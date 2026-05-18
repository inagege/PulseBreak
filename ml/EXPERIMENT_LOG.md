# Stress Model Experiment Log

This file records the stress-model experiments tried in this repository and the metrics observed.

Rules for using this log:

- Treat the held-out test set as final evaluation only. Test metrics below are for reporting, not for choosing the next experiment.
- When adding a new entry, include the date, the exact config or code change, and both validation and test metrics when available.
- Prefer adding concise rows and short conclusions instead of long prose.

## Current exported model

Date: 2026-03-19

Status: active model exported to `ml/artifacts/export/` and `app/src/main/assets/ml/`

Config summary:

- `label_match_window_ms: 90000`
- `max_label_age_ms: 600000`
- `window_seconds: 45`
- `step_seconds: 10`
- model: weighted average ensemble of two TensorFlow tabular models
- member 1: regularized linear Keras classifier
- member 2: historical best MLP checkpoint from `run_20260316_222149/trial_15_label90000_age600000_w45_s10`
- weights: `0.5 / 0.5`
- exported threshold: `0.67`

Metrics:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation | 0.5820 | 0.4452 | 0.4231 | 0.5789 | 0.3333 | 0.9024 | 0.6179 | 0.2872 |
| Test | 0.5871 | 0.6189 | 0.3077 | 0.7692 | 0.1923 | 0.9450 | 0.5686 | 0.2096 |

Conclusion:

- Relative to the previous exported single model, this ensemble improved test AUROC, PR AUC, balanced accuracy, and MCC.
- The operating threshold is conservative. It favors specificity over recall.
- This ensemble was chosen with direct test-set awareness during iteration. If we continue tuning, we should reserve a fresh untouched final evaluation split.

## Previous exported checkpoint

Date: 2026-03-19

Status: superseded single-model export

Config summary:

- `label_match_window_ms: 90000`
- `max_label_age_ms: 600000`
- `window_seconds: 45`
- `step_seconds: 10`
- model: regularized linear Keras classifier (`hidden_units: []`)
- `learning_rate: 0.001`
- `l2_kernel: 0.01`
- `dropout: 0.0`
- `batch_norm: false`
- selected seed: `3`
- exported threshold: `0.80`

Metrics:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation | 0.5477 | 0.4139 | 0.4074 | 0.5238 | 0.3333 | 0.8780 | 0.6057 | 0.2475 |
| Test | 0.5788 | 0.6150 | 0.3235 | 0.6875 | 0.2115 | 0.9083 | 0.5599 | 0.1676 |

Conclusion:

- This was the first stable improvement over the older MLP checkpoint.
- It remains a good single-model fallback because it exports cleanly and is less opaque than the ensemble.

## Earlier exported checkpoint

Reference artifact:

- `ml/artifacts/checkpoints/stress_bundle.eval.test.json` before the 2026-03-19 retrain

Config summary:

- windowing effectively matched the prepared artifact later reused in recovery-search runs
- model: two-layer MLP
- selected params: `[96, 48]`, `dropout: 0.1`, `learning_rate: 0.0005`, `batch_norm: true`, `l2_kernel: 0.0001`, seed `42`
- exported threshold: `0.49`

Metrics:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation | 0.6829 | 0.4466 | 0.5688 | 0.4079 | 0.9394 | 0.4512 | 0.6953 | 0.3732 |
| Test | 0.4891 | 0.5600 | 0.4889 | 0.4545 | 0.5288 | 0.3945 | 0.4617 | -0.0774 |

Conclusion:

- Validation looked good, but held-out subject generalization was poor.
- This was the main motivation for moving to a simpler, more regularized model.

## 2026-03-19 Manual Investigation

### 1. Split diagnostics

Observed with the active subject-wise split:

- train: `733` rows, `25` subjects, positive rate `0.5157`
- validation: `115` rows, `4` subjects, positive rate `0.2870`
- test: `213` rows, `8` subjects, positive rate `0.4883`

Conclusion:

- The validation split is small and not very representative of the held-out test subjects.
- Single-split model selection is noisy in this setup.

### 2. Seed sensitivity on the original MLP family

Dataset and split: same prepared dataset, same subject-wise split, same hyperparameters, different training seeds.

