#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupShuffleSplit, StratifiedGroupKFold

from common import load_config, write_json
from train_stress_classifier import (
    best_threshold,
    build_model,
    compute_class_weight,
    load_features,
    score_from_probs,
    set_training_seed,
)


def summarize_metric_dicts(rows: list[dict[str, float]]) -> dict[str, dict[str, float]]:
    if not rows:
        return {}

    keys = sorted(rows[0].keys())
    out: dict[str, dict[str, float]] = {}
    for key in keys:
        values = np.asarray([float(row[key]) for row in rows], dtype=np.float64)
        out[key] = {
            "mean": float(values.mean()),
            "std": float(values.std(ddof=0)),
            "min": float(values.min()),
            "max": float(values.max()),
        }
    return out


def make_sklearn_probs(
    family: str,
    cfg: dict[str, Any],
    x_train_n: np.ndarray,
    y_train: np.ndarray,
    x_val_n: np.ndarray,
) -> np.ndarray:
    class_weight = cfg.get("class_weight")
    if class_weight == "balanced":
        cls_w = compute_class_weight(y_train)
        sample_weight = np.where(y_train == 1, cls_w[1], cls_w[0]).astype(np.float32)
    else:
        sample_weight = None

    if family == "logistic_regression":
        penalty = str(cfg.get("penalty", "l2"))
        c_value = float(cfg.get("c", 1.0))
        solver = str(cfg.get("solver", "lbfgs"))
        max_iter = int(cfg.get("max_iter", 5000))
        clf = LogisticRegression(
            C=c_value,
            penalty=penalty,
            solver=solver,
            max_iter=max_iter,
            class_weight=None,
            random_state=int(cfg.get("random_state", 0)),
        )
        clf.fit(x_train_n, y_train, sample_weight=sample_weight)
        return clf.predict_proba(x_val_n)[:, 1].astype(np.float32)

    if family == "hist_gradient_boosting":
        clf = HistGradientBoostingClassifier(
            learning_rate=float(cfg.get("learning_rate", 0.05)),
            max_depth=None if cfg.get("max_depth") is None else int(cfg["max_depth"]),
            max_iter=int(cfg.get("max_iter", 200)),
            min_samples_leaf=int(cfg.get("min_samples_leaf", 20)),
            l2_regularization=float(cfg.get("l2_regularization", 0.0)),
            early_stopping=bool(cfg.get("early_stopping", False)),
            random_state=int(cfg.get("random_state", 0)),
        )
        clf.fit(x_train_n, y_train, sample_weight=sample_weight)
        return clf.predict_proba(x_val_n)[:, 1].astype(np.float32)

    if family == "lightgbm":
        try:
            from lightgbm import LGBMClassifier
        except ImportError as exc:
            raise RuntimeError("lightgbm is not installed. Install ml/requirements.txt before running LightGBM comparisons.") from exc

        clf = LGBMClassifier(
            n_estimators=int(cfg.get("n_estimators", 300)),
            learning_rate=float(cfg.get("learning_rate", 0.03)),
            num_leaves=int(cfg.get("num_leaves", 15)),
            max_depth=int(cfg.get("max_depth", -1)),
            min_child_samples=int(cfg.get("min_child_samples", 20)),
            subsample=float(cfg.get("subsample", 1.0)),
            colsample_bytree=float(cfg.get("colsample_bytree", 1.0)),
            reg_alpha=float(cfg.get("reg_alpha", 0.0)),
            reg_lambda=float(cfg.get("reg_lambda", 0.0)),
            random_state=int(cfg.get("random_state", 0)),
            verbose=-1,
        )
        clf.fit(x_train_n, y_train, sample_weight=sample_weight)
        return clf.predict_proba(x_val_n)[:, 1].astype(np.float32)

    if family == "xgboost":
        try:
            from xgboost import XGBClassifier
        except ImportError as exc:
            raise RuntimeError("xgboost is not installed. Install ml/requirements.txt before running XGBoost comparisons.") from exc

        clf = XGBClassifier(
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
            random_state=int(cfg.get("random_state", 0)),
            n_jobs=1,
        )
        clf.fit(x_train_n, y_train, sample_weight=sample_weight)
        return clf.predict_proba(x_val_n)[:, 1].astype(np.float32)

    raise RuntimeError(f"Unsupported sklearn model family: {family}")


