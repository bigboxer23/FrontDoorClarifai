"""HTTP endpoints: pause/enable (compatible with the old Java service's API) and the live stream."""

from __future__ import annotations

import json
import logging
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .stream import Streamer

logger = logging.getLogger(__name__)

# Give up on a stream if the encoder stops producing frames.
FRAME_TIMEOUT_SECONDS = 5
BOUNDARY = "FRAME"
VIEWER_PAGE = b"""<!doctype html>
<html><head><title>Front door</title><meta name="viewport" content="width=device-width">
<style>body{margin:0;background:#000}img{display:block;width:100%;height:100vh;object-fit:contain}</style>
</head><body><img src="/stream.mjpg" alt="Front door camera"></body></html>
"""


class PauseState:
    def __init__(self, clock=time.monotonic):
        self._clock = clock
        self._until = 0.0

    def pause(self, seconds: int) -> None:
        logger.info("Pausing for %d seconds", seconds)
        self._until = self._clock() + seconds

    def enable(self) -> None:
        logger.info("Enabling running again")
        self._until = 0.0

    def remaining(self) -> int:
        """Whole seconds left in the pause, 0 when not paused."""
        return max(0, int(self._until - self._clock()))

    @property
    def active(self) -> bool:
        return self._until > self._clock()


def start_control_server(
    port: int, pause: PauseState, streamer: Streamer | None = None, host: str = "0.0.0.0"
) -> ThreadingHTTPServer:
    """POST /pause/{seconds}, POST /enable, GET /isPaused, and with a streamer GET / and GET /stream.mjpg."""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/isPaused":
                self._reply(pause.remaining())
            elif self.path == "/" and streamer:
                self._send(VIEWER_PAGE, "text/html")
            elif self.path == "/stream.mjpg" and streamer:
                self._stream()
            else:
                self.send_error(404)

        def do_POST(self):
            match = re.fullmatch(r"/pause/(\d+)", self.path)
            if match:
                pause.pause(int(match[1]))
                self._reply(pause.remaining())
            elif self.path == "/enable":
                pause.enable()
                self._reply(None)
            else:
                self.send_error(404)

        def _reply(self, value):
            self._send(b"" if value is None else json.dumps(value).encode(), "application/json")

        def _send(self, body: bytes, content_type: str):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _stream(self):
            self.send_response(200)
            self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
            self.send_header("Cache-Control", "no-cache, private")
            self.end_headers()
            logger.info("Stream viewer connected from %s", self.client_address[0])
            with streamer.viewing() as frames:
                sequence = frames.sequence  # skip any stale frame from a previous stream
                try:
                    while True:
                        result = frames.next_frame(sequence, FRAME_TIMEOUT_SECONDS)
                        if result is None:
                            logger.warning("No frames from the camera, ending stream")
                            return
                        sequence, frame = result
                        self.wfile.write(
                            f"--{BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame)}\r\n\r\n".encode()
                        )
                        self.wfile.write(frame)
                        self.wfile.write(b"\r\n")
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    logger.info("Stream viewer disconnected from %s", self.client_address[0])

        def log_message(self, format, *args):
            logger.debug(format, *args)

    server = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(target=server.serve_forever, name="control", daemon=True).start()
    return server
