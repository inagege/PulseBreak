#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score, roc_auc_score

from common import load_config, write_json


def build_row_keys(df: pd.DataFrame) -> np.ndarray:
    if {"subject", "window_start_ms", "window_end_ms"}.issubset(df.columns):
        s = df["subject"].astype(str)
        a = df["window_start_ms"].astype(str)
        b = df["window_end_ms"].astype(str)
        return (s + "|" + a + "|" + b).to_numpy(dtype=str)
    return np.array([f"row:{i}" for i in range(len(df))], dtype=str)


def predict_bundle_probs(bundle: dict, df: pd.DataFrame) -> tuple[np.ndarray, list[str]]:
    model_type = bundle.get("model_type")

    if model_type == "tf_mlp":
        feature_columns = bundle["feature_columns"]
        mean = np.asarray(bundle["mean"], dtype=np.float32)
        std = np.asarray(bundle["std"], dtype=np.float32)

        model_path = Path(bundle["keras_model_path"])
        if not model_path.exists():
            raise FileNotFoundError(f"Trained keras model not found: {model_path}")

        model = tf.keras.models.load_model(model_path, compile=False)
        x = df[feature_columns].fillna(0.0).to_numpy(dtype=np.float32)
        x_n = (x - mean) / std
        probs = model.predict(x_n, verbose=0).reshape(-1)
        return probs, feature_columns

    if model_type == "tf_ensemble_avg":
        members = bundle.get("members")
        if not isinstance(members, list) or not members:
            raise RuntimeError("Ensemble bundle has no members")

        total_weight = float(sum(float(member.get("weight", 1.0)) for member in members))
        if total_weight <= 0:
            raise RuntimeError("Ensemble member weights must sum to a positive value")

        combined: np.ndarray | None = None
        feature_columns = [str(v) for v in members[0]["feature_columns"]]
        for idx, member in enumerate(members):
            member_features = [str(v) for v in member["feature_columns"]]
            if member_features != feature_columns:
                raise RuntimeError(f"Ensemble member {idx} uses mismatched feature columns")

            model_path = Path(member["keras_model_path"])
            if not model_path.exists():
                raise FileNotFoundError(f"Ensemble member keras model not found: {model_path}")

            model = tf.keras.models.load_model(model_path, compile=False)
            mean = np.asarray(member["mean"], dtype=np.float32)
            std = np.asarray(member["std"], dtype=np.float32)
            x = df[member_features].fillna(0.0).to_numpy(dtype=np.float32)
            x_n = (x - mean) / std
            probs = model.predict(x_n, verbose=0).reshape(-1)
            weight = float(member.get("weight", 1.0)) / total_weight
            combined = probs * weight if combined is None else combined + (probs * weight)

        if combined is None:
            raise RuntimeError("Ensemble prediction failed to produce outputs")
        return combined, feature_columns

    raise RuntimeError(f"Unsupported model bundle type: {model_type}")


def run() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the trained stress classifier on prepared data.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    parser.add_argument("--scope", choices=["test", "all"], default="test")
    args = parser.parse_args()

    cfg = load_config(args.config)
    ds = cfg["dataset"]
    exp = cfg["export"]

    prepared_csv = Path(ds["prepared_csv"])
    bundle_path = Path(exp["model_bundle_path"])
    if not prepared_csv.exists() or not bundle_path.exists():
        raise FileNotFoundError("Prepared dataset or model bundle is missing")

    bundle = joblib.load(bundle_path)
    threshold = float(bundle.get("threshold", 0.5))

    df = pd.read_csv(prepared_csv)
    split = bundle.get("split")
    if args.scope == "test":
        if not isinstance(split, dict):
            raise RuntimeError("Missing split metadata in model bundle. Re-run training to evaluate test-only.")

        test_keys = split.get("test_row_keys")
        if isinstance(test_keys, list) and len(test_keys) > 0:
            keys = build_row_keys(df)
            test_key_set = {str(v) for v in test_keys}
            mask = np.array([k in test_key_set for k in keys], dtype=bool)
            df = df.loc[mask].copy()
        else:
            test_indices = split.get("test_indices")
            if not isinstance(test_indices, list) or len(test_indices) == 0:
                raise RuntimeError("Bundle split metadata has no test membership.")
            df = df.iloc[np.array(test_indices, dtype=int)].copy()

    if df.empty:
        raise RuntimeError(f"No samples selected for scope='{args.scope}'.")

    y = df["label"].astype(np.int32).to_numpy()
    probs, feature_columns = predict_bundle_probs(bundle, df)
    preds = (probs >= threshold).astype(np.int32)
    tp = int(np.sum((y == 1) & (preds == 1)))
    tn = int(np.sum((y == 0) & (preds == 0)))
    fp = int(np.sum((y == 0) & (preds == 1)))
    fn = int(np.sum((y == 1) & (preds == 0)))
    specificity = float(tn / (tn + fp)) if (tn + fp) > 0 else 0.0
    recall = float(recall_score(y, preds, zero_division=0))
    balanced_accuracy = (recall + specificity) / 2.0
    mcc_den = np.sqrt(max((tp + fp) * (tp + fn) * (tn + fp) * (tn + fn), 0))
    mcc = float(((tp * tn) - (fp * fn)) / mcc_den) if mcc_den > 0 else 0.0

    report = {
        "scope": args.scope,
        "threshold": threshold,
        "auroc": float(roc_auc_score(y, probs)) if len(np.unique(y)) > 1 else 0.5,
        "pr_auc": float(average_precision_score(y, probs)) if len(np.unique(y)) > 1 else float(np.mean(y)),
        "f1": float(f1_score(y, preds, zero_division=0)),
        "precision": float(precision_score(y, preds, zero_division=0)),
        "recall": recall,
        "specificity": specificity,
        "balanced_accuracy": balanced_accuracy,
        "mcc": mcc,
        "samples": int(len(y)),
        "positives": int(np.sum(y)),
    }
    if isinstance(split, dict):
        report["split_strategy"] = split.get("strategy")
        report["split_seed"] = split.get("random_seed")

    out_path = bundle_path.with_suffix(f".eval.{args.scope}.json")
    write_json(out_path, report)

    # Keep compatibility with existing tooling by updating the legacy path as well.
    write_json(bundle_path.with_suffix(".eval.json"), report)

    print(f"[evaluate] Report written to {out_path}")
    print(f"[evaluate] {report}")


if __name__ == "__main__":
    run()
