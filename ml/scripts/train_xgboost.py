#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import GroupShuffleSplit, StratifiedGroupKFold

from common import ensure_parent, load_config, write_json
from train_stress_classifier import best_threshold, load_features, make_row_keys, score_from_probs


def make_sample_weight(y: np.ndarray) -> np.ndarray:
    pos = float(np.sum(y == 1))
    neg = float(np.sum(y == 0))
    total = max(pos + neg, 1.0)
    if pos <= 0 or neg <= 0:
        return np.ones_like(y, dtype=np.float32)
    pos_weight = total / (2.0 * pos)
    neg_weight = total / (2.0 * neg)
    return np.where(y == 1, pos_weight, neg_weight).astype(np.float32)


def build_xgboost(cfg: dict, *, random_state: int):
    try:
        from xgboost import XGBClassifier
    except ImportError as exc:
        raise RuntimeError("xgboost is not installed. Install ml/requirements.txt before training this model.") from exc

    return XGBClassifier(
        n_estimators=int(cfg.get("n_estimators", 300)),
        learning_rate=float(cfg.get("learning_rate", 0.03)),
        max_depth=int(cfg.get("max_depth", 4)),
        min_child_weight=float(cfg.get("min_child_weight", 1.0)),
        subsample=float(cfg.get("subsample", 1.0)),
        colsample_bytree=float(cfg.get("colsample_bytree", 1.0)),
        reg_alpha=float(cfg.get("reg_alpha", 0.0)),
        reg_lambda=float(cfg.get("reg_lambda", 1.0)),
        gamma=float(cfg.get("gamma", 0.0)),
        objective="binary:logistic",
        eval_metric="logloss",
        random_state=random_state,
        n_jobs=1,
    )


