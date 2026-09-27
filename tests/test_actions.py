import time
from email import message_from_bytes, policy
from urllib.parse import unquote

from datetime import date

from frontdoor.actions import Actions, MonthlyCounter
from frontdoor.config import Config


class FakeS3:
    def __init__(self, fail=False):
        self.fail = fail
        self.uploads = []

    def upload_file(self, filename, bucket, key, ExtraArgs=None):
        if self.fail:
            raise RuntimeError("no network")
        self.uploads.append((filename, bucket, key))

    def generate_presigned_url(self, operation, Params, ExpiresIn):
        return f"https://{Params['Bucket']}.s3.amazonaws.com/{Params['Key']}?sig=a&b=c"


class FakeSmtp:
    sent = []

    def __init__(self, host, port, timeout):
        self.host = host

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def login(self, account, password):
        self.login_args = (account, password)

    def send_message(self, message):
        FakeSmtp.sent.append(message)


class Opener:
    def __init__(self):
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        return self

    def close(self):
        pass


def config(tmp_path, **s3):
    config = Config(capture_dir=str(tmp_path))
    config.s3.bucket = s3.get("bucket")
    config.notify.notification_url = "http://hub/Notification"
    config.notify.after_stored_callback = "http://hub/preview?url=%s"
    config.email.to, config.email.account, config.email.password = "to@x.com", "from@gmail.com", "pw"
    return config


def image(tmp_path, name="a.jpg"):
    path = tmp_path / name
    path.write_bytes(b"jpeg")
    return path


def test_store_uploads_to_dated_key_and_deletes(tmp_path):
    s3 = FakeS3()
    path = image(tmp_path)
    key = Actions(config(tmp_path, bucket="b"), s3_client=s3).store(path, "Success")
    assert key == f"Success/{time.strftime('%Y/%m')}/a.jpg"
    assert s3.uploads == [(str(path), "b", key)]
    assert not path.exists()


def test_store_keeps_file_when_upload_fails(tmp_path):
    path = image(tmp_path)
    assert Actions(config(tmp_path, bucket="b"), s3_client=FakeS3(fail=True)).store(path, "Failure") is None
    assert path.exists()


def test_store_without_s3_moves_locally(tmp_path):
    path = image(tmp_path)
    key = Actions(config(tmp_path)).store(path, "Failure")
    assert (tmp_path / key).read_bytes() == b"jpeg"
    assert not path.exists()


def test_after_stored_posts_encoded_presigned_url(tmp_path):
    opener = Opener()
    Actions(config(tmp_path, bucket="b"), s3_client=FakeS3(), opener=opener).after_stored("Success/a.jpg")
    [request] = opener.requests
    assert request.get_method() == "POST"
    prefix = "http://hub/preview?url="
    assert request.full_url.startswith(prefix)
    assert "&" not in request.full_url[len(prefix) :]
    assert unquote(request.full_url[len(prefix) :]) == "https://b.s3.amazonaws.com/Success/a.jpg?sig=a&b=c"


def test_notify_gets_url(tmp_path):
    opener = Opener()
    Actions(config(tmp_path), opener=opener).notify()
    assert [(r.get_method(), r.full_url) for r in opener.requests] == [("GET", "http://hub/Notification")]


def test_email_has_only_image_attachments(tmp_path):
    FakeSmtp.sent.clear()
    Actions(config(tmp_path), smtp_factory=FakeSmtp).email([image(tmp_path, "a.jpg"), image(tmp_path, "b.jpg")])
    [message] = FakeSmtp.sent
    parsed = message_from_bytes(message.as_bytes(), policy=policy.default)
    assert parsed["To"] == "to@x.com"
    assert [p.get_content_type() for p in parsed.walk() if not p.is_multipart()] == ["image/jpeg", "image/jpeg"]
    assert [p.get_filename() for p in parsed.iter_attachments()] == ["a.jpg", "b.jpg"]


def test_email_subject_counts_up_and_survives_restarts(tmp_path):
    FakeSmtp.sent.clear()
    for _ in range(2):
        Actions(config(tmp_path), smtp_factory=FakeSmtp).email([image(tmp_path)])
    Actions(config(tmp_path), smtp_factory=FakeSmtp).email([image(tmp_path)])
    assert [m["Subject"] for m in FakeSmtp.sent] == ["Front Door Motion 1", "Front Door Motion 2", "Front Door Motion 3"]


def test_email_count_not_used_up_by_failed_send(tmp_path):
    class BrokenSmtp(FakeSmtp):
        def login(self, account, password):
            raise OSError("smtp down")

    FakeSmtp.sent.clear()
    Actions(config(tmp_path), smtp_factory=BrokenSmtp).email([image(tmp_path)])
    Actions(config(tmp_path), smtp_factory=FakeSmtp).email([image(tmp_path)])
    assert [m["Subject"] for m in FakeSmtp.sent] == ["Front Door Motion 1"]


def test_monthly_counter_resets_each_month(tmp_path):
    today = date(2026, 9, 30)
    counter = MonthlyCounter(tmp_path / "count.json", today=lambda: today)
    assert counter.next() == 1
    counter.record(1)
    counter.record(counter.next())
    assert counter.next() == 3
    today = date(2026, 10, 1)
    assert counter.next() == 1


def test_email_skipped_when_not_configured(tmp_path):
    FakeSmtp.sent.clear()
    Actions(Config(capture_dir=str(tmp_path)), smtp_factory=FakeSmtp).email([image(tmp_path)])
    assert FakeSmtp.sent == []
