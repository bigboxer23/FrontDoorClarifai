import threading
import urllib.request

import pytest

from frontdoor.control import PauseState, start_control_server
from frontdoor.stream import FrameBuffer, Streamer


class FakeEncoder:
    """Writes numbered fake JPEG frames to the output from a thread, like picamera2's encoder does."""

    def __init__(self):
        self.starts = 0
        self.stops = 0
        self._stop = None

    def start(self, output):
        self.starts += 1
        self._stop = threading.Event()
        stop = self._stop

        def run():
            n = 0
            while not stop.wait(0.01):
                n += 1
                output.write(b"\xff\xd8frame%d\xff\xd9" % n)

        threading.Thread(target=run, daemon=True).start()

    def stop(self):
        self.stops += 1
        self._stop.set()


def test_frame_buffer_waits_for_newer_frame():
    frames = FrameBuffer()
    assert frames.next_frame(0, timeout=0.01) is None
    frames.write(b"a")
    assert frames.next_frame(0, timeout=0.01) == (1, b"a")
    assert frames.next_frame(1, timeout=0.01) is None


def test_encoder_runs_only_while_someone_is_viewing():
    encoder = FakeEncoder()
    streamer = Streamer(encoder.start, encoder.stop)
    with streamer.viewing():
        with streamer.viewing():
            assert (encoder.starts, encoder.stops) == (1, 0)
        assert encoder.stops == 0
    assert (encoder.starts, encoder.stops) == (1, 1)
    with streamer.viewing():
        assert encoder.starts == 2
    assert encoder.stops == 2


def test_failed_start_does_not_count_as_viewer():
    def broken(output):
        raise RuntimeError("no encoder")

    encoder = FakeEncoder()
    streamer = Streamer(broken, encoder.stop)
    with pytest.raises(RuntimeError):
        with streamer.viewing():
            pass
    assert encoder.stops == 0


@pytest.fixture
def server():
    encoder = FakeEncoder()
    server = start_control_server(0, PauseState(), Streamer(encoder.start, encoder.stop), host="127.0.0.1")
    yield f"http://127.0.0.1:{server.server_address[1]}", encoder
    server.shutdown()


def test_stream_serves_multipart_jpegs(server):
    base, encoder = server
    with urllib.request.urlopen(base + "/stream.mjpg", timeout=5) as response:
        assert response.headers["Content-Type"] == "multipart/x-mixed-replace; boundary=FRAME"
        for _ in range(3):
            assert response.readline() == b"--FRAME\r\n"
            assert response.readline() == b"Content-Type: image/jpeg\r\n"
            length = int(response.readline().split(b":")[1])
            assert response.readline() == b"\r\n"
            frame = response.read(length)
            assert frame.startswith(b"\xff\xd8frame") and frame.endswith(b"\xff\xd9")
            assert response.readline() == b"\r\n"
    assert encoder.starts == 1


def test_viewer_page_embeds_stream(server):
    base, _ = server
    with urllib.request.urlopen(base + "/", timeout=5) as response:
        assert b'src="/stream.mjpg"' in response.read()


def test_no_stream_without_streamer():
    server = start_control_server(0, PauseState(), host="127.0.0.1")
    try:
        with pytest.raises(urllib.error.HTTPError, match="404"):
            urllib.request.urlopen(f"http://127.0.0.1:{server.server_address[1]}/stream.mjpg", timeout=5)
    finally:
        server.shutdown()