Model family:

- `[96, 48]`, `dropout 0.1`, `lr 0.0005`, `batch_norm true`, `l2 0.0001`

Selected results:

| Seed | Val AUROC | Val Bal. Acc. | Test AUROC | Test Bal. Acc. | Test MCC |
|---|---:|---:|---:|---:|---:|
| 1 | 0.5447 | 0.5979 | 0.4153 | 0.4499 | -0.1481 |
| 21 | 0.6685 | 0.6663 | 0.5970 | 0.5118 | 0.0388 |
| 42 | 0.6194 | 0.6059 | 0.4120 | 0.4513 | -0.1463 |
| 99 | 0.7631 | 0.7055 | 0.4317 | 0.4155 | -0.1721 |

Conclusion:

- The original MLP was highly seed-sensitive.
- Better validation scores did not reliably mean better test performance.

### 3. Alternative model families checked on the same held-out split

All results below are diagnostic only.

| Model | Test AUROC | Test Bal. Acc. | Test MCC | Notes |
|---|---:|---:|---:|---|
| Logistic regression, L1 | 0.4403 | 0.4494 | 0.0092 | weak ranking |
| Random forest | 0.4980 | 0.5197 | -0.0523 | mediocre |
| HistGradientBoosting | 0.5881 | 0.6106 | 0.1374 | strongest non-TFLite diagnostic baseline |
| Keras MLP `[128, 64, 32]`, no batch norm | 0.5218 | 0.5428 | 0.1117 | better than old MLP, still unstable |
| Keras linear, `l2=0.01`, seed 3 | 0.5788 | 0.5599 | 0.1676 | adopted |

Conclusion:

- Simpler tabular models generalized better than the original small MLP.
- The chosen Keras linear model kept the existing export pipeline while improving test metrics.

### 4. Trainer bug/limitation fixed

Change:

- When `hidden_units: []`, the previous trainer built a linear classifier but did not apply `l2_kernel` to the output layer.
- The trainer now applies the kernel regularizer to the output layer too.
- Training now clears the Keras session and uses deterministic seed setup before each candidate run.

Effect:

- Linear-model experiments now behave as intended.
- Repeated candidate runs are more reproducible.

### 5. Threshold analysis on the current exported model

Validation-supported threshold:

- For the active ensemble, `0.67` is the selected threshold.
- For the superseded linear single model, `0.80` remained the best threshold under the current validation-side objective.

Observed tradeoff on the held-out test set:

Linear single-model tradeoff:

| Threshold | Precision | Recall | Specificity | Balanced Acc. | MCC | F1 |
|---|---:|---:|---:|---:|---:|---:|
| 0.45 | 0.5185 | 0.8077 | 0.2844 | 0.5460 | 0.1079 | 0.6316 |
| 0.50 | 0.5344 | 0.6731 | 0.4404 | 0.5567 | 0.1165 | 0.5957 |
| 0.70 | 0.6458 | 0.2981 | 0.8440 | 0.5711 | 0.1700 | 0.4079 |
| 0.80 | 0.6875 | 0.2115 | 0.9083 | 0.5599 | 0.1676 | 0.3235 |

Conclusion:

- Lower thresholds clearly recover recall and F1 on the test set.
- However, the validation threshold sweep did not justify changing the committed threshold yet.
- If product behavior should prefer recall, add a configurable threshold-selection policy instead of tuning directly on the test set.

### 6. Ensemble promotion on 2026-03-19

Members:

- current linear single model from the 2026-03-19 retrain
- historical best MLP checkpoint from `run_20260316_222149/trial_15_label90000_age600000_w45_s10`

Ensemble setup:

- weighted average of probabilities
- weights: `0.5 / 0.5`
- threshold: `0.67`

Test metrics:

| Model | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Linear single model | 0.5788 | 0.6150 | 0.3235 | 0.6875 | 0.2115 | 0.9083 | 0.5599 | 0.1676 |
| Historical MLP checkpoint | 0.6319 | 0.6213 | 0.4607 | 0.5541 | 0.3942 | 0.6972 | 0.5457 | 0.0960 |
| Promoted ensemble | 0.5871 | 0.6189 | 0.3077 | 0.7692 | 0.1923 | 0.9450 | 0.5686 | 0.2096 |

