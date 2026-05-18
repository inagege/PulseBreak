#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupShuffleSplit, StratifiedGroupKFold

from common import ensure_parent, load_config, write_json
from train_stress_classifier import best_threshold, load_features, make_row_keys, score_from_probs


def normalize(x_train: np.ndarray, x_other: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    mean = x_train.mean(axis=0)
    std = x_train.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)
    return (x_train - mean) / std, (x_other - mean) / std, mean, std


def make_sample_weight(y: np.ndarray) -> np.ndarray:
    pos = float(np.sum(y == 1))
    neg = float(np.sum(y == 0))
    total = max(pos + neg, 1.0)
    if pos <= 0 or neg <= 0:
        return np.ones_like(y, dtype=np.float32)
    pos_weight = total / (2.0 * pos)
    neg_weight = total / (2.0 * neg)
    return np.where(y == 1, pos_weight, neg_weight).astype(np.float32)


def build_exportable_logistic(input_dim: int, coef: np.ndarray, intercept: np.ndarray) -> tf.keras.Model:
    inp = tf.keras.Input(shape=(input_dim,), name="features")
    out = tf.keras.layers.Dense(1, activation="sigmoid", name="stress_probability")(inp)
    model = tf.keras.Model(inputs=inp, outputs=out)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss=tf.keras.losses.BinaryCrossentropy(),
        metrics=[tf.keras.metrics.AUC(name="auroc"), tf.keras.metrics.AUC(curve="PR", name="pr_auc")],
    )
    model.get_layer("stress_probability").set_weights([coef.reshape(input_dim, 1), intercept.reshape(1)])
    return model


def run() -> None:
    parser = argparse.ArgumentParser(description="Train a deterministic logistic-regression stress model and export it as a Keras/TFLite-compatible linear model.")
    parser.add_argument("--config", default="ml/configs/stress_logistic_exportable.yaml")
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
    all_indices = np.arange(len(df), dtype=np.int32)
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

    penalty = str(tr_cfg.get("penalty", "l2"))
    c_value = float(tr_cfg.get("c", 1.0))
    solver = str(tr_cfg.get("solver", "lbfgs"))
    max_iter = int(tr_cfg.get("max_iter", 5000))
    class_weight_mode = str(tr_cfg.get("class_weight", "balanced"))

    repeats = int(tr_cfg.get("cv_repeats", 2))
    n_splits = int(tr_cfg.get("cv_n_splits", 4))
    cv_seed = int(tr_cfg.get("cv_random_seed", 2026))

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

            x_train_n, x_val_n, _, _ = normalize(x_train, x_val)
            sample_weight = make_sample_weight(y_train) if class_weight_mode == "balanced" else None

            clf = LogisticRegression(
                penalty=penalty,
                C=c_value,
                solver=solver,
                max_iter=max_iter,
                random_state=seed,
            )
            clf.fit(x_train_n, y_train, sample_weight=sample_weight)
            probs_val = clf.predict_proba(x_val_n)[:, 1].astype(np.float32)

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

    x_dev_n, x_test_n, mean, std = normalize(x_dev, x_test)
    sample_weight_dev = make_sample_weight(y_dev) if class_weight_mode == "balanced" else None
    clf_final = LogisticRegression(
        penalty=penalty,
        C=c_value,
        solver=solver,
        max_iter=max_iter,
        random_state=seed,
    )
    clf_final.fit(x_dev_n, y_dev, sample_weight=sample_weight_dev)

    tf.keras.backend.clear_session()
    keras_model = build_exportable_logistic(
        input_dim=len(feature_columns),
        coef=clf_final.coef_.reshape(-1).astype(np.float32),
        intercept=clf_final.intercept_.reshape(-1).astype(np.float32),
    )

    dev_fit_probs = keras_model.predict(x_dev_n, verbose=0).reshape(-1)
    test_probs = keras_model.predict(x_test_n, verbose=0).reshape(-1)
    dev_fit_metrics = score_from_probs(y_dev, dev_fit_probs, threshold)
    test_metrics = score_from_probs(y_test, test_probs, threshold)

    keras_path = Path(exp["keras_model_path"])
    ensure_parent(keras_path)
    keras_model.save(keras_path)

    bundle = {
        "model_type": "tf_mlp",
        "feature_columns": feature_columns,
        "mean": mean.astype(np.float32),
        "std": std.astype(np.float32),
        "threshold": float(threshold),
        "keras_model_path": str(keras_path),
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
            "model_family": "logistic_regression",
            "penalty": penalty,
            "c": c_value,
            "solver": solver,
            "max_iter": max_iter,
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

    print(f"[logistic] Saved Keras model: {keras_path}")
    print(f"[logistic] Saved bundle: {bundle_path}")
    print(f"[logistic] Selected params: {bundle['selected_params']}")
    print(f"[logistic] Dev OOF metrics: {dev_oof_metrics}")
    print(f"[logistic] Test metrics: {test_metrics}")


if __name__ == "__main__":
    run()
