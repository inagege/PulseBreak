#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from common import ensure_parent, write_json
from evaluate_model import build_row_keys, predict_bundle_probs
from train_stress_classifier import best_threshold, score_from_probs


def run() -> None:
    parser = argparse.ArgumentParser(description="Build a weighted TensorFlow ensemble bundle from trained member bundles.")
    parser.add_argument("--prepared-csv", required=True)
    parser.add_argument("--output-bundle", required=True)
    parser.add_argument("--member", action="append", nargs=3, metavar=("NAME", "BUNDLE_PATH", "WEIGHT"), required=True)
    args = parser.parse_args()

    prepared_csv = Path(args.prepared_csv)
    if not prepared_csv.exists():
        raise FileNotFoundError(f"Prepared dataset not found: {prepared_csv}")
    df = pd.read_csv(prepared_csv)
    row_keys = build_row_keys(df)

    member_specs: list[dict[str, object]] = []
    member_probs_all: list[np.ndarray] = []
    split = None
    feature_columns = None

    for name, bundle_path_str, weight_str in args.member:
        bundle_path = Path(bundle_path_str)
        if not bundle_path.exists():
            raise FileNotFoundError(f"Member bundle not found: {bundle_path}")
        bundle = joblib.load(bundle_path)
        if bundle.get("model_type") != "tf_mlp":
            raise RuntimeError(f"Only tf_mlp bundles are supported as ensemble members: {bundle_path}")

        probs, member_features = predict_bundle_probs(bundle, df)
        member_probs_all.append(probs.astype(np.float32))

        if feature_columns is None:
            feature_columns = list(member_features)
        elif list(member_features) != feature_columns:
            raise RuntimeError("All ensemble members must use identical feature columns")

        if split is None:
            split = bundle.get("split")

        member_specs.append(
            {
                "name": name,
                "source_bundle": str(bundle_path),
                "keras_model_path": str(bundle["keras_model_path"]),
                "feature_columns": list(bundle["feature_columns"]),
                "mean": np.asarray(bundle["mean"], dtype=np.float32),
                "std": np.asarray(bundle["std"], dtype=np.float32),
                "weight": float(weight_str),
                "selected_params": bundle.get("selected_params", {}),
            }
        )

    if split is None or feature_columns is None:
        raise RuntimeError("No valid ensemble members were provided")

    total_weight = float(sum(float(m["weight"]) for m in member_specs))
    if total_weight <= 0:
        raise RuntimeError("Ensemble weights must sum to a positive value")

    ensemble_probs = np.zeros(len(df), dtype=np.float32)
    for probs, spec in zip(member_probs_all, member_specs):
        ensemble_probs += probs * (float(spec["weight"]) / total_weight)

    y = df["label"].astype(np.int32).to_numpy()
    key_to_idx = {str(k): i for i, k in enumerate(row_keys.tolist())}

    def indices_for(split_key: str) -> np.ndarray:
        split_keys = split.get(split_key, [])
        if isinstance(split_keys, list) and split_keys:
            idx = [key_to_idx[str(k)] for k in split_keys]
            return np.asarray(idx, dtype=np.int32)
        split_idx = split.get(split_key.replace("_row_keys", "_indices"), [])
        return np.asarray(split_idx, dtype=np.int32)

    train_idx = indices_for("train_row_keys")
    val_idx = indices_for("val_row_keys")
    test_idx = indices_for("test_row_keys")

    threshold = best_threshold(y[val_idx], ensemble_probs[val_idx])
    metrics = {
        "threshold": float(threshold),
        "train": score_from_probs(y[train_idx], ensemble_probs[train_idx], threshold),
        "val": score_from_probs(y[val_idx], ensemble_probs[val_idx], threshold),
        "test": score_from_probs(y[test_idx], ensemble_probs[test_idx], threshold),
    }

    out_bundle = {
        "model_type": "tf_ensemble_avg",
        "feature_columns": feature_columns,
        "threshold": float(threshold),
        "split": split,
        "members": member_specs,
        "selected_params": {
            "ensemble_members": [
                {
                    "name": str(m["name"]),
                    "weight": float(m["weight"]),
                    "source_bundle": str(m["source_bundle"]),
                }
                for m in member_specs
            ],
            "threshold_selection": "validation_best_threshold",
        },
        "selection_metric_priority": ["balanced_accuracy", "mcc", "f1"],
        "metrics": metrics,
    }

    output_bundle = Path(args.output_bundle)
    ensure_parent(output_bundle)
    joblib.dump(out_bundle, output_bundle)
    write_json(output_bundle.with_suffix(".metrics.json"), metrics)

    print(f"[ensemble] Saved bundle: {output_bundle}")
    print(f"[ensemble] Validation threshold: {threshold:.3f}")
    print(f"[ensemble] Test metrics: {metrics['test']}")


if __name__ == "__main__":
    run()
