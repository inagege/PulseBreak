#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit, train_test_split

from common import ensure_parent, load_config, write_json

DEFAULT_FEATURES = [
    "hr_mean",
    "hr_median",
    "hr_std",
    "hr_min",
    "hr_max",
    "hr_range",
    "hr_iqr",
    "hr_cv",
    "hr_slope",
    "hr_delta_abs",
    "rmssd",
    "pnn50",
    "ibi_mean_ms",
    "ibi_std_ms",
    "motion_mean",
    "motion_median",
    "motion_std",
    "motion_p90",
    "motion_active_fraction",
    "motion_rest_fraction",
    "hr_motion_corr",
    "hr_motion_ratio",
    "temp_mean",
    "temp_median",
    "temp_spread",
]


def load_features(path: Path) -> list[str]:
    if not path.exists():
        return DEFAULT_FEATURES
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    features = data.get("feature_columns")
    if isinstance(features, list) and features:
        return [str(v) for v in features]
    return DEFAULT_FEATURES


def score_from_probs(y_true: np.ndarray, probs: np.ndarray, threshold: float) -> dict[str, float]:
    preds = (probs >= threshold).astype(np.int32)
    tp = int(np.sum((y_true == 1) & (preds == 1)))
    tn = int(np.sum((y_true == 0) & (preds == 0)))
    fp = int(np.sum((y_true == 0) & (preds == 1)))
    fn = int(np.sum((y_true == 1) & (preds == 0)))

    specificity = float(tn / (tn + fp)) if (tn + fp) > 0 else 0.0
    recall = float(recall_score(y_true, preds, zero_division=0))
    balanced_accuracy = (recall + specificity) / 2.0
    mcc_den = np.sqrt(max((tp + fp) * (tp + fn) * (tn + fp) * (tn + fn), 0))
    mcc = float(((tp * tn) - (fp * fn)) / mcc_den) if mcc_den > 0 else 0.0

    return {
        "auroc": float(roc_auc_score(y_true, probs)) if len(np.unique(y_true)) > 1 else 0.5,
        "pr_auc": float(average_precision_score(y_true, probs)) if len(np.unique(y_true)) > 1 else float(np.mean(y_true)),
        "f1": float(f1_score(y_true, preds, zero_division=0)),
        "precision": float(precision_score(y_true, preds, zero_division=0)),
        "recall": recall,
        "specificity": specificity,
        "balanced_accuracy": balanced_accuracy,
        "mcc": mcc,
        "positives": int(np.sum(y_true)),
        "samples": int(len(y_true)),
    }


def best_threshold(y_true: np.ndarray, probs: np.ndarray) -> float:
    if len(np.unique(y_true)) <= 1:
        return 0.5
    candidates = np.linspace(0.2, 0.8, 61)
    best_t = 0.5
    best_tuple = (-1.0, -1.0, -1.0)
    for t in candidates:
        metrics = score_from_probs(y_true, probs, float(t))
        current = (
            float(metrics["balanced_accuracy"]),
            float(metrics["mcc"]),
            float(metrics["f1"]),
        )
        if current > best_tuple:
            best_tuple = current
            best_t = float(t)
    return best_t


def metric_priority_tuple(metrics: dict[str, float], priority: list[str]) -> tuple[float, ...]:
    return tuple(float(metrics.get(name, float("-inf"))) for name in priority)


def compute_class_weight(y: np.ndarray) -> dict[int, float]:
    pos = float(np.sum(y == 1))
    neg = float(np.sum(y == 0))
    total = max(pos + neg, 1.0)
    if pos <= 0 or neg <= 0:
        return {0: 1.0, 1: 1.0}
    return {
        0: total / (2.0 * neg),
        1: total / (2.0 * pos),
    }


def make_loss(use_focal: bool, focal_gamma: float, focal_alpha: float) -> tf.keras.losses.Loss:
    if use_focal:
        return tf.keras.losses.BinaryFocalCrossentropy(
            gamma=focal_gamma,
            alpha=focal_alpha,
            from_logits=False,
        )
    return tf.keras.losses.BinaryCrossentropy()


def set_training_seed(seed: int) -> None:
    np.random.seed(seed)
    tf.keras.utils.set_random_seed(seed)
    try:
        tf.config.experimental.enable_op_determinism()
    except Exception:
        pass


