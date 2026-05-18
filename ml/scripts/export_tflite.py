#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np
import tensorflow as tf

from common import ensure_parent, load_config, write_json


def build_serving_module_for_bundle(bundle: dict) -> tuple[tf.Module, list[str]]:
    model_type = bundle.get("model_type")
    if model_type == "tf_mlp":
        feature_columns = bundle["feature_columns"]
        mean = np.asarray(bundle["mean"], dtype=np.float32)
        std = np.asarray(bundle["std"], dtype=np.float32)

        keras_path = Path(bundle["keras_model_path"])
        if not keras_path.exists():
            raise FileNotFoundError(f"Trained keras model not found: {keras_path}")
        base_model = tf.keras.models.load_model(keras_path, compile=False)

        class ServingModule(tf.Module):
            def __init__(self, model: tf.keras.Model, mean_vec: np.ndarray, std_vec: np.ndarray) -> None:
                super().__init__()
                self.model = model
                self.mean = tf.constant(mean_vec, dtype=tf.float32)
                self.std = tf.constant(std_vec, dtype=tf.float32)

            @tf.function(input_signature=[tf.TensorSpec(shape=[None, len(feature_columns)], dtype=tf.float32, name="features")])
            def serve(self, features: tf.Tensor) -> tf.Tensor:
                normalized = (features - self.mean) / self.std
                return self.model(normalized, training=False)

        return ServingModule(base_model, mean, std), feature_columns

    if model_type == "tf_ensemble_avg":
        members = bundle.get("members")
        if not isinstance(members, list) or not members:
            raise RuntimeError("Ensemble bundle has no members")

        feature_columns = [str(v) for v in members[0]["feature_columns"]]
        keras_models: list[tf.keras.Model] = []
        means: list[np.ndarray] = []
        stds: list[np.ndarray] = []
        weights: list[float] = []

        for idx, member in enumerate(members):
            member_features = [str(v) for v in member["feature_columns"]]
            if member_features != feature_columns:
                raise RuntimeError(f"Ensemble member {idx} uses mismatched feature columns")

            keras_path = Path(member["keras_model_path"])
            if not keras_path.exists():
                raise FileNotFoundError(f"Ensemble member keras model not found: {keras_path}")

            keras_models.append(tf.keras.models.load_model(keras_path, compile=False))
            means.append(np.asarray(member["mean"], dtype=np.float32))
            stds.append(np.asarray(member["std"], dtype=np.float32))
            weights.append(float(member.get("weight", 1.0)))

        total_weight = float(sum(weights))
        if total_weight <= 0:
            raise RuntimeError("Ensemble member weights must sum to a positive value")
        normalized_weights = [w / total_weight for w in weights]

        class EnsembleServingModule(tf.Module):
            def __init__(
                self,
                models: list[tf.keras.Model],
                mean_vecs: list[np.ndarray],
                std_vecs: list[np.ndarray],
                model_weights: list[float],
            ) -> None:
                super().__init__()
                self.models = models
                self.means = [tf.constant(v, dtype=tf.float32) for v in mean_vecs]
                self.stds = [tf.constant(v, dtype=tf.float32) for v in std_vecs]
                self.weights = [tf.constant(v, dtype=tf.float32) for v in model_weights]

            @tf.function(input_signature=[tf.TensorSpec(shape=[None, len(feature_columns)], dtype=tf.float32, name="features")])
            def serve(self, features: tf.Tensor) -> tf.Tensor:
                outputs = []
                for model, mean, std, weight in zip(self.models, self.means, self.stds, self.weights):
                    normalized = (features - mean) / std
                    outputs.append(model(normalized, training=False) * weight)
                return tf.add_n(outputs)

        return EnsembleServingModule(keras_models, means, stds, normalized_weights), feature_columns

    raise RuntimeError(f"Unsupported model bundle type: {model_type}")


def run() -> None:
    parser = argparse.ArgumentParser(description="Export trained stress model bundle to TFLite.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    exp = cfg["export"]

    bundle_path = Path(exp["model_bundle_path"])
    if not bundle_path.exists():
        raise FileNotFoundError(f"Model bundle not found: {bundle_path}")

    bundle = joblib.load(bundle_path)
    serving, feature_columns = build_serving_module_for_bundle(bundle)
    threshold = float(bundle.get("threshold", 0.5))
    concrete_fn = serving.serve.get_concrete_function()

    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_fn], serving)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()

    tflite_path = Path(exp["tflite_path"])
    ensure_parent(tflite_path)
    tflite_path.write_bytes(tflite_bytes)

    metadata_path = Path(exp["metadata_path"])
    write_json(
        metadata_path,
        {
            "model_name": "stress_model",
            "version": "v3-tf-tabular",
            "model_type": bundle.get("model_type"),
            "input_features": feature_columns,
            "input_dim": len(feature_columns),
            "output": "stress_probability",
            "decision_threshold": threshold,
            "source_bundle": str(bundle_path),
            "tflite_path": str(tflite_path),
            "normalization_embedded": True,
        },
    )

    print(f"[export] Using model bundle: {bundle_path}")
    print(f"[export] Saved tflite model: {tflite_path}")
    print(f"[export] Saved metadata: {metadata_path}")


if __name__ == "__main__":
    run()
