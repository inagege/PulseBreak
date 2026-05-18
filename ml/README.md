# Stress Model Pipeline (`ml/`)

This directory contains the end-to-end training/export pipeline for stress detection using the Bemotion dataset.

## What this version does

- Uses Bemotion-native parsing (`HeartRateMeasurements.csv` + `SelfReports.csv`) in `prepare_bemotion.py`
- Trains a TensorFlow tabular baseline with subject-wise splits in `train_stress_classifier.py`
- Supports lightweight validation-driven model search (`search_hidden_units`, `search_dropout`, `search_learning_rate`, `search_seeds`)
- Uses AUROC-first model selection with balanced-metric tie-breaks (`selection_metric_priority`)
- Tunes decision threshold on validation data (stored in bundle + metadata)
- Evaluates on held-out test split by default (no train/val leakage)
- Exports a TFLite model with normalization embedded in the serving graph
- Supports both single-model bundles and averaged TensorFlow ensemble bundles
- Packages model artifacts directly into `app/src/main/assets/ml/`

Label quality controls are configurable in `ml/configs/stress_baseline.yaml`:

- `ignored_label_values`: self-reports to discard (e.g., `none`)
- `max_label_age_ms`: maximum label carry-forward age when aligning HR rows to self-reports

Evaluation reports now also include `specificity`, `balanced_accuracy`, and `mcc` to avoid misleading interpretation under strong class imbalance.

The prepared feature set now includes richer heart-rate and motion descriptors, including trend, range, IQR, coefficient of variation, inter-beat-interval proxies, motion quantiles, motion state fractions, and HR-motion interaction features.

Experiment history is tracked in `ml/EXPERIMENT_LOG.md`. Update that file whenever you change the stress-model config, training code, thresholding policy, or reported metrics.

## Install dependencies

```bash
python3 -m pip install -r ml/requirements.txt
```

## One-pass run

```bash
python3 ml/scripts/run_stress_pipeline.py --config ml/configs/stress_baseline.yaml
```

The pipeline selects the training script from `training.model_family`:

- default / omitted: `tf_mlp` -> `train_stress_classifier.py`
- `logistic_regression` -> `train_logistic_exportable.py`

## One-pass recovery search (recommended after AUROC drops)

This sweep selects `label_match_window_ms`, `max_label_age_ms`, `window_seconds`, and `step_seconds` using validation metrics only,
then runs one final full pipeline with the best pair.

```bash
python3 ml/scripts/search_label_window_recovery.py --search-config ml/configs/stress_label_recovery.yaml --skip-download
```

Outputs:

- trial artifacts under `ml/artifacts/recovery_search/run_*/trial_*`
- search summary in `ml/artifacts/recovery_search/run_*/leaderboard.json`
- selected final config in `ml/artifacts/recovery_search/run_*/best_config.yaml`

## Cross-Validated Model Comparison

This compares candidate model families using repeated `StratifiedGroupKFold` on the non-test subjects only.
It keeps the configured holdout test subjects out of model selection.

```bash
python3 ml/scripts/compare_models_cv.py --config ml/configs/stress_model_comparison.yaml
```

Output:

- `ml/artifacts/model_comparison/repeated_group_cv.json`
- clean dev-CV winner on the current dataset: `logistic_l2`

## Step-by-step run

```bash
python3 ml/scripts/download_bemotion.py
python3 ml/scripts/prepare_bemotion.py --config ml/configs/stress_baseline.yaml
python3 ml/scripts/train_stress_classifier.py --config ml/configs/stress_baseline.yaml
python3 ml/scripts/export_tflite.py --config ml/configs/stress_baseline.yaml
python3 ml/scripts/evaluate_model.py --config ml/configs/stress_baseline.yaml --scope test
# optional diagnostic run on all prepared rows
python3 ml/scripts/evaluate_model.py --config ml/configs/stress_baseline.yaml --scope all
python3 ml/scripts/package_app_ml_assets.py --config ml/configs/stress_baseline.yaml
```

## Smoke test

```bash
python3 ml/scripts/smoke_test_pipeline.py
```

## Expected outputs

- `ml/artifacts/prepared/stress_windows.csv`
- `ml/artifacts/checkpoints/stress_bundle.joblib`
- `ml/artifacts/checkpoints/stress_bundle.metrics.json`
- `ml/artifacts/checkpoints/stress_bundle.eval.test.json`
- `ml/artifacts/checkpoints/stress_bundle.eval.all.json` (only if run with `--scope all`)
- `ml/artifacts/checkpoints/stress_bundle.eval.json`
- `ml/artifacts/export/stress_model.tflite`
- `ml/artifacts/export/model_metadata.json`
- `app/src/main/assets/ml/stress_model.tflite`
- `app/src/main/assets/ml/model_metadata.json`
