"""Pi camera access through picamera2 (installed from apt, only available on the Pi)."""

from __future__ import annotations

import io
from collections.abc import Iterator
from datetime import datetime
from pathlib import Path

import numpy as np

from .config import CameraConfig
from .overlay import stamp


class Camera:
    def __init__(self, config: CameraConfig):
        from libcamera import Transform, controls
        from picamera2 import Picamera2

        self._camera = Picamera2()
        camera_controls = {"FrameRate": config.frame_rate}
        if "AfMode" in self._camera.camera_controls:  # Camera Module 3 autofocus
            camera_controls["AfMode"] = controls.AfModeEnum.Continuous
        self._camera.configure(
            self._camera.create_video_configuration(
                main={"size": tuple(config.still_size)},
                lores={"size": tuple(config.lores_size), "format": "YUV420"},
                transform=Transform(hflip=config.hflip, vflip=config.vflip),
                controls=camera_controls,
                buffer_count=3,
            )
        )
        self._lores_height = config.lores_size[1]
        self._timestamp_format = config.timestamp_format
        self._camera.start()

    def lores_frames(self) -> Iterator[np.ndarray]:
        """Grayscale frames from the low resolution stream (the Y plane of YUV420)."""
        while True:
            yield self._camera.capture_array("lores")[: self._lores_height]

    def capture(self, path: Path, when: datetime) -> None:
        """Save the current full resolution frame as a JPEG, timestamped with when."""
        image = self._camera.capture_image("main").convert("RGB")
        if self._timestamp_format:
            stamp(image, when, self._timestamp_format)
        image.save(path, quality=90)

    def start_stream(self, output: io.BufferedIOBase) -> None:
        """Encode the main stream to MJPEG (hardware encoder on a Pi 3/4) into output."""
        from picamera2.encoders import MJPEGEncoder
        from picamera2.outputs import FileOutput

        self._encoder = MJPEGEncoder()
        self._camera.start_encoder(self._encoder, FileOutput(output), name="main")

    def stop_stream(self) -> None:
        self._camera.stop_encoder(self._encoder)
        self._encoder = None

    def close(self) -> None:
        self._camera.close()
