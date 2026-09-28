"""Decides what happens to each captured image, batching successes to avoid spamming."""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from .actions import Actions

logger = logging.getLogger(__name__)


class Pipeline:
    """The first success after batch_minutes without one notifies and emails immediately. Successes in the
    following quiet_seconds are usually the same visitor, so they're stored without an email. Later successes
    within batch_minutes of the previous one are collected and emailed together once batch_minutes pass
    without another."""

    def __init__(
        self,
        detector,
        actions: Actions,
        threshold: float,
        batch_minutes: float,
        quiet_seconds: float = 0,
        clock=time.monotonic,
        timer_factory=threading.Timer,
    ):
        self._detector = detector
        self._actions = actions
        self._threshold = threshold
        self._batch_seconds = batch_minutes * 60
        self._quiet_seconds = quiet_seconds
        self._clock = clock
        self._timer_factory = timer_factory
        self._lock = threading.Lock()
        self._batch: list[Path] = []
        self._last_success: float | None = None
        self._quiet_until = float("-inf")
        self._timer = None

    def analyze(self, path: Path) -> float:
        started = self._clock()
        score = self._detector.person_score(path)
        success = score >= self._threshold
        logger.info(
            "%s person %.0f%% (%s, %.2fs)",
            path.name,
            score * 100,
            "success" if success else "failure",
            self._clock() - started,
        )
        if success:
            self._on_success(path)
        else:
            self._actions.store(path, "Failure")
        return score

    def flush(self) -> None:
        """Send any batched successes now."""
        with self._lock:
            if self._timer is not None:
                self._timer.cancel()
                self._timer = None
            files, self._batch = self._batch, []
        if files:
            self._send(files, notify=False)

    def _on_success(self, path: Path) -> None:
        with self._lock:
            now = self._clock()
            if now < self._quiet_until:
                # Not counted as activity, so it can't delay the next visitor's immediate email.
                files = None
            else:
                self._batch.append(path)
                immediate = self._last_success is None or now - self._last_success > self._batch_seconds
                self._last_success = now
                if self._timer is not None:
                    self._timer.cancel()
                    self._timer = None
                if immediate:
                    files, self._batch = self._batch, []
                    self._quiet_until = now + self._quiet_seconds
                else:
                    logger.info("Adding %s to batch", path.name)
                    self._timer = self._timer_factory(self._batch_seconds, self.flush)
                    self._timer.daemon = True
                    self._timer.start()
                    return
        if files is None:
            logger.info("%s is within %.0fs of the last email, storing without emailing", path.name, self._quiet_seconds)
            self._actions.store(path, "Success")
        else:
            self._send(files, notify=True)

    def _send(self, files: list[Path], notify: bool) -> None:
        if notify:
            self._actions.notify()
        self._actions.email(files)
        for file in files:
            key = self._actions.store(file, "Success")
            if notify and key and len(files) == 1:
                self._actions.after_stored(key)
