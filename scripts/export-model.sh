#!/usr/bin/env bash
# Exports YOLO11n to ONNX at 320px into models/. Run on a dev machine, not the Pi (it installs PyTorch).
# Note: Ultralytics YOLO weights are AGPL-3.0 licensed, so the model isn't committed to this repo.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 -m venv .venv-export
.venv-export/bin/pip install --quiet ultralytics onnx onnxslim
mkdir -p models
cd models
../.venv-export/bin/yolo export model=yolo11n.pt format=onnx imgsz=320 simplify=True
rm -f yolo11n.pt
ls -lh yolo11n.onnx
