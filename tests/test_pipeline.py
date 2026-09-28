from pathlib import Path

import pytest

from frontdoor.pipeline import Pipeline


class FakeDetector:
    def __init__(self):
        self.scores = {}

    def person_score(self, path):
        return self.scores[path.name]


class FakeActions:
    def __init__(self):
        self.calls = []

    def store(self, path, folder):
        self.calls.append(("store", path.name, folder))
        return f"{folder}/{path.name}"

    def notify(self):
        self.calls.append(("notify",))

    def after_stored(self, key):
        self.calls.append(("after_stored", key))

    def email(self, files):
        self.calls.append(("email", [f.name for f in files]))


class FakeTimer:
    def __init__(self, seconds, callback):
        self.seconds = seconds
        self.callback = callback
        self.cancelled = False
        self.started = False

    def start(self):
        self.started = True

    def cancel(self):
        self.cancelled = True


class Clock:
    now = 1000.0

    def __call__(self):
        return self.now


@pytest.fixture
def setup():
    detector, actions, clock, timers = FakeDetector(), FakeActions(), Clock(), []

    def timer_factory(seconds, callback):
        timers.append(FakeTimer(seconds, callback))
        return timers[-1]

    pipeline = Pipeline(
        detector, actions, 0.5, batch_minutes=5, quiet_seconds=60, clock=clock, timer_factory=timer_factory
    )

    def analyze(name, score):
        detector.scores[name] = score
        return pipeline.analyze(Path(name))

    return pipeline, analyze, actions, clock, timers


def test_failure_is_stored_in_failure_folder(setup):
    _, analyze, actions, _, _ = setup
    assert analyze("a.jpg", 0.2) == 0.2
    assert actions.calls == [("store", "a.jpg", "Failure")]


def test_first_success_notifies_immediately(setup):
    _, analyze, actions, _, timers = setup
    analyze("a.jpg", 0.9)
    assert actions.calls == [
        ("notify",),
        ("email", ["a.jpg"]),
        ("store", "a.jpg", "Success"),
        ("after_stored", "Success/a.jpg"),
    ]
    assert timers == []


def test_successes_within_window_are_batched(setup):
    _, analyze, actions, clock, timers = setup
    analyze("a.jpg", 0.9)
    actions.calls.clear()

    clock.now += 90
    analyze("b.jpg", 0.9)
    clock.now += 60
    analyze("c.jpg", 0.8)
    assert actions.calls == []
    assert timers[0].cancelled and not timers[1].cancelled
    assert timers[1].seconds == 300

    timers[1].callback()
    assert actions.calls == [
        ("email", ["b.jpg", "c.jpg"]),
        ("store", "b.jpg", "Success"),
        ("store", "c.jpg", "Success"),
    ]


def test_success_after_quiet_window_notifies_again(setup):
    _, analyze, actions, clock, _ = setup
    analyze("a.jpg", 0.9)
    clock.now += 301
    actions.calls.clear()
    analyze("b.jpg", 0.9)
    assert actions.calls[0] == ("notify",)


def test_flush_sends_pending_batch(setup):
    pipeline, analyze, actions, clock, timers = setup
    analyze("a.jpg", 0.9)
    clock.now += 90
    analyze("b.jpg", 0.9)
    actions.calls.clear()
    pipeline.flush()
    assert timers[0].cancelled
    assert actions.calls == [("email", ["b.jpg"]), ("store", "b.jpg", "Success")]
    pipeline.flush()
    assert len(actions.calls) == 2


def test_success_in_quiet_window_is_stored_without_email(setup):
    _, analyze, actions, clock, timers = setup
    analyze("a.jpg", 0.9)
    actions.calls.clear()
    clock.now += 59
    analyze("b.jpg", 0.9)
    assert actions.calls == [("store", "b.jpg", "Success")]
    assert timers == []


def test_failure_in_quiet_window_still_stored_as_failure(setup):
    _, analyze, actions, clock, _ = setup
    analyze("a.jpg", 0.9)
    actions.calls.clear()
    clock.now += 5
    analyze("b.jpg", 0.1)
    assert actions.calls == [("store", "b.jpg", "Failure")]


def test_quiet_window_successes_do_not_delay_next_immediate_email(setup):
    _, analyze, actions, clock, _ = setup
    analyze("a.jpg", 0.9)
    clock.now += 50
    analyze("b.jpg", 0.9)  # quiet, not counted as activity
    clock.now += 260  # 310s after a.jpg, only 260s after b.jpg
    actions.calls.clear()
    analyze("c.jpg", 0.9)
    assert actions.calls[0] == ("notify",)


def test_real_visit_sends_one_email(setup):
    """17:43:27 success, 17:43:32 success, 17:43:37 failure from the Pi's log: previously two emails."""
    _, analyze, actions, clock, timers = setup
    analyze("174327.jpg", 0.82)
    clock.now += 5
    analyze("174332.jpg", 0.63)
    clock.now += 5
    analyze("174337.jpg", 0.02)
    assert [c for c in actions.calls if c[0] == "email"] == [("email", ["174327.jpg"])]
    assert ("store", "174332.jpg", "Success") in actions.calls
    assert timers == []