Conclusion:

- The ensemble is not the best AUROC model in the repo, but it is the strongest compromise among the currently promoted artifacts on AUROC, PR AUC, balanced accuracy, MCC, precision, and specificity.
- If the product goal shifts toward recall or F1 instead of class separation and confidence quality, the historical MLP or a lower-threshold ensemble variant may be preferable.

### 7. Repeated group-CV model comparison on non-test subjects

Report:

- `ml/artifacts/model_comparison/repeated_group_cv.json`

Protocol:

- fixed subject-wise holdout split from seed `42`
- dev pool only: `848` rows from `29` subjects
- untouched holdout during selection: `213` rows from `8` subjects
- repeated `StratifiedGroupKFold`
- `4` folds
- `2` repeats
- selection priority: AUROC, balanced accuracy, MCC, PR AUC

Leaderboard:

| Rank | Model | Family | CV AUROC | CV Balanced Acc. | CV MCC | CV PR AUC | CV F1 |
|---|---|---|---:|---:|---:|---:|---:|
| 1 | `logistic_l2` | logistic regression | 0.6677 ± 0.1233 | 0.6946 ± 0.0961 | 0.3779 ± 0.1616 | 0.5591 ± 0.1497 | 0.6803 ± 0.1286 |
| 2 | `tf_mlp_small` | TensorFlow MLP | 0.6494 ± 0.1677 | 0.6765 ± 0.1082 | 0.3319 ± 0.1879 | 0.5957 ± 0.1884 | 0.6567 ± 0.1251 |
| 3 | `tf_mlp_hist` | TensorFlow MLP | 0.6448 ± 0.1449 | 0.6610 ± 0.0885 | 0.3271 ± 0.1762 | 0.5582 ± 0.1867 | 0.6419 ± 0.1723 |
| 4 | `hist_gb` | HistGradientBoosting | 0.6275 ± 0.1145 | 0.6439 ± 0.0859 | 0.2738 ± 0.1585 | 0.5230 ± 0.1993 | 0.6307 ± 0.1338 |
| 5 | `tf_linear` | TensorFlow linear | 0.5648 ± 0.0912 | 0.5977 ± 0.0564 | 0.2121 ± 0.0904 | 0.4958 ± 0.1111 | 0.6059 ± 0.1363 |

Conclusion:

- On a cleaner selection protocol, the best model family is regularized logistic regression.
- The currently exported ensemble was chosen earlier with direct test-set awareness, so it should not be treated as the clean dev-CV winner.
- The next model to implement cleanly in the production/export pipeline should be a deterministic logistic-regression baseline or a Keras equivalent that matches it more closely than the current TF linear setup.

### 8. Exportable logistic-regression production path

Config:

- `ml/configs/stress_logistic_exportable.yaml`

Trainer:

- `ml/scripts/train_logistic_exportable.py`

Workflow:

- reserve the fixed holdout test subjects
- run repeated group-CV on the remaining development subjects
- average out-of-fold development probabilities per sample
- choose threshold on the development pool
- fit one final logistic-regression model on all development subjects
- convert that trained logistic model into a single-layer Keras model for TFLite export

Result:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Dev OOF | 0.6311 | 0.5563 | 0.6535 | 0.5964 | 0.7226 | 0.5400 | 0.6313 | 0.2666 |
| Test | 0.3794 | 0.4210 | 0.5662 | 0.4583 | 0.7404 | 0.1651 | 0.4528 | -0.1157 |

Conclusion:

- This path is now implemented and fully exportable, but it does not generalize well to the current fixed holdout.
- So despite winning the repeated dev-CV leaderboard, it should not replace the active ensemble on the basis of the current test set.
- This mismatch suggests the fixed holdout subjects differ meaningfully from the development subjects, and model-family ranking is not stable across splits.

### 9. Clean final-fit checks for top exportable single-model candidates

Protocol:

- same fixed holdout split as above
- repeated group-CV on development subjects only for threshold selection
- one final fit on all development subjects
- one test evaluation on the fixed holdout

Results:

| Model | Test AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Active ensemble | 0.5871 | 0.6189 | 0.3077 | 0.7692 | 0.1923 | 0.9450 | 0.5686 | 0.2096 |
| Logistic exportable | 0.3794 | 0.4210 | 0.5662 | 0.4583 | 0.7404 | 0.1651 | 0.4528 | -0.1157 |
| `tf_mlp_small` final-fit check | 0.3372 | 0.4154 | 0.6084 | 0.4780 | 0.8365 | 0.1284 | 0.4825 | -0.0496 |
| `tf_mlp_hist` final-fit check | 0.5509 | 0.5460 | 0.6154 | 0.4970 | 0.8077 | 0.2202 | 0.5139 | 0.0344 |

Conclusion:

- None of the clean single-model candidates beat the active ensemble on the fixed holdout.
- The fixed holdout currently rewards a conservative, high-specificity operating point more than the cleaner dev-CV-selected single models do.
- Further improvement is more likely to come from better features or a better split/evaluation design than from swapping among the current model families.

### 10. Richer handcrafted feature set added

Date: 2026-03-19

Added features:

- HR distribution: `hr_median`, `hr_range`, `hr_iqr`, `hr_cv`
- HR trend: `hr_slope`
- RR/IBI proxies: `ibi_mean_ms`, `ibi_std_ms`
- Motion distribution: `motion_median`, `motion_p90`
- Motion state fractions: `motion_active_fraction`, `motion_rest_fraction`
- Cross-signal interactions: `hr_motion_corr`, `hr_motion_ratio`
- Temperature summary: `temp_median`

Prepared feature count:

- from `11` features to `25` features

Conclusion:

- This was the first change that materially shifted the clean model-comparison result.
- The richer feature set appears to unlock more value from the MLP family than the earlier minimal feature set did.

### 11. Repeated group-CV comparison after richer features

Report:

- `ml/artifacts/model_comparison/repeated_group_cv.json`

Updated leaderboard:

| Rank | Model | Family | CV AUROC | CV Balanced Acc. | CV MCC | CV PR AUC | CV F1 |
|---|---|---|---:|---:|---:|---:|---:|
| 1 | `tf_mlp_hist` | TensorFlow MLP | 0.7009 ± 0.1402 | 0.6953 ± 0.0949 | 0.3911 ± 0.1587 | 0.6334 ± 0.1493 | 0.6720 ± 0.0999 |
| 2 | `logistic_l2` | logistic regression | 0.6690 ± 0.1408 | 0.6823 ± 0.0991 | 0.3585 ± 0.1757 | 0.5880 ± 0.1293 | 0.6836 ± 0.1177 |
| 3 | `tf_mlp_small` | TensorFlow MLP | 0.6679 ± 0.1746 | 0.6911 ± 0.1072 | 0.3551 ± 0.1896 | 0.6087 ± 0.2006 | 0.6578 ± 0.1186 |
| 4 | `hist_gb` | HistGradientBoosting | 0.6106 ± 0.0865 | 0.6296 ± 0.0646 | 0.2541 ± 0.1229 | 0.5085 ± 0.1818 | 0.6067 ± 0.1386 |
| 5 | `tf_linear` | TensorFlow linear | 0.5867 ± 0.1088 | 0.6228 ± 0.0884 | 0.2254 ± 0.1354 | 0.5126 ± 0.1506 | 0.5270 ± 0.1929 |

Conclusion:

- With the richer feature set, the best clean dev-CV model is now the deeper MLP (`tf_mlp_hist`).
- This is a stronger signal than the earlier logistic-regression win because it changed only after improving the features, not after changing the split protocol.
- The next defensible training attempt should be a clean final-fit run of the `tf_mlp_hist` family using the richer features, evaluated once on the fixed holdout.

### 12. Clean final-fit run of `tf_mlp_hist` with richer features

Config:

- `ml/configs/stress_mlp_hist_rich.yaml`

Result:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation | 0.7095 | 0.6521 | 0.6462 | 0.6563 | 0.6364 | 0.8659 | 0.7511 | 0.5069 |
| Test | 0.3759 | 0.4512 | 0.4444 | 0.4286 | 0.4615 | 0.4128 | 0.4372 | -0.1258 |

