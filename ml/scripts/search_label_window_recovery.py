#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import datetime as dt
import itertools
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def write_yaml(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        yaml.safe_dump(payload, f, sort_keys=False)


def deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def metric_tuple(metrics: dict[str, Any], priority: list[str]) -> tuple[float, ...]:
    return tuple(float(metrics.get(k, float("-inf"))) for k in priority)


def run_cmd(cmd: list[str]) -> None:
    print("[recovery]", " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Search label window parameters and run final full pipeline with best config.")
    parser.add_argument("--search-config", default="ml/configs/stress_label_recovery.yaml")
    parser.add_argument("--skip-download", action="store_true")
    args = parser.parse_args()

    repo = Path(__file__).resolve().parents[2]
    py = sys.executable

    search_cfg = load_yaml((repo / args.search_config).resolve())
    base_cfg_path = (repo / str(search_cfg["base_config"])).resolve()
    base_cfg = load_yaml(base_cfg_path)

    recovery = search_cfg["recovery_search"]
    label_windows = [int(v) for v in recovery.get("label_match_window_ms", [120000])]
    max_ages = [int(v) for v in recovery.get("max_label_age_ms", [600000])]
    window_seconds = [int(v) for v in recovery.get("window_seconds", [int(base_cfg.get("windowing", {}).get("window_seconds", 45))])]
    step_seconds = [int(v) for v in recovery.get("step_seconds", [int(base_cfg.get("windowing", {}).get("step_seconds", 8))])]
    max_trials = int(recovery.get("max_trials", len(label_windows) * len(max_ages)))
    priority = [str(v) for v in recovery.get("selection_metric_priority", ["auroc", "balanced_accuracy", "mcc", "pr_auc"])]
    fast_overrides = recovery.get("fast_overrides", {})

    timestamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    run_root = repo / "ml" / "artifacts" / "recovery_search" / f"run_{timestamp}"
    run_root.mkdir(parents=True, exist_ok=True)

    trials: list[dict[str, Any]] = []
    explicit_trials = recovery.get("trials")
    if isinstance(explicit_trials, list) and explicit_trials:
        trial_grid = []
        for t in explicit_trials:
            trial_grid.append(
                {
                    "label_match_window_ms": int(t["label_match_window_ms"]),
                    "max_label_age_ms": int(t["max_label_age_ms"]),
                    "window_seconds": int(t.get("window_seconds", base_cfg.get("windowing", {}).get("window_seconds", 45))),
                    "step_seconds": int(t.get("step_seconds", base_cfg.get("windowing", {}).get("step_seconds", 8))),
                }
            )
    else:
        trial_grid = [
            {
                "label_match_window_ms": lm,
                "max_label_age_ms": ma,
                "window_seconds": ws,
                "step_seconds": ss,
            }
            for lm, ma, ws, ss in itertools.product(label_windows, max_ages, window_seconds, step_seconds)
        ]

    trial_grid = trial_grid[:max_trials]

    for idx, trial in enumerate(trial_grid, start=1):
        label_ms = int(trial["label_match_window_ms"])
        age_ms = int(trial["max_label_age_ms"])
        win_s = int(trial["window_seconds"])
        step_s = int(trial["step_seconds"])

        trial_dir = run_root / f"trial_{idx:02d}_label{label_ms}_age{age_ms}_w{win_s}_s{step_s}"
        trial_cfg = copy.deepcopy(base_cfg)

        trial_cfg.setdefault("training", {})
        trial_cfg["training"]["label_match_window_ms"] = label_ms
        trial_cfg["training"]["max_label_age_ms"] = age_ms
        trial_cfg.setdefault("windowing", {})
        trial_cfg["windowing"]["window_seconds"] = win_s
        trial_cfg["windowing"]["step_seconds"] = step_s
        trial_cfg = deep_merge(trial_cfg, fast_overrides)

        trial_cfg["dataset"]["prepared_csv"] = str(trial_dir / "prepared" / "stress_windows.csv")
        trial_cfg["dataset"]["feature_columns_json"] = str(trial_dir / "prepared" / "feature_columns.json")
        trial_cfg["export"]["model_bundle_path"] = str(trial_dir / "checkpoints" / "stress_bundle.joblib")
        trial_cfg["export"]["keras_model_path"] = str(trial_dir / "export" / "stress_model.keras")
        trial_cfg["export"]["tflite_path"] = str(trial_dir / "export" / "stress_model.tflite")
        trial_cfg["export"]["metadata_path"] = str(trial_dir / "export" / "model_metadata.json")

        trial_cfg_path = trial_dir / "trial_config.yaml"
        write_yaml(trial_cfg_path, trial_cfg)

        run_cmd([py, str(repo / "ml/scripts/prepare_bemotion.py"), "--config", str(trial_cfg_path)])
        run_cmd([py, str(repo / "ml/scripts/train_stress_classifier.py"), "--config", str(trial_cfg_path)])

        metrics_path = Path(trial_cfg["export"]["model_bundle_path"]).with_suffix(".metrics.json")
        with metrics_path.open("r", encoding="utf-8") as f:
            metrics = json.load(f)

        val_metrics = metrics["val"]
        trial_info = {
            "trial": idx,
            "label_match_window_ms": label_ms,
            "max_label_age_ms": age_ms,
            "window_seconds": win_s,
            "step_seconds": step_s,
            "val": val_metrics,
            "score_tuple": metric_tuple(val_metrics, priority),
            "trial_config": str(trial_cfg_path),
        }
        trials.append(trial_info)
        print(f"[recovery] Trial {idx} val metrics: {val_metrics}")

    if not trials:
        raise RuntimeError("No recovery-search trials were executed")

    best = max(trials, key=lambda t: tuple(t["score_tuple"]))

    leaderboard = {
        "selection_metric_priority": priority,
        "trials": trials,
        "best": best,
    }
    leaderboard_path = run_root / "leaderboard.json"
    leaderboard_path.write_text(json.dumps(leaderboard, indent=2), encoding="utf-8")

    best_cfg = copy.deepcopy(base_cfg)
    best_cfg.setdefault("training", {})
    best_cfg["training"]["label_match_window_ms"] = int(best["label_match_window_ms"])
    best_cfg["training"]["max_label_age_ms"] = int(best["max_label_age_ms"])
    best_cfg.setdefault("windowing", {})
    best_cfg["windowing"]["window_seconds"] = int(best.get("window_seconds", best_cfg["windowing"].get("window_seconds", 45)))
    best_cfg["windowing"]["step_seconds"] = int(best.get("step_seconds", best_cfg["windowing"].get("step_seconds", 8)))

    best_cfg_path = run_root / "best_config.yaml"
    write_yaml(best_cfg_path, best_cfg)

    print("[recovery] Best trial:", best)
    print(f"[recovery] Leaderboard: {leaderboard_path}")
    print(f"[recovery] Best config: {best_cfg_path}")

    cmd = [
        py,
        str(repo / "ml/scripts/run_stress_pipeline.py"),
        "--config",
        str(best_cfg_path),
    ]
    if args.skip_download:
        cmd.append("--skip-download")
    run_cmd(cmd)

    final_eval = repo / "ml" / "artifacts" / "checkpoints" / "stress_bundle.eval.test.json"
    if final_eval.exists():
        print(f"[recovery] Final test report: {final_eval}")
        print(final_eval.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()

