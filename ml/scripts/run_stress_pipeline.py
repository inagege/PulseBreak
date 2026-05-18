#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
import sys

from common import load_config


def run_cmd(cmd: list[str]) -> None:
    print("[pipeline]", " ".join(cmd))
    subprocess.run(cmd, check=True)


def run() -> None:
    parser = argparse.ArgumentParser(description="Run the full Bemotion stress model pipeline.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    parser.add_argument("--skip-download", action="store_true")
    args = parser.parse_args()

    py = sys.executable
    cfg = load_config(args.config)
    model_family = str(cfg.get("training", {}).get("model_family", "tf_mlp"))
    train_script = "ml/scripts/train_stress_classifier.py"
    if model_family == "logistic_regression":
        train_script = "ml/scripts/train_logistic_exportable.py"

    if not args.skip_download:
        run_cmd([py, "ml/scripts/download_bemotion.py"])

    run_cmd([py, "ml/scripts/prepare_bemotion.py", "--config", args.config])
    run_cmd([py, train_script, "--config", args.config])
    run_cmd([py, "ml/scripts/export_tflite.py", "--config", args.config])
    run_cmd([py, "ml/scripts/evaluate_model.py", "--config", args.config, "--scope", "test"])
    run_cmd([py, "ml/scripts/package_app_ml_assets.py", "--config", args.config])

    print("[pipeline] Done")


if __name__ == "__main__":
    run()
