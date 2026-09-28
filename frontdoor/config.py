"""Loads the TOML config file into typed sections."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path

DEFAULT_PATH = Path("/etc/frontdoor/config.toml")


@dataclass
class CameraConfig:
    # 16:9 matches Camera Module 3. Use 4:3 sizes like (1640, 1232) and (320, 240) for v1/v2.
    still_size: tuple[int, int] = (1920, 1080)
    lores_size: tuple[int, int] = (320, 180)
    frame_rate: float = 10
    hflip: bool = False
    vflip: bool = False
    # strftime format stamped in the bottom right of captured stills. Empty to disable.
    timestamp_format: str = "%Y-%m-%d %H:%M:%S"


@dataclass
class MotionConfig:
    # Black areas of the mask are ignored, white areas are watched (same convention as motion's mask.pgm).
    mask_path: str | None = None
    # How much a lores pixel must change (0-255) to count as changed.
    pixel_threshold: int = 25
    # Fraction of watched pixels that must change for a frame to count as motion.
    min_changed_fraction: float = 0.005
    # Consecutive motion frames required before capturing.
    min_frames: int = 5
    # Minimum seconds between stills while motion continues.
    capture_interval_seconds: float = 5


@dataclass
class DetectionConfig:
    model_path: str = "/opt/frontdoor/models/yolo11n.onnx"
    # Minimum person confidence (0-1) for an image to count as a success.
    threshold: float = 0.5
    threads: int = 4


@dataclass
class NotifyConfig:
    # After a success, further successes within this many minutes are batched into one email.
    batch_minutes: float = 5
    # Successes this many seconds after an immediate email are stored in S3 without emailing.
    quiet_seconds: float = 60
    # GET on the first success of a batch.
    notification_url: str | None = None
    # POSTed after a lone success is stored in S3. %s is replaced with a presigned URL to the image.
    after_stored_callback: str | None = None


@dataclass
class EmailConfig:
    to: str | None = None
    account: str | None = None
    password: str | None = None


@dataclass
class S3Config:
    # Without a bucket, images are kept under capture_dir/Success and capture_dir/Failure instead.
    bucket: str | None = None
    region: str | None = None


@dataclass
class Config:
    capture_dir: str = "/var/lib/frontdoor"
    control_port: int = 8082
    # Optional "host:port" of a logstash TCP input.
    logstash: str | None = None
    camera: CameraConfig = field(default_factory=CameraConfig)
    motion: MotionConfig = field(default_factory=MotionConfig)
    detection: DetectionConfig = field(default_factory=DetectionConfig)
    notify: NotifyConfig = field(default_factory=NotifyConfig)
    email: EmailConfig = field(default_factory=EmailConfig)
    s3: S3Config = field(default_factory=S3Config)


def load_config(path: Path = DEFAULT_PATH) -> Config:
    with open(path, "rb") as f:
        return _build(Config, tomllib.load(f), "")


def _build(cls, data: dict, section: str):
    known = {f.name: f for f in fields(cls)}
    unknown = set(data) - set(known)
    if unknown:
        raise ValueError(f"Unknown config key(s) in [{section or 'top level'}]: {', '.join(sorted(unknown))}")
    defaults = cls()
    values = {}
    for name, value in data.items():
        default = getattr(defaults, name)
        if isinstance(value, dict):
            value = _build(type(default), value, name)
        elif isinstance(default, tuple):
            value = tuple(value)
        values[name] = value
    return cls(**values)
