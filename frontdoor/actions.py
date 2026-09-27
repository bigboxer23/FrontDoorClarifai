"""Side effects of an analysed image: storage, email, and webhooks."""

from __future__ import annotations

import json
import logging
import smtplib
import time
import urllib.request
from datetime import date
from email.message import EmailMessage
from pathlib import Path
from urllib.parse import quote

from .config import Config

logger = logging.getLogger(__name__)


class MonthlyCounter:
    """A count saved to disk that starts over each calendar month."""

    def __init__(self, path: Path, today=date.today):
        self._path = path
        self._today = today

    def next(self) -> int:
        """The number the next event will get. Call record() once it has happened."""
        month = self._today().strftime("%Y-%m")
        try:
            saved = json.loads(self._path.read_text())
        except (OSError, ValueError):
            saved = {}
        return saved.get("count", 0) + 1 if saved.get("month") == month else 1

    def record(self, count: int) -> None:
        temporary = self._path.with_suffix(".tmp")
        temporary.write_text(json.dumps({"month": self._today().strftime("%Y-%m"), "count": count}))
        temporary.replace(self._path)


class Actions:
    def __init__(
        self,
        config: Config,
        s3_client=None,
        smtp_factory=smtplib.SMTP_SSL,
        opener=urllib.request.urlopen,
        email_counter: MonthlyCounter | None = None,
    ):
        self._config = config
        self._email_counter = email_counter or MonthlyCounter(Path(config.capture_dir) / "email-count.json")
        self._s3 = s3_client
        if self._s3 is None and config.s3.bucket:
            import boto3

            self._s3 = boto3.client("s3", region_name=config.s3.region)
        self._smtp_factory = smtp_factory
        self._opener = opener

    def store(self, path: Path, folder: str) -> str | None:
        """Move the image to S3 (or a local folder without S3) under folder/YYYY/MM/. Returns its key."""
        key = f"{folder}/{time.strftime('%Y/%m')}/{path.name}"
        try:
            if self._s3 is not None:
                logger.info("Uploading %s to s3://%s/%s", path.name, self._config.s3.bucket, key)
                self._s3.upload_file(str(path), self._config.s3.bucket, key, ExtraArgs={"ContentType": "image/jpeg"})
                path.unlink()
            else:
                destination = Path(self._config.capture_dir) / key
                destination.parent.mkdir(parents=True, exist_ok=True)
                path.replace(destination)
            return key
        except Exception:
            logger.exception("Problem storing %s, leaving it in place", path)
            return None

    def notify(self) -> None:
        url = self._config.notify.notification_url
        if not url:
            return
        logger.info("Sending notification")
        self._request(url)

    def after_stored(self, key: str) -> None:
        callback = self._config.notify.after_stored_callback
        if not callback or self._s3 is None:
            return
        presigned = self._s3.generate_presigned_url(
            "get_object", Params={"Bucket": self._config.s3.bucket, "Key": key}, ExpiresIn=300
        )
        self._request(callback % quote(presigned, safe=""), data=b"")

    def email(self, files: list[Path]) -> None:
        email = self._config.email
        if not files or not (email.to and email.account and email.password):
            return
        count = self._email_counter.next()
        logger.info("Emailing %d image(s) as #%d this month, first %s", len(files), count, files[0].name)
        message = EmailMessage()
        message["Subject"] = f"Front Door Motion {count}"
        message["From"] = email.account
        message["To"] = email.to
        for file in files:
            message.add_attachment(file.read_bytes(), maintype="image", subtype="jpeg", filename=file.name)
        try:
            with self._smtp_factory("smtp.gmail.com", 465, timeout=30) as smtp:
                smtp.login(email.account, email.password)
                smtp.send_message(message)
        except Exception:
            logger.exception("Problem sending email")
            return
        self._email_counter.record(count)

    def _request(self, url: str, data: bytes | None = None) -> None:
        try:
            self._opener(urllib.request.Request(url, data=data), timeout=10).close()
        except Exception:
            logger.exception("Problem calling %s", url)
