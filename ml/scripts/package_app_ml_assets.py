#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from common import load_config


def run() -> None:
    parser = argparse.ArgumentParser(description="Copy exported model artifacts into app assets.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    parser.add_argument("--asset-dir", default="app/src/main/assets/ml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    exp = cfg["export"]

    tflite_path = Path(exp["tflite_path"])
    metadata_path = Path(exp["metadata_path"])
    if not tflite_path.exists() or not metadata_path.exists():
        raise FileNotFoundError("Expected exported tflite and metadata files are missing")

    asset_dir = Path(args.asset_dir)
    asset_dir.mkdir(parents=True, exist_ok=True)

    dst_model = asset_dir / "stress_model.tflite"
    dst_metadata = asset_dir / "model_metadata.json"

    shutil.copy2(tflite_path, dst_model)
    shutil.copy2(metadata_path, dst_metadata)

    print(f"[package] Copied model to {dst_model}")
    print(f"[package] Copied metadata to {dst_metadata}")


if __name__ == "__main__":
    run()

