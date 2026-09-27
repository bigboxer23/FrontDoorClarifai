"""On-demand MJPEG streaming: the camera's JPEG encoder only runs while someone is watching."""

from __future__ import annotations

import io
import logging
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager

logger = logging.getLogger(__name__)


class FrameBuffer(io.BufferedIOBase):
    """Holds the latest encoded frame. The encoder writes to it, viewers wait on it."""

    def __init__(self):
        self._condition = threading.Condition()
        self._frame = b""
        self._sequence = 0

    def writable(self) -> bool:
        return True

    def write(self, data) -> int:
        with self._condition:
            self._frame = bytes(data)
            self._sequence += 1
            self._condition.notify_all()
        return len(data)

    @property
    def sequence(self) -> int:
        with self._condition:
            return self._sequence

    def next_frame(self, after: int, timeout: float) -> tuple[int, bytes] | None:
        """Wait for a frame newer than sequence number after. None if none arrives within timeout."""
        with self._condition:
            if not self._condition.wait_for(lambda: self._sequence > after, timeout):
                return None
            return self._sequence, self._frame


class Streamer:
    def __init__(self, start: Callable[[io.BufferedIOBase], None], stop: Callable[[], None]):
        self._start = start
        self._stop = stop
        self._lock = threading.Lock()
        self._viewers = 0
        self.frames = FrameBuffer()

    @contextmanager
    def viewing(self) -> Iterator[FrameBuffer]:
        with self._lock:
            if self._viewers == 0:
                logger.info("Starting stream")
                self._start(self.frames)
            self._viewers += 1
        try:
            yield self.frames
        finally:
            with self._lock:
                self._viewers -= 1
                if self._viewers == 0:
                    logger.info("Stopping stream")
                    self._stop()
