"""Ships log records to a logstash TCP input as JSON lines, like logback's LogstashTcpSocketAppender."""

from __future__ import annotations

import json
import logging
import socket
import time
from datetime import datetime, timezone

RECONNECT_SECONDS = 30


class LogstashHandler(logging.Handler):
    def __init__(self, host: str, port: int):
        super().__init__()
        self._address = (host, port)
        self._hostname = socket.gethostname()
        self._socket: socket.socket | None = None
        self._next_attempt = 0.0

    def format_record(self, record: logging.LogRecord) -> bytes:
        payload = {
            "@timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
            "@version": "1",
            "message": record.getMessage(),
            "logger_name": record.name,
            "thread_name": record.threadName,
            "level": record.levelname,
            # logback's level_value scale happens to be Python's levelno * 1000 (INFO = 20000)
            "level_value": record.levelno * 1000,
            "HOSTNAME": self._hostname,
        }
        if record.exc_info:
            payload["stack_trace"] = logging.Formatter().formatException(record.exc_info)
        return json.dumps(payload).encode() + b"\n"

    def emit(self, record: logging.LogRecord) -> None:
        try:
            data = self.format_record(record)
            if self._socket is None:
                if time.monotonic() < self._next_attempt:
                    return
                self._next_attempt = time.monotonic() + RECONNECT_SECONDS
                self._socket = socket.create_connection(self._address, timeout=5)
            self._socket.sendall(data)
        except OSError:
            self._close()
        except Exception:
            self.handleError(record)

    def _close(self) -> None:
        if self._socket is not None:
            try:
                self._socket.close()
            finally:
                self._socket = None

    def close(self) -> None:
        self._close()
        super().close()
