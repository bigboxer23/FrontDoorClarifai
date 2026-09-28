"""The long-running camera service: watch for motion, capture stills, analyze them in the background."""

from __future__ import annotations

import logging
import queue
import threading
import time
from datetime import datetime
from pathlib import Path

from .actions import Actions
from .config import Config
from .control import PauseState, start_control_server
from .detector import PersonDetector
from .motion import MotionDetector, load_mask
from .pipeline import Pipeline
from .stream import Streamer

logger = logging.getLogger(__name__)

QUEUE_SIZE = 10
# Exposure and autofocus settle after the camera starts, which would otherwise register as motion.
WARMUP_SECONDS = 3


def build_pipeline(config: Config) -> Pipeline:
    return Pipeline(
        PersonDetector(config.detection.model_path, config.detection.threads),
        Actions(config),
        config.detection.threshold,
        config.notify.batch_minutes,
        config.notify.quiet_seconds,
    )


def run(config: Config) -> None:
    from .camera import Camera

    capture_dir = Path(config.capture_dir)
    capture_dir.mkdir(parents=True, exist_ok=True)
    pipeline = build_pipeline(config)
    pause = PauseState()

    work: queue.Queue[Path] = queue.Queue(maxsize=QUEUE_SIZE)
    threading.Thread(target=_analyze_forever, args=(pipeline, work), name="analyze", daemon=True).start()

    motion_config = config.motion
    mask = None
    if motion_config.mask_path:
        mask = load_mask(motion_config.mask_path, config.camera.lores_size)
        logger.info("Motion mask %s watches %.0f%% of the frame", motion_config.mask_path, mask.mean() * 100)
    motion = MotionDetector(
        motion_config.pixel_threshold, motion_config.min_changed_fraction, motion_config.min_frames, mask
    )
    camera = Camera(config.camera)
    start_control_server(config.control_port, pause, Streamer(camera.start_stream, camera.stop_stream))
    logger.info("Watching for motion")
    started = time.monotonic()
    last_capture = float("-inf")
    try:
        for frame in camera.lores_frames():
            if time.monotonic() - started < WARMUP_SECONDS:
                continue
            if not motion.update(frame) or pause.active:
                continue
            now = time.monotonic()
            if now - last_capture < motion_config.capture_interval_seconds:
                continue
            last_capture = now
            when = datetime.now()
            path = capture_dir / f"{when:%Y%m%d-%H%M%S-%f}.jpg"
            camera.capture(path, when)
            try:
                work.put_nowait(path)
            except queue.Full:
                logger.warning("Analysis is behind, dropping %s", path.name)
                path.unlink()
    finally:
        camera.close()


def _analyze_forever(pipeline: Pipeline, work: queue.Queue[Path]) -> None:
    while True:
        path = work.get()
        try:
            pipeline.analyze(path)
        except Exception:
            logger.exception("Problem analyzing %s", path)
