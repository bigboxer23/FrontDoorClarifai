import numpy as np
from PIL import Image

from frontdoor.motion import MotionDetector, load_mask

SHAPE = (180, 320)


def still():
    return np.full(SHAPE, 100, dtype=np.uint8)


def with_block(top=50, left=50, size=60):
    frame = still()
    frame[top : top + size, left : left + size] = 250
    return frame


def detector(**kwargs):
    args = dict(pixel_threshold=25, min_changed_fraction=0.005, min_frames=3)
    args.update(kwargs)
    return MotionDetector(**args)


def test_static_scene_is_not_motion():
    motion = detector()
    assert not any(motion.update(still()) for _ in range(20))


def test_motion_requires_consecutive_frames():
    motion = detector()
    motion.update(still())
    assert [motion.update(with_block()) for _ in range(3)] == [False, False, True]


def test_streak_resets_when_motion_stops():
    motion = detector(learning_rate=0)
    motion.update(still())
    motion.update(with_block())
    motion.update(with_block())
    assert not motion.update(still())
    assert not motion.update(with_block())


def test_small_change_is_ignored():
    motion = detector()
    motion.update(still())
    assert not any(motion.update(with_block(size=5)) for _ in range(10))


def test_masked_area_is_ignored():
    mask = np.ones(SHAPE, dtype=bool)
    mask[:90, :160] = False
    motion = detector(mask=mask)
    motion.update(still())
    assert not any(motion.update(with_block(top=10, left=10)) for _ in range(10))
    assert any(motion.update(with_block(top=100, left=200)) for _ in range(10))


def test_background_adapts_to_lasting_change():
    motion = detector(learning_rate=0.5)
    motion.update(still())
    results = [motion.update(with_block()) for _ in range(20)]
    assert results[2] and not results[-1]


def test_load_mask_scales_and_thresholds(tmp_path):
    image = Image.new("L", (640, 360), 255)
    image.paste(0, (0, 0, 320, 180))
    path = tmp_path / "mask.pgm"
    image.save(path)
    mask = load_mask(str(path), (320, 180))
    assert mask.shape == SHAPE
    assert not mask[:90, :160].any()
    assert mask[90:, 160:].all()
