import json
import urllib.request
from pathlib import Path

import pytest

from frontdoor.config import Config, load_config
from frontdoor.control import PauseState, start_control_server

EXAMPLE = Path(__file__).parent.parent / "config.example.toml"


class Clock:
    now = 0.0

    def __call__(self):
        return self.now


def test_pause_state_counts_down():
    clock = Clock()
    pause = PauseState(clock)
    assert not pause.active and pause.remaining() == 0
    pause.pause(60)
    clock.now = 30.5
    assert pause.active and pause.remaining() == 29
    clock.now = 61
    assert not pause.active and pause.remaining() == 0


def test_control_endpoints():
    pause = PauseState()
    server = start_control_server(0, pause, host="127.0.0.1")
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def call(path, method="GET"):
        with urllib.request.urlopen(urllib.request.Request(base + path, method=method)) as response:
            body = response.read()
            return json.loads(body) if body else None

    try:
        assert call("/isPaused") == 0
        assert call("/pause/120", "POST") in (119, 120)
        assert pause.active
        assert call("/enable", "POST") is None
        assert call("/isPaused") == 0
    finally:
        server.shutdown()


def test_example_config_matches_defaults():
    assert load_config(EXAMPLE) == Config()


def test_config_rejects_typos(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text("[detection]\nthreshhold = 0.7\n")
    with pytest.raises(ValueError, match="threshhold"):
        load_config(path)


def test_config_converts_sizes(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text('[camera]\nstill_size = [1920, 1080]\n[s3]\nbucket = "b"\n')
    config = load_config(path)
    assert config.camera.still_size == (1920, 1080)
    assert config.s3.bucket == "b"
    assert config.detection == Config().detection
