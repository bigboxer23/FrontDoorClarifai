"""Person detection with a YOLO model exported to ONNX (see scripts/export-model.sh)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

PERSON_CLASS = 0  # COCO class index


def letterbox(image: Image.Image, size: int) -> np.ndarray:
    """Scale image to fit a size x size square, pad with gray, return as a 1x3xHxW float tensor."""
    image = image.convert("RGB")
    scale = size / max(image.size)
    width, height = round(image.width * scale), round(image.height * scale)
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(image.resize((width, height), Image.BILINEAR), ((size - width) // 2, (size - height) // 2))
    return (np.asarray(canvas, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]


class PersonDetector:
    def __init__(self, model_path: str | Path, threads: int = 4, session=None):
        if session is None:
            import onnxruntime as ort

            options = ort.SessionOptions()
            options.intra_op_num_threads = threads
            session = ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])
        self._session = session
        model_input = session.get_inputs()[0]
        self._input_name = model_input.name
        self._size = int(model_input.shape[2])

    def person_score(self, image_path: str | Path) -> float:
        """Highest confidence (0-1) of any person in the image."""
        with Image.open(image_path) as image:
            # Lets the JPEG decoder downscale while decoding, which is much faster on a Pi.
            image.draft("RGB", (self._size, self._size))
            tensor = letterbox(image, self._size)
        # YOLOv8/11 output is (1, 4 + classes, candidates): box, then per-class scores.
        output = self._session.run(None, {self._input_name: tensor})[0]
        return float(output[0, 4 + PERSON_CLASS].max())