def build_model(
    input_dim: int,
    hidden_units: list[int],
    dropout: float,
    learning_rate: float,
    activation: str,
    batch_norm: bool,
    l2_kernel: float,
    use_focal: bool,
    focal_gamma: float,
    focal_alpha: float,
) -> tf.keras.Model:
    inp = tf.keras.Input(shape=(input_dim,), name="features")
    x = inp
    kernel_regularizer = tf.keras.regularizers.l2(l2_kernel) if l2_kernel > 0 else None
    for i, units in enumerate(hidden_units):
        x = tf.keras.layers.Dense(
            units,
            activation=None if batch_norm else activation,
            kernel_regularizer=kernel_regularizer,
            name=f"dense_{i}",
        )(x)
        if batch_norm:
            x = tf.keras.layers.BatchNormalization(name=f"bn_{i}")(x)
            x = tf.keras.layers.Activation(activation, name=f"act_{i}")(x)
        if dropout > 0:
            x = tf.keras.layers.Dropout(dropout, name=f"dropout_{i}")(x)
    out = tf.keras.layers.Dense(
        1,
        activation="sigmoid",
        kernel_regularizer=kernel_regularizer,
        name="stress_probability",
    )(x)
    model = tf.keras.Model(inputs=inp, outputs=out)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=learning_rate),
        loss=make_loss(use_focal=use_focal, focal_gamma=focal_gamma, focal_alpha=focal_alpha),
        metrics=[tf.keras.metrics.AUC(name="auroc"), tf.keras.metrics.AUC(curve="PR", name="pr_auc")],
    )
    return model


def train_candidate(
    *,
    feature_dim: int,
    hidden_units: list[int],
    dropout: float,
    learning_rate: float,
    activation: str,
    batch_norm: bool,
    l2_kernel: float,
    use_focal: bool,
    focal_gamma: float,
    focal_alpha: float,
    epochs: int,
    batch_size: int,
    patience: int,
    seed: int,
    x_train_n: np.ndarray,
    y_train: np.ndarray,
    x_val_n: np.ndarray,
    y_val: np.ndarray,
    x_test_n: np.ndarray,
    y_test: np.ndarray,
) -> dict[str, object]:
    tf.keras.backend.clear_session()
    set_training_seed(seed)

    model = build_model(
        input_dim=feature_dim,
        hidden_units=hidden_units,
        dropout=dropout,
        learning_rate=learning_rate,
        activation=activation,
        batch_norm=batch_norm,
        l2_kernel=l2_kernel,
        use_focal=use_focal,
        focal_gamma=focal_gamma,
        focal_alpha=focal_alpha,
    )

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_pr_auc",
            mode="max",
            patience=patience,
            restore_best_weights=True,
        )
    ]

    cls_w = compute_class_weight(y_train)
    sample_weight = np.where(y_train == 1, cls_w[1], cls_w[0]).astype(np.float32)

    model.fit(
        x_train_n,
        y_train,
        sample_weight=sample_weight,
        validation_data=(x_val_n, y_val),
        epochs=epochs,
        batch_size=batch_size,
        callbacks=callbacks,
        verbose=0,
    )

    train_probs = model.predict(x_train_n, verbose=0).reshape(-1)
    val_probs = model.predict(x_val_n, verbose=0).reshape(-1)
    test_probs = model.predict(x_test_n, verbose=0).reshape(-1)
    threshold = best_threshold(y_val, val_probs)

    metrics = {
        "threshold": threshold,
        "train": score_from_probs(y_train, train_probs, threshold),
        "val": score_from_probs(y_val, val_probs, threshold),
        "test": score_from_probs(y_test, test_probs, threshold),
    }

    return {
        "model": model,
        "metrics": metrics,
        "params": {
            "hidden_units": hidden_units,
            "dropout": dropout,
            "learning_rate": learning_rate,
            "activation": activation,
            "batch_norm": batch_norm,
            "l2_kernel": l2_kernel,
            "use_focal": use_focal,
            "focal_gamma": focal_gamma,
            "focal_alpha": focal_alpha,
            "seed": seed,
        },
    }


def make_row_keys(df: pd.DataFrame, indices: np.ndarray) -> list[str]:
    if {"subject", "window_start_ms", "window_end_ms"}.issubset(df.columns):
        subject = df["subject"].astype(str)
        start = df["window_start_ms"].astype(str)
        end = df["window_end_ms"].astype(str)
        return [f"{subject.iloc[i]}|{start.iloc[i]}|{end.iloc[i]}" for i in indices.tolist()]
    return [f"row:{int(i)}" for i in indices.tolist()]


