"""Cheap frame-differencing motion detection on the camera's low resolution stream."""

from __future__ import annotations

import numpy as np
from PIL import Image


def load_mask(path: str, size: tuple[int, int]) -> np.ndarray:
    """Load a mask image scaled to size (width, height). True where motion should be watched."""
    with Image.open(path) as image:
        return np.asarray(image.convert("L").resize(size, Image.NEAREST)) > 127


class MotionDetector:
    """Compares each grayscale frame against a running-average background."""

    def __init__(
        self,
        pixel_threshold: int,
        min_changed_fraction: float,
        min_frames: int,
        mask: np.ndarray | None = None,
        learning_rate: float = 0.05,
    ):
        self._pixel_threshold = pixel_threshold
        self._min_changed_fraction = min_changed_fraction
        self._min_frames = min_frames
        self._mask = mask
        self._watched = int(mask.sum()) if mask is not None else None
        self._learning_rate = learning_rate
        self._background: np.ndarray | None = None
        self._streak = 0

    def update(self, frame: np.ndarray) -> bool:
        """Feed the next grayscale frame. Returns True while motion has lasted at least min_frames frames."""
        current = frame.astype(np.float32)
        if self._background is None:
            self._background = current
            return False

        changed = np.abs(current - self._background) > self._pixel_threshold
        if self._mask is not None:
            changed &= self._mask
        watched = self._watched if self._watched is not None else changed.size
        fraction = changed.sum() / watched if watched else 0.0
        self._background += self._learning_rate * (current - self._background)

        self._streak = self._streak + 1 if fraction >= self._min_changed_fraction else 0
        return self._streak >= self._min_frames
