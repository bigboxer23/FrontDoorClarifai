from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

from frontdoor.detector import PersonDetector, letterbox

MODEL = Path(__file__).parent.parent / "models" / "yolo11n.onnx"


class FakeSession:
    def __init__(self, person_scores):
        self.person_scores = person_scores
        self.inputs = []

    def get_inputs(self):
        return [SimpleNamespace(name="images", shape=[1, 3, 320, 320])]

    def run(self, outputs, feed):
        self.inputs.append(feed["images"])
        output = np.zeros((1, 84, len(self.person_scores)), dtype=np.float32)
        output[0, 4] = self.person_scores
        output[0, 5] = 0.99  # a confident bicycle shouldn't count
        return [output]


def test_letterbox_pads_to_square():
    tensor = letterbox(Image.new("RGB", (640, 480), (255, 0, 0)), 320)
    assert tensor.shape == (1, 3, 320, 320)
    assert tensor.dtype == np.float32
    # 640x480 scales to 320x240, leaving 40px gray bars top and bottom
    assert tensor[0, 0, 0, 0] == pytest.approx(114 / 255)
    assert tensor[0, :, 160, 160].tolist() == [1.0, 0.0, 0.0]
    assert tensor[0, 0, 319, 0] == pytest.approx(114 / 255)


def test_person_score_is_best_person_candidate(tmp_path):
    image = tmp_path / "frame.jpg"
    Image.new("RGB", (1640, 1232)).save(image)
    session = FakeSession([0.1, 0.72, 0.3])
    assert PersonDetector("unused", session=session).person_score(image) == pytest.approx(0.72)
    assert session.inputs[0].shape == (1, 3, 320, 320)


@pytest.mark.skipif(not MODEL.exists(), reason="run scripts/export-model.sh first")
def test_real_model_scores_blank_image_low(tmp_path):
    image = tmp_path / "blank.jpg"
    Image.new("RGB", (640, 480), (90, 120, 90)).save(image)
    assert PersonDetector(MODEL).person_score(image) < 0.1