def run() -> None:
    parser = argparse.ArgumentParser(description="Train a subject-wise TensorFlow stress classifier.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    ds = cfg["dataset"]
    exp = cfg["export"]
    tr_cfg = cfg.get("training", {})
    selection_priority = [str(v) for v in tr_cfg.get("selection_metric_priority", ["auroc", "balanced_accuracy", "mcc", "pr_auc"])]

    prepared_csv = Path(ds["prepared_csv"])
    if not prepared_csv.exists():
        raise FileNotFoundError(f"Prepared data not found: {prepared_csv}")

    df = pd.read_csv(prepared_csv)
    feature_columns = load_features(Path(ds["feature_columns_json"]))
    missing = [c for c in feature_columns + ["label"] if c not in df.columns]
    if missing:
        raise RuntimeError(f"Prepared dataset missing required columns: {missing}")

    x = df[feature_columns].fillna(0.0).to_numpy(dtype=np.float32)
    y = df["label"].astype(np.int32).to_numpy()
    all_indices = np.arange(len(df), dtype=np.int32)

    seed = int(ds.get("random_seed", 42))
    set_training_seed(seed)

    if "subject" in df.columns and df["subject"].nunique() > 2:
        groups = df["subject"].astype(str).to_numpy()
        gss_test = GroupShuffleSplit(n_splits=1, test_size=float(ds.get("test_size", 0.2)), random_state=seed)
        train_val_idx, test_idx = next(gss_test.split(x, y, groups=groups))

        x_train_val = x[train_val_idx]
        y_train_val = y[train_val_idx]
        groups_train_val = groups[train_val_idx]

        val_size = float(ds.get("validation_size", 0.1))
        rel_val = val_size / max(1e-6, (1.0 - float(ds.get("test_size", 0.2))))
        rel_val = min(max(rel_val, 0.05), 0.5)
        gss_val = GroupShuffleSplit(n_splits=1, test_size=rel_val, random_state=seed + 1)
        train_idx_rel, val_idx_rel = next(gss_val.split(x_train_val, y_train_val, groups=groups_train_val))

        train_abs_idx = train_val_idx[train_idx_rel]
        val_abs_idx = train_val_idx[val_idx_rel]
        test_abs_idx = test_idx

        x_train = x_train_val[train_idx_rel]
        y_train = y_train_val[train_idx_rel]
        x_val = x_train_val[val_idx_rel]
        y_val = y_train_val[val_idx_rel]
        x_test = x[test_idx]
        y_test = y[test_idx]
    else:
        x_train_val, x_test, y_train_val, y_test, train_val_abs_idx, test_abs_idx = train_test_split(
            x,
            y,
            all_indices,
            test_size=float(ds.get("test_size", 0.2)),
            random_state=seed,
            stratify=y if len(np.unique(y)) > 1 else None,
        )
        x_train, x_val, y_train, y_val, train_abs_idx, val_abs_idx = train_test_split(
            x_train_val,
            y_train_val,
            train_val_abs_idx,
            test_size=float(ds.get("validation_size", 0.1)) / max(1e-6, (1.0 - float(ds.get("test_size", 0.2)))),
            random_state=seed + 1,
            stratify=y_train_val if len(np.unique(y_train_val)) > 1 else None,
        )

    mean = x_train.mean(axis=0)
    std = x_train.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)

    x_train_n = (x_train - mean) / std
    x_val_n = (x_val - mean) / std
    x_test_n = (x_test - mean) / std

    epochs = int(tr_cfg.get("epochs", 50))
    batch_size = int(tr_cfg.get("batch_size", 256))
    patience = int(tr_cfg.get("early_stopping_patience", 6))

    default_hidden = [int(v) for v in tr_cfg.get("hidden_units", [64, 32])]
    hidden_grid = tr_cfg.get("search_hidden_units", [default_hidden])
    hidden_grid = [[int(v) for v in hs] for hs in hidden_grid]
    dropout_grid = [float(v) for v in tr_cfg.get("search_dropout", [float(tr_cfg.get("dropout", 0.15))])]
    lr_grid = [float(v) for v in tr_cfg.get("search_learning_rate", [float(tr_cfg.get("learning_rate", 1e-3))])]
    activation_grid = [str(v) for v in tr_cfg.get("search_activation", [str(tr_cfg.get("activation", "relu"))])]
    batch_norm_grid = [bool(v) for v in tr_cfg.get("search_batch_norm", [bool(tr_cfg.get("batch_norm", False))])]
    l2_grid = [float(v) for v in tr_cfg.get("search_l2", [float(tr_cfg.get("l2_kernel", 0.0))])]
    use_focal_grid = [bool(v) for v in tr_cfg.get("search_use_focal", [bool(tr_cfg.get("use_focal", False))])]
    focal_gamma_grid = [float(v) for v in tr_cfg.get("search_focal_gamma", [float(tr_cfg.get("focal_gamma", 2.0))])]
    focal_alpha_grid = [float(v) for v in tr_cfg.get("search_focal_alpha", [float(tr_cfg.get("focal_alpha", 0.25))])]
    seed_grid = [int(v) for v in tr_cfg.get("search_seeds", [seed])]

    best_result: dict[str, object] | None = None
    for hs in hidden_grid:
        for dr in dropout_grid:
            for lr in lr_grid:
                for act in activation_grid:
                    for bn in batch_norm_grid:
                        for l2 in l2_grid:
                            for use_focal in use_focal_grid:
                                for fg in focal_gamma_grid:
                                    for fa in focal_alpha_grid:
                                        for run_seed in seed_grid:
                                            result = train_candidate(
                                                feature_dim=len(feature_columns),
                                                hidden_units=hs,
                                                dropout=dr,
                                                learning_rate=lr,
                                                activation=act,
                                                batch_norm=bn,
                                                l2_kernel=l2,
                                                use_focal=use_focal,
                                                focal_gamma=fg,
                                                focal_alpha=fa,
                                                epochs=epochs,
                                                batch_size=batch_size,
                                                patience=patience,
                                                seed=run_seed,
                                                x_train_n=x_train_n,
                                                y_train=y_train,
                                                x_val_n=x_val_n,
                                                y_val=y_val,
                                                x_test_n=x_test_n,
                                                y_test=y_test,
                                            )

                                            if best_result is None:
                                                best_result = result
                                                continue

                                            cur = result["metrics"]["val"]
                                            best = best_result["metrics"]["val"]
                                            if metric_priority_tuple(cur, selection_priority) > metric_priority_tuple(best, selection_priority):
                                                best_result = result

    if best_result is None:
        raise RuntimeError("Model search produced no candidates")

    model = best_result["model"]
    metrics = best_result["metrics"]
    selected_params = best_result["params"]

    keras_path = Path(exp["keras_model_path"])
    ensure_parent(keras_path)
    if keras_path.exists() and keras_path.is_dir():
        shutil.rmtree(keras_path)
    model.save(keras_path)

    bundle = {
        "model_type": "tf_mlp",
        "feature_columns": feature_columns,
        "mean": mean.astype(np.float32),
        "std": std.astype(np.float32),
        "threshold": float(metrics["threshold"]),
        "keras_model_path": str(keras_path),
        "split": {
            "strategy": "group_subject" if ("subject" in df.columns and df["subject"].nunique() > 2) else "random",
            "random_seed": seed,
            "train_indices": train_abs_idx.astype(int).tolist(),
            "val_indices": val_abs_idx.astype(int).tolist(),
            "test_indices": test_abs_idx.astype(int).tolist(),
            "train_row_keys": make_row_keys(df, train_abs_idx.astype(np.int32)),
            "val_row_keys": make_row_keys(df, val_abs_idx.astype(np.int32)),
            "test_row_keys": make_row_keys(df, test_abs_idx.astype(np.int32)),
        },
        "selected_params": selected_params,
        "selection_metric_priority": selection_priority,
        "metrics": metrics,
    }

    bundle_path = Path(exp["model_bundle_path"])
    ensure_parent(bundle_path)
    joblib.dump(bundle, bundle_path)

    metrics_path = bundle_path.with_suffix(".metrics.json")
    write_json(metrics_path, metrics)

    print(f"[train] Saved TensorFlow model: {keras_path}")
    print(f"[train] Saved model bundle: {bundle_path}")
    print(f"[train] Selected params: {selected_params}")
    print(f"[train] Validation metrics: {metrics['val']}")
    print(f"[train] Test metrics: {metrics['test']}")


if __name__ == "__main__":
    run()
