#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import yaml


def make_synthetic_dataset(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(42)
    rows: list[dict[str, float | int | str]] = []
    for subject in ["s1", "s2", "s3"]:
        base = 1_700_000_000_000
        for i in range(1200):
            ts = base + i * 1000
            stressed = 1 if (i // 120) % 2 == 1 else 0
            hr = 72 + (8 if stressed else 0) + rng.normal(0, 2)
            temp = 31.5 + (0.3 if stressed else 0.0) + rng.normal(0, 0.05)
            motion = max(0.0, 0.08 + (0.03 if stressed else 0.0) + rng.normal(0, 0.02))
            rows.append(
                {
                    "timestamp": ts,
                    "subject_id": subject,
                    "heart_rate": hr,
                    "temperature": temp,
                    "motion_magnitude": motion,
                    "stress": stressed,
                }
            )
    pd.DataFrame(rows).to_csv(root / "synthetic_bemotion.csv", index=False)


def run() -> None:
    py = sys.executable
    repo = Path(__file__).resolve().parents[2]

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)

        raw_dir = tmp_path / "raw"
        prepared_csv = tmp_path / "artifacts" / "prepared" / "stress_windows.csv"
        feature_json = tmp_path / "artifacts" / "prepared" / "feature_columns.json"
        bundle_path = tmp_path / "artifacts" / "checkpoints" / "stress_bundle.joblib"
        keras_path = tmp_path / "artifacts" / "export" / "stress_model.keras"
        tflite_path = tmp_path / "artifacts" / "export" / "stress_model.tflite"
        metadata_path = tmp_path / "artifacts" / "export" / "model_metadata.json"
        asset_dir = tmp_path / "app_assets"

        make_synthetic_dataset(raw_dir)

        cfg = {
            "dataset": {
                "raw_dir": str(raw_dir),
                "prepared_csv": str(prepared_csv),
                "feature_columns_json": str(feature_json),
                "test_size": 0.2,
                "validation_size": 0.1,
                "random_seed": 42,
            },
            "windowing": {"window_seconds": 45, "step_seconds": 8},
            "columns": {
                "timestamp": ["timestamp"],
                "subject": ["subject_id"],
                "heart_rate": ["heart_rate"],
                "label": ["stress"],
                "motion": ["motion_magnitude"],
                "temperature": ["temperature"],
                "accel_x": ["acc_x"],
                "accel_y": ["acc_y"],
                "accel_z": ["acc_z"],
            },
            "training": {
                "positive_label_values": [1, "1", "stress", "stressed", "high"],
                "min_windows": 30,
            },
            "export": {
                "model_bundle_path": str(bundle_path),
                "keras_model_path": str(keras_path),
                "tflite_path": str(tflite_path),
                "metadata_path": str(metadata_path),
            },
        }

        cfg_path = tmp_path / "smoke.yaml"
        cfg_path.write_text(yaml.safe_dump(cfg), encoding="utf-8")

        subprocess.run([py, str(repo / "ml/scripts/prepare_bemotion.py"), "--config", str(cfg_path)], check=True)
        subprocess.run([py, str(repo / "ml/scripts/train_stress_classifier.py"), "--config", str(cfg_path)], check=True)
        subprocess.run([py, str(repo / "ml/scripts/export_tflite.py"), "--config", str(cfg_path)], check=True)
        subprocess.run(
            [py, str(repo / "ml/scripts/evaluate_model.py"), "--config", str(cfg_path), "--scope", "test"],
            check=True,
        )
        subprocess.run(
            [
                py,
                str(repo / "ml/scripts/package_app_ml_assets.py"),
                "--config",
                str(cfg_path),
                "--asset-dir",
                str(asset_dir),
            ],
            check=True,
        )

        assert prepared_csv.exists(), "prepared csv missing"
        assert bundle_path.exists(), "model bundle missing"
        assert tflite_path.exists(), "tflite export missing"
        assert metadata_path.exists(), "metadata missing"
        assert bundle_path.with_suffix(".eval.test.json").exists(), "test-scope eval report missing"
        assert (asset_dir / "stress_model.tflite").exists(), "packaged app model missing"
        assert (asset_dir / "model_metadata.json").exists(), "packaged metadata missing"

        print("[smoke] pipeline succeeded")


if __name__ == "__main__":
    run()

