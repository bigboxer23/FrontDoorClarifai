"""Command line entry point: `frontdoor run` and `frontdoor analyze`."""

from __future__ import annotations

import argparse
import logging
import shutil
import sys
from pathlib import Path

from .config import DEFAULT_PATH, Config, load_config
from .logstash import LogstashHandler


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="frontdoor", description="Front door camera person detection.")
    parser.add_argument("-c", "--config", type=Path, default=DEFAULT_PATH, help=f"config file (default {DEFAULT_PATH})")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="watch the camera for motion and analyze captured stills")
    analyze = commands.add_parser("analyze", help="print the person score of existing images")
    analyze.add_argument("images", nargs="+", type=Path)
    analyze.add_argument("--model", help="ONNX model path, overriding the config")
    analyze.add_argument(
        "--act",
        action="store_true",
        help="also run the success/failure actions (store, email, notify) on copies of the images",
    )
    args = parser.parse_args(argv)

    if args.command == "analyze" and not args.act and not args.config.exists():
        config = Config()
    else:
        config = load_config(args.config)
    _setup_logging(config)

    if args.command == "run":
        from .service import run

        run(config)
        return 0
    if args.model:
        config.detection.model_path = args.model
    return _analyze(config, args.images, args.act)


def _analyze(config: Config, images: list[Path], act: bool) -> int:
    from .detector import PersonDetector
    from .service import build_pipeline

    if act:
        pipeline = build_pipeline(config)
        capture_dir = Path(config.capture_dir)
        capture_dir.mkdir(parents=True, exist_ok=True)
    else:
        detector = PersonDetector(config.detection.model_path, config.detection.threads)
    for image in images:
        if act:
            # The pipeline moves or deletes what it analyzes, so leave the original alone.
            copy = capture_dir / image.name
            shutil.copyfile(image, copy)
            score = pipeline.analyze(copy)
        else:
            score = detector.person_score(image)
        verdict = "success" if score >= config.detection.threshold else "failure"
        print(f"{image}\tperson {score:.1%}\t{verdict}")
    if act:
        pipeline.flush()
    return 0


def _setup_logging(config: Config) -> None:
    logging.basicConfig(
        level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s [%(threadName)s] %(name)s - %(message)s"
    )
    if config.logstash:
        host, _, port = config.logstash.rpartition(":")
        logging.getLogger().addHandler(LogstashHandler(host, int(port)))


if __name__ == "__main__":
    sys.exit(main())