def run() -> None:
    parser = argparse.ArgumentParser(description="Train an XGBoost stress model and evaluate it on the fixed subject holdout.")
    parser.add_argument("--config", default="ml/configs/stress_xgboost.yaml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    ds = cfg["dataset"]
    tr_cfg = cfg.get("training", {})
    exp = cfg["export"]

    prepared_csv = Path(ds["prepared_csv"])
    feature_columns = load_features(Path(ds["feature_columns_json"]))
    df = pd.read_csv(prepared_csv)
    missing = [c for c in feature_columns + ["label", "subject"] if c not in df.columns]
    if missing:
        raise RuntimeError(f"Prepared dataset missing required columns: {missing}")

    x = df[feature_columns].fillna(0.0).to_numpy(dtype=np.float32)
    y = df["label"].astype(np.int32).to_numpy()
    groups = df["subject"].astype(str).to_numpy()

    seed = int(ds.get("random_seed", 42))
    test_size = float(ds.get("test_size", 0.2))
    gss = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    dev_idx, test_idx = next(gss.split(x, y, groups=groups))
    dev_idx = dev_idx.astype(np.int32)
    test_idx = test_idx.astype(np.int32)

    x_dev = x[dev_idx]
    y_dev = y[dev_idx]
    groups_dev = groups[dev_idx]
    x_test = x[test_idx]
    y_test = y[test_idx]

    repeats = int(tr_cfg.get("cv_repeats", 2))
    n_splits = int(tr_cfg.get("cv_n_splits", 4))
    cv_seed = int(tr_cfg.get("cv_random_seed", 2026))
    class_weight_mode = str(tr_cfg.get("class_weight", "balanced"))

    oof_sum = np.zeros(len(dev_idx), dtype=np.float64)
    oof_count = np.zeros(len(dev_idx), dtype=np.int32)
    fold_reports: list[dict[str, object]] = []

    for repeat in range(repeats):
        cv = StratifiedGroupKFold(
            n_splits=n_splits,
            shuffle=True,
            random_state=cv_seed + repeat,
        )
        for fold_idx, (train_rel, val_rel) in enumerate(cv.split(x_dev, y_dev, groups=groups_dev), start=1):
            x_train = x_dev[train_rel]
            y_train = y_dev[train_rel]
            x_val = x_dev[val_rel]
            y_val = y_dev[val_rel]
            sample_weight = make_sample_weight(y_train) if class_weight_mode == "balanced" else None

            clf = build_xgboost(tr_cfg, random_state=seed + repeat * 100 + fold_idx)
            clf.fit(x_train, y_train, sample_weight=sample_weight)
            probs_val = clf.predict_proba(x_val)[:, 1].astype(np.float32)

            oof_sum[val_rel] += probs_val
            oof_count[val_rel] += 1

            threshold = best_threshold(y_val, probs_val)
            fold_reports.append(
                {
                    "repeat": repeat + 1,
                    "fold": fold_idx,
                    "threshold": float(threshold),
                    "metrics": score_from_probs(y_val, probs_val, threshold),
                }
            )

    if np.any(oof_count == 0):
        raise RuntimeError("Some development rows did not receive out-of-fold predictions")

    dev_probs_oof = (oof_sum / oof_count).astype(np.float32)
    threshold = best_threshold(y_dev, dev_probs_oof)
    dev_oof_metrics = score_from_probs(y_dev, dev_probs_oof, threshold)

    sample_weight_dev = make_sample_weight(y_dev) if class_weight_mode == "balanced" else None
    clf_final = build_xgboost(tr_cfg, random_state=seed)
    clf_final.fit(x_dev, y_dev, sample_weight=sample_weight_dev)

    dev_fit_probs = clf_final.predict_proba(x_dev)[:, 1].astype(np.float32)
    test_probs = clf_final.predict_proba(x_test)[:, 1].astype(np.float32)
    dev_fit_metrics = score_from_probs(y_dev, dev_fit_probs, threshold)
    test_metrics = score_from_probs(y_test, test_probs, threshold)

    bundle = {
        "model_type": "xgboost",
        "feature_columns": feature_columns,
        "threshold": float(threshold),
        "model": clf_final,
        "split": {
            "strategy": "group_subject",
            "random_seed": seed,
            "train_indices": dev_idx.astype(int).tolist(),
            "val_indices": [],
            "test_indices": test_idx.astype(int).tolist(),
            "train_row_keys": make_row_keys(df, dev_idx),
            "val_row_keys": [],
            "test_row_keys": make_row_keys(df, test_idx),
        },
        "selected_params": {
            "model_family": "xgboost",
            "n_estimators": int(tr_cfg.get("n_estimators", 300)),
            "learning_rate": float(tr_cfg.get("learning_rate", 0.03)),
            "max_depth": int(tr_cfg.get("max_depth", 4)),
            "min_child_weight": float(tr_cfg.get("min_child_weight", 1.0)),
            "subsample": float(tr_cfg.get("subsample", 1.0)),
            "colsample_bytree": float(tr_cfg.get("colsample_bytree", 1.0)),
            "reg_alpha": float(tr_cfg.get("reg_alpha", 0.0)),
            "reg_lambda": float(tr_cfg.get("reg_lambda", 1.0)),
            "gamma": float(tr_cfg.get("gamma", 0.0)),
            "class_weight": class_weight_mode,
            "cv_n_splits": n_splits,
            "cv_repeats": repeats,
            "cv_random_seed": cv_seed,
        },
        "selection_metric_priority": ["balanced_accuracy", "mcc", "f1"],
        "metrics": {
            "threshold": float(threshold),
            "train": dev_fit_metrics,
            "val": dev_oof_metrics,
            "test": test_metrics,
        },
        "cv_fold_reports": fold_reports,
    }

    bundle_path = Path(exp["model_bundle_path"])
    ensure_parent(bundle_path)
    joblib.dump(bundle, bundle_path)
    write_json(bundle_path.with_suffix(".metrics.json"), bundle["metrics"])

    print(f"[xgboost] Saved bundle: {bundle_path}")
    print(f"[xgboost] Selected params: {bundle['selected_params']}")
    print(f"[xgboost] Dev OOF metrics: {dev_oof_metrics}")
    print(f"[xgboost] Test metrics: {test_metrics}")


if __name__ == "__main__":
    run()
