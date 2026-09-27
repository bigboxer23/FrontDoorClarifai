from datetime import datetime

import numpy as np
from PIL import Image

from frontdoor.overlay import stamp


def test_stamp_draws_in_bottom_right_only():
    image = Image.new("RGB", (1920, 1080), (60, 90, 60))
    stamp(image, datetime(2026, 9, 23, 7, 5, 9), "%Y-%m-%d %H:%M:%S")
    pixels = np.asarray(image)
    changed = np.argwhere((pixels != (60, 90, 60)).any(axis=2))
    top, left = changed.min(axis=0)
    bottom, right = changed.max(axis=0)
    assert top > 1080 * 0.85 and left > 1920 * 0.5
    assert bottom < 1080 - 5 and right < 1920 - 5
    # white text and black outline are both present
    assert (pixels > 240).all(axis=2).any()
    assert (pixels < 15).all(axis=2).any()


def test_stamp_scales_with_image():
    small, large = Image.new("RGB", (1280, 720)), Image.new("RGB", (3840, 2160))
    when = datetime(2026, 9, 23)
    stamp(small, when, "%H:%M:%S")
    stamp(large, when, "%H:%M:%S")
    width = lambda image: np.ptp(np.argwhere(np.asarray(image).any(axis=2))[:, 1])
    assert width(large) > 2 * width(small)