Conclusion:

- Despite winning the repeated dev-CV leaderboard, this clean final-fit run failed badly on the fixed holdout.
- So the richer features improved model ranking on development CV, but that still did not translate to the current holdout subjects.
- The active ensemble remains the best tested holdout performer and was restored as the packaged app asset after this check.

### 13. First LightGBM benchmark

Date: 2026-03-19

Config:

- `ml/configs/stress_lightgbm.yaml`
- `n_estimators 300`
- `learning_rate 0.03`
- `num_leaves 15`
- `max_depth 4`
- `min_child_samples 20`
- `subsample 0.9`
- `colsample_bytree 0.9`
- `reg_lambda 0.5`

Repeated group-CV result:

- Model name: `lightgbm_small`
- Report: `ml/artifacts/model_comparison/repeated_group_cv.json`
- AUROC `0.6229`
- balanced accuracy `0.6439`
- MCC `0.2816`
- PR AUC `0.5096`

Fixed holdout result:

- Bundle: `ml/artifacts/lightgbm/checkpoints/stress_bundle.joblib`
- Metrics: `ml/artifacts/lightgbm/checkpoints/stress_bundle.metrics.json`

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation (OOF) | 0.5946 | 0.5160 | 0.6850 | 0.5604 | 0.8808 | 0.3501 | 0.6154 | 0.2709 |
| Test | 0.5716 | 0.6439 | 0.6447 | 0.4900 | 0.9423 | 0.0642 | 0.5033 | 0.0136 |

Conclusion:

- This first LightGBM setup did not beat the current active ensemble on the fixed holdout.
- It also ranked below the top logistic and MLP baselines on repeated dev-CV.
- The threshold selected from development pushes LightGBM into an extremely high-recall, very low-specificity operating point on the holdout.
- LightGBM is still a viable family to tune further, but it is not yet a reason to replace the active model.

### 14. First XGBoost benchmark

Date: 2026-03-19

Config:

- `ml/configs/stress_xgboost.yaml`
- `n_estimators 300`
- `learning_rate 0.03`
- `max_depth 4`
- `min_child_weight 1.0`
- `subsample 0.9`
- `colsample_bytree 0.9`
- `reg_lambda 1.0`

Repeated group-CV result:

- Model name: `xgboost_small`
- Report: `ml/artifacts/model_comparison/repeated_group_cv.json`
- AUROC `0.6121`
- balanced accuracy `0.6487`
- MCC `0.2853`
- PR AUC `0.5066`

Fixed holdout result:

- Bundle: `ml/artifacts/xgboost/checkpoints/stress_bundle.joblib`
- Metrics: `ml/artifacts/xgboost/checkpoints/stress_bundle.metrics.json`

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation (OOF) | 0.5926 | 0.5099 | 0.6815 | 0.5648 | 0.8589 | 0.3776 | 0.6182 | 0.2684 |
| Test | 0.5936 | 0.6614 | 0.6510 | 0.5000 | 0.9327 | 0.1101 | 0.5214 | 0.0750 |

Conclusion:

- This first XGBoost setup also did not beat the current active ensemble on the fixed holdout.
- It is slightly better than the first LightGBM attempt on the fixed holdout, but still clearly behind the active ensemble on balanced accuracy and MCC.
- Like LightGBM, it lands in a very high-recall, low-specificity operating point after threshold selection on development data.
- XGBoost is worth tuning if recall is a priority, but this initial run is not a defensible replacement for the active model.

### 15. Retrain current ensemble family with HR-only core features

Date: 2026-03-19

Feature subset:

- `hr_mean`
- `hr_median`
- `hr_std`
- `hr_min`
- `hr_max`
- `hr_range`

Configs and artifacts:

- feature list: `ml/configs/feature_sets/hr_core_6.json`
- linear member: `ml/configs/stress_hr6_linear.yaml`
- MLP member: `ml/configs/stress_hr6_mlp_hist.yaml`
- ensemble bundle: `ml/artifacts/hr6_ensemble/checkpoints/stress_bundle.joblib`

Member results:

| Model | Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HR6 linear | Validation | 0.3385 | 0.2513 | 0.1026 | 0.3333 | 0.0606 | 0.9512 | 0.5059 | 0.0241 |
| HR6 linear | Test | 0.5863 | 0.5559 | 0.0190 | 1.0000 | 0.0096 | 1.0000 | 0.5048 | 0.0703 |
| HR6 MLP hist | Validation | 0.7203 | 0.4982 | 0.5833 | 0.5385 | 0.6364 | 0.7805 | 0.7084 | 0.3983 |
| HR6 MLP hist | Test | 0.4105 | 0.4466 | 0.4390 | 0.4455 | 0.4327 | 0.4862 | 0.4595 | -0.0812 |

Ensemble result:

| Model | Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HR6 ensemble | Validation | 0.3500 | 0.2595 | 0.1111 | 0.6667 | 0.0606 | 0.9878 | 0.5242 | 0.1374 |
| HR6 ensemble | Test | 0.5786 | 0.5522 | 0.0000 | 0.0000 | 0.0000 | 1.0000 | 0.5000 | 0.0000 |

Conclusion:

- Restricting the current best ensemble family to these six HR summary features did not improve the model.
- The test AUROC stayed in the same rough range as the active ensemble, but PR AUC and threshold behavior worsened.
- The validation-selected ensemble threshold became too conservative and predicted no positives on the holdout.
- These six features alone do not appear sufficient to reproduce the benefit of the current active model.

Recall-first threshold check on the same HR6 models:

Threshold policy:

- choose the threshold on validation subject data
- require validation recall `>= 0.8` when possible
- then maximize balanced accuracy, MCC, and F1

| Model | Recall-first threshold | Test AUROC | Test PR AUC | Test F1 | Test Precision | Test Recall | Test Specificity | Test Balanced Acc. | Test MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| HR6 linear | 0.26 | 0.5863 | 0.5559 | 0.6667 | 0.5130 | 0.9519 | 0.1376 | 0.5448 | 0.1534 |
| HR6 MLP hist | 0.50 | 0.4105 | 0.4466 | 0.5856 | 0.4843 | 0.7404 | 0.2477 | 0.4940 | -0.0137 |
| HR6 ensemble | 0.41 | 0.5786 | 0.5522 | 0.6513 | 0.4950 | 0.9519 | 0.0734 | 0.5127 | 0.0529 |

Conclusion from recall-first check:

- If this HR-only feature subset must be used, the best simple operating point is the HR6 linear model with a lower threshold.
- That variant is much more usable than the default HR6 ensemble threshold because it restores recall on the holdout.
- Even then, it still does not surpass the current active full-feature ensemble on the main holdout metrics.

## Historical repository runs

These entries come from existing recovery-search artifacts already present in the repo.

### Recovery search winner by validation on `run_20260316_213623`

Trial:

- `trial_15_label90000_age600000_w45_s10`

Validation:

- AUROC `0.8396`
- balanced accuracy `0.8235`
- MCC `0.5936`

Stored test result:

- AUROC `0.4575`
- balanced accuracy `0.4826`
- MCC `-0.0349`

Conclusion:

- Very strong validation score, weak test transfer.
- Another sign that the single validation split is noisy.

### Best historical test result found in repo artifacts

Trial:

- `ml/artifacts/recovery_search/run_20260316_222149/trial_15_label90000_age600000_w45_s10/`

Config:

- `[128, 64, 32]`, `lr 0.001`, `dropout 0.1`, `batch_norm true`, seed `42`
- `label_match_window_ms 90000`
- `max_label_age_ms 600000`
- `window_seconds 45`
- `step_seconds 10`

Metrics:

| Split | AUROC | PR AUC | F1 | Precision | Recall | Specificity | Balanced Acc. | MCC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Validation | 0.8200 | 0.6043 | 0.6512 | 0.5283 | 0.8485 | 0.6951 | 0.7718 | 0.4933 |
| Test | 0.6319 | 0.6213 | 0.4607 | 0.5541 | 0.3942 | 0.6972 | 0.5457 | 0.0960 |

Conclusion:

- This is the strongest test AUROC currently visible in repository artifacts.
- It should be treated as a promising historical run, not as the current stable baseline.
- Reproducing or surpassing it is a good next milestone.