def make_tf_probs(
    cfg: dict[str, Any],
    x_train_n: np.ndarray,
    y_train: np.ndarray,
    x_val_n: np.ndarray,
    y_val: np.ndarray,
) -> np.ndarray:
    seeds = [int(v) for v in cfg.get("seeds", [42])]
    probs_accum: list[np.ndarray] = []

    for seed in seeds:
        tf.keras.backend.clear_session()
        set_training_seed(seed)
        model = build_model(
            input_dim=x_train_n.shape[1],
            hidden_units=[int(v) for v in cfg.get("hidden_units", [])],
            dropout=float(cfg.get("dropout", 0.0)),
            learning_rate=float(cfg.get("learning_rate", 1e-3)),
            activation=str(cfg.get("activation", "relu")),
            batch_norm=bool(cfg.get("batch_norm", False)),
            l2_kernel=float(cfg.get("l2_kernel", 0.0)),
            use_focal=bool(cfg.get("use_focal", False)),
            focal_gamma=float(cfg.get("focal_gamma", 2.0)),
            focal_alpha=float(cfg.get("focal_alpha", 0.25)),
        )

        cls_w = compute_class_weight(y_train)
        sample_weight = np.where(y_train == 1, cls_w[1], cls_w[0]).astype(np.float32)
        callbacks = [
            tf.keras.callbacks.EarlyStopping(
                monitor="val_pr_auc",
                mode="max",
                patience=int(cfg.get("patience", 4)),
                restore_best_weights=True,
            )
        ]

        model.fit(
            x_train_n,
            y_train,
            sample_weight=sample_weight,
            validation_data=(x_val_n, y_val),
            epochs=int(cfg.get("epochs", 24)),
            batch_size=int(cfg.get("batch_size", 256)),
            callbacks=callbacks,
            verbose=0,
        )
        probs_accum.append(model.predict(x_val_n, verbose=0).reshape(-1).astype(np.float32))

    return np.mean(np.stack(probs_accum, axis=0), axis=0)


def build_dev_split(
    x: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    *,
    test_size: float,
    seed: int,
) -> tuple[np.ndarray, np.ndarray]:
    splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    dev_idx, test_idx = next(splitter.split(x, y, groups=groups))
    return dev_idx.astype(np.int32), test_idx.astype(np.int32)


def normalize_from_train(x_train: np.ndarray, x_val: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    mean = x_train.mean(axis=0)
    std = x_train.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)
    return (x_train - mean) / std, (x_val - mean) / std


def run() -> None:
    parser = argparse.ArgumentParser(description="Compare stress models with repeated subject-wise cross-validation on non-test subjects.")
    parser.add_argument("--config", default="ml/configs/stress_model_comparison.yaml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    base_cfg = load_config(str(cfg["base_config"]))

    ds = base_cfg["dataset"]
    comparison = cfg["comparison"]
    output_json = Path(comparison["output_json"])
    selection_priority = [str(v) for v in comparison.get("selection_metric_priority", ["auroc", "balanced_accuracy", "mcc", "pr_auc"])]

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
    dev_idx, test_idx = build_dev_split(
        x,
        y,
        groups,
        test_size=float(ds.get("test_size", 0.2)),
        seed=seed,
    )
    x_dev = x[dev_idx]
    y_dev = y[dev_idx]
    groups_dev = groups[dev_idx]

    n_splits = int(comparison.get("n_splits", 4))
    repeats = int(comparison.get("repeats", 2))
    cv_seed = int(comparison.get("random_seed", 2026))
    models_cfg = comparison.get("models", [])
    if not isinstance(models_cfg, list) or not models_cfg:
        raise RuntimeError("comparison.models must contain at least one model")

    model_results: list[dict[str, Any]] = []
    for model_cfg in models_cfg:
        family = str(model_cfg["family"])
        name = str(model_cfg["name"])
        folds: list[dict[str, Any]] = []

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
                x_train_n, x_val_n = normalize_from_train(x_train, x_val)

                if family == "tf_mlp":
                    probs_val = make_tf_probs(model_cfg, x_train_n, y_train, x_val_n, y_val)
                else:
                    probs_val = make_sklearn_probs(family, model_cfg, x_train_n, y_train, x_val_n)

                threshold = best_threshold(y_val, probs_val)
                metrics = score_from_probs(y_val, probs_val, threshold)
                folds.append(
                    {
                        "repeat": repeat + 1,
                        "fold": fold_idx,
                        "threshold": float(threshold),
                        "samples": int(len(y_val)),
                        "subjects": int(len(np.unique(groups_dev[val_rel]))),
                        "metrics": metrics,
                    }
                )

        fold_metrics = [row["metrics"] for row in folds]
        summary = summarize_metric_dicts(fold_metrics)
        model_results.append(
            {
                "name": name,
                "family": family,
                "config": model_cfg,
                "folds": folds,
                "summary": summary,
            }
        )

    leaderboard = sorted(
        [
            {
                "name": row["name"],
                "family": row["family"],
                "score_tuple": [row["summary"][metric]["mean"] for metric in selection_priority],
                "summary": row["summary"],
            }
            for row in model_results
        ],
        key=lambda item: tuple(float(v) for v in item["score_tuple"]),
        reverse=True,
    )

    payload = {
        "base_config": str(cfg["base_config"]),
        "prepared_csv": str(prepared_csv),
        "feature_columns": feature_columns,
        "selection_metric_priority": selection_priority,
        "holdout_split": {
            "strategy": "group_subject",
            "random_seed": seed,
            "dev_rows": int(len(dev_idx)),
            "test_rows": int(len(test_idx)),
            "dev_subjects": sorted(np.unique(groups[dev_idx]).tolist()),
            "test_subjects": sorted(np.unique(groups[test_idx]).tolist()),
        },
        "comparison": {
            "n_splits": n_splits,
            "repeats": repeats,
            "random_seed": cv_seed,
        },
        "leaderboard": leaderboard,
        "models": model_results,
    }

    write_json(output_json, payload)
    print(f"[compare] Wrote report to {output_json}")
    if leaderboard:
        top = leaderboard[0]
        print(f"[compare] Best model: {top['name']} ({top['family']})")
        print(f"[compare] Score tuple: {top['score_tuple']}")


if __name__ == "__main__":
    run()
