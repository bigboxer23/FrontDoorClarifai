[![CodeQL](https://github.com/bigboxer23/FrontDoorClarifai/actions/workflows/codeql.yml/badge.svg)](https://github.com/bigboxer23/FrontDoorClarifai/actions/workflows/codeql.yml)

## Raspberry Pi AI security camera

A Python service for a Raspberry Pi with a Pi Camera Module that watches for motion and runs on-device person
detection. It replaces the old stack of the `motion` daemon plus a Spring Boot service calling Clarifai's (now
discontinued) vision API.

1. picamera2 streams a small 320x180 frame for cheap frame-differencing motion detection, with an optional mask.
2. When motion lasts `min_frames` frames, a full resolution still is captured, at most every `capture_interval_seconds`.
3. A YOLO11n model (ONNX, 320px, run with onnxruntime) scores how confident it is that a person is in the still.
4. At or above `threshold` it's a success, otherwise a failure. Failures are uploaded to S3 under
   `Failure/YYYY/MM/`. Successes go to `Success/YYYY/MM/`, are emailed, and hit the notification webhook.

Successes are batched to prevent spamming: the first one after `batch_minutes` without a success notifies and emails
right away. Successes in the next `quiet_seconds` (60) are usually the same visitor, so they're uploaded to S3 without
an email. After that, successes within `batch_minutes` of the previous one are collected and emailed together once
`batch_minutes` pass without another.
When a success is sent on its own, `after_stored_callback` also gets a 5 minute presigned S3 link to the image.

### Requirements

- Raspberry Pi 3 or newer running Raspberry Pi OS **64-bit** (Lite is fine) with a Pi Camera Module
- Roughly 0.5-1s per analysed image on a Pi 3. Analysis runs in the background, so motion detection keeps going.

### Setup

1. Export the model on a dev machine (installs PyTorch into `.venv-export`, so don't do this on the Pi):
   `./scripts/export-model.sh`. Ultralytics YOLO weights are AGPL-3.0 licensed, so they're not committed here.
2. Deploy: `./deploy/deploy.sh pi@frontdoor3`. This syncs the repo and runs `deploy/install.sh` on the Pi, which
   installs picamera2 from apt, creates a `frontdoor` user and a venv under `/opt/frontdoor`, and installs the
   systemd service. Rerun it to deploy changes.
3. Configure it: copy `config.example.toml` to `local/config.toml` and edit it. Anything in the gitignored `local/`
   folder, such as `config.toml`, `mask.png` and `aws-credentials` (AWS keys for S3), is installed into
   `/etc/frontdoor/` on every deploy. Without `local/config.toml`, the first install creates
   `/etc/frontdoor/config.toml` from the example for you to edit on the Pi.
4. Watch the logs with `journalctl -u frontdoor -f` and tune `threshold` from the logged person scores.

`mask_path` takes a black (ignore) and white (watch) image with the camera's aspect ratio, in the same format as
the old `motion` mask.pgm. Draw it over a captured still, and make sure the `frontdoor` user can read it.

### Commands

- `frontdoor run`: the service itself.
- `frontdoor analyze IMAGE...`: prints each image's person score and verdict. Works without a config file on a dev
  machine: `frontdoor analyze --model models/yolo11n.onnx photo.jpg`. Add `--act` to also run the real
  success/failure actions on copies of the images.

### Live stream

Open `http://<pi>:8082/` in a browser (or `http://<pi>:8082/stream.mjpg` in VLC) for a live MJPEG view of the
camera. The Pi's hardware JPEG encoder only runs while someone is watching, and motion detection keeps going. There's
no authentication, so keep port 8082 on your home network.

### Pause API

Kept compatible with the old service, on `control_port` (8082):

- `POST /pause/{seconds}`: stop capturing for that many seconds. Returns the seconds remaining.
- `POST /enable`: resume immediately.
- `GET /isPaused`: seconds remaining in the pause, `0` when running.

### Development

```
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest
```

The camera code only runs on a Pi. Everything else is tested with fakes, plus a real-model test that runs when
`models/yolo11n.onnx` exists.
