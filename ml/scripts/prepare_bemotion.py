#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from common import ensure_parent, load_config, resolve_column, to_bool_label

FEATURE_COLUMNS = [
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


def read_table(path: Path) -> pd.DataFrame:
    suffix = path.suffix.lower()
    if suffix == ".csv":
        return pd.read_csv(path)
    if suffix in {".tsv", ".txt"}:
        return pd.read_csv(path, sep="\t")
    if suffix == ".parquet":
        return pd.read_parquet(path)
    raise ValueError(f"Unsupported file type: {path}")


def std(values: np.ndarray) -> float:
    if values.size <= 1:
        return 0.0
    return float(values.std(ddof=0))


def median(values: np.ndarray) -> float:
    if values.size == 0:
        return 0.0
    return float(np.median(values))


def percentile(values: np.ndarray, q: float) -> float:
    if values.size == 0:
        return 0.0
    return float(np.percentile(values, q))


def iqr(values: np.ndarray) -> float:
    if values.size == 0:
        return 0.0
    return percentile(values, 75.0) - percentile(values, 25.0)


def coeff_var(values: np.ndarray) -> float:
    if values.size == 0:
        return 0.0
    mean = float(values.mean())
    if abs(mean) < 1e-6:
        return 0.0
    return float(values.std(ddof=0) / mean)


def linear_slope(times_ms: np.ndarray, values: np.ndarray) -> float:
    if values.size <= 1 or times_ms.size != values.size:
        return 0.0
    times_min = (times_ms - times_ms[0]) / 60000.0
    if float(times_min[-1]) <= 0:
        return 0.0
    coeffs = np.polyfit(times_min, values, deg=1)
    return float(coeffs[0])


def rmssd(values: np.ndarray) -> float:
    if values.size <= 1:
        return 0.0
    diffs = np.diff(values)
    return float(np.sqrt(np.mean(np.square(diffs))))


def pnn50(values: np.ndarray) -> float:
    if values.size <= 1:
        return 0.0
    diffs = np.abs(np.diff(values))
    return float((diffs > 5.0).mean())


def safe_corr(a: np.ndarray, b: np.ndarray) -> float:
    if a.size <= 1 or b.size <= 1 or a.size != b.size:
        return 0.0
    if float(np.std(a)) < 1e-6 or float(np.std(b)) < 1e-6:
        return 0.0
    corr = np.corrcoef(a, b)[0, 1]
    if np.isnan(corr):
        return 0.0
    return float(corr)


def fraction_above(values: np.ndarray, threshold: float) -> float:
    if values.size == 0:
        return 0.0
    return float((values > threshold).mean())


def fraction_below(values: np.ndarray, threshold: float) -> float:
    if values.size == 0:
        return 0.0
    return float((values < threshold).mean())


def to_motion(df: pd.DataFrame, columns: dict[str, str | None]) -> pd.Series:
    motion_col = columns.get("motion")
    if motion_col:
        return pd.to_numeric(df[motion_col], errors="coerce")

    acc_x = columns.get("accel_x")
    acc_y = columns.get("accel_y")
    acc_z = columns.get("accel_z")
    if acc_x and acc_y and acc_z:
        x = pd.to_numeric(df[acc_x], errors="coerce")
        y = pd.to_numeric(df[acc_y], errors="coerce")
        z = pd.to_numeric(df[acc_z], errors="coerce")
        g = 9.80665
        return np.sqrt(np.square(x) + np.square(y) + np.square(z)) / g - 1.0

    return pd.Series([np.nan] * len(df), index=df.index)


def resolve_columns(df: pd.DataFrame, aliases: dict[str, list[str]]) -> dict[str, str | None]:
    resolved: dict[str, str | None] = {}
    cols = list(df.columns)
    for key, key_aliases in aliases.items():
        resolved[key] = resolve_column(cols, key_aliases)
    return resolved


def read_zipped_csv(zip_path: Path, expected_name: str) -> pd.DataFrame:
    with zipfile.ZipFile(zip_path) as zf:
        names = [n for n in zf.namelist() if n.lower().endswith(".csv") and not n.startswith("__MACOSX")]
        if not names:
            raise RuntimeError(f"No CSV file found in zip: {zip_path}")
        preferred = next((n for n in names if Path(n).name == expected_name), names[0])
        with zf.open(preferred) as f:
            return pd.read_csv(f)


def build_windows_from_unified(
    unified: pd.DataFrame,
    window_seconds: int,
    step_seconds: int,
) -> list[dict[str, float | int | str]]:
    if unified.empty:
        return []

    unified = unified.sort_values("timestamp_ms")
    rows: list[dict[str, float | int | str]] = []
    w_ms = window_seconds * 1000
    s_ms = step_seconds * 1000

    by_subject = unified.groupby("subject")
    for subject_id, group in by_subject:
        group = group.sort_values("timestamp_ms")
        start = int(group["timestamp_ms"].min())
        end = int(group["timestamp_ms"].max())
        t = start
        while t + w_ms <= end:
            win = group[(group["timestamp_ms"] >= t) & (group["timestamp_ms"] < t + w_ms)]
            t += s_ms
            if len(win) < 4:
                continue

            hr_vals = win["hr"].to_numpy(dtype=float)
            time_vals = win["timestamp_ms"].to_numpy(dtype=float)
            motion_vals = win["motion"].dropna().to_numpy(dtype=float)
            temp_vals = win["temperature"].dropna().to_numpy(dtype=float)
            ibi_vals = 60000.0 / np.clip(hr_vals, 1.0, None)

            motion_aligned = win["motion"].to_numpy(dtype=float)
            motion_mask = ~np.isnan(motion_aligned)
            hr_motion_corr = safe_corr(hr_vals[motion_mask], motion_aligned[motion_mask]) if motion_mask.any() else 0.0
            motion_mean = float(motion_vals.mean()) if motion_vals.size else 0.0

            row: dict[str, float | int | str] = {
                "subject": str(subject_id),
                "window_start_ms": int(win["timestamp_ms"].min()),
                "window_end_ms": int(win["timestamp_ms"].max()),
                "label": int((win["label"].mean() >= 0.5)),
                "hr_mean": float(hr_vals.mean()),
                "hr_median": median(hr_vals),
                "hr_std": std(hr_vals),
                "hr_min": float(hr_vals.min()),
                "hr_max": float(hr_vals.max()),
                "hr_range": float(hr_vals.max() - hr_vals.min()),
                "hr_iqr": iqr(hr_vals),
                "hr_cv": coeff_var(hr_vals),
                "hr_slope": linear_slope(time_vals, hr_vals),
                "hr_delta_abs": float(abs(hr_vals[-1] - hr_vals[0])),
                "rmssd": rmssd(hr_vals),
                "pnn50": pnn50(hr_vals),
                "ibi_mean_ms": float(ibi_vals.mean()),
                "ibi_std_ms": std(ibi_vals),
                "motion_mean": motion_mean,
                "motion_median": median(motion_vals),
                "motion_std": std(motion_vals) if motion_vals.size else 0.0,
                "motion_p90": percentile(motion_vals, 90.0),
                "motion_active_fraction": fraction_above(motion_vals, 0.15),
                "motion_rest_fraction": fraction_below(motion_vals, 0.05),
                "hr_motion_corr": hr_motion_corr,
                "hr_motion_ratio": float(hr_vals.mean() / (1.0 + motion_mean)),
                "temp_mean": float(temp_vals.mean()) if temp_vals.size else 0.0,
                "temp_median": median(temp_vals),
                "temp_spread": float(temp_vals.max() - temp_vals.min()) if temp_vals.size >= 2 else 0.0,
            }
            rows.append(row)
    return rows


def prepare_bemotion_repository(
    raw_dir: Path,
    positive_values: set[str],
    ignored_values: set[str],
    max_label_age_ms: int,
    label_match_window_ms: int,
    sensor_alignment_tolerance_ms: int,
    window_seconds: int,
    step_seconds: int,
) -> list[dict[str, float | int | str]]:
    hr_path = raw_dir / "HeartRateMeasurements.csv"
    self_report_path = raw_dir / "SelfReports.csv"
    accel_zip_path = raw_dir / "AccelerometerMeasurements.csv.zip"
    if not hr_path.exists() or not self_report_path.exists():
        return []

    hr = pd.read_csv(hr_path)
    reports = pd.read_csv(self_report_path)

    accel: pd.DataFrame | None = None
    if accel_zip_path.exists():
        try:
            accel = read_zipped_csv(accel_zip_path, "AccelerometerMeasurements.csv")
        except Exception as exc:  # noqa: BLE001
            print(f"[prepare] Accelerometer zip could not be read, continuing without motion features: {exc}")
            accel = None

    if not {"participantId", "timestamp", "hr"}.issubset(hr.columns):
        return []
    if not {"participantId", "timeOfArousal", "arousal"}.issubset(reports.columns):
        return []

    hr = hr[["participantId", "timestamp", "hr"]].copy()
    hr["participantId"] = hr["participantId"].astype(str)
    hr["timestamp"] = pd.to_numeric(hr["timestamp"], errors="coerce")
    hr["hr"] = pd.to_numeric(hr["hr"], errors="coerce")
    hr = hr.dropna(subset=["timestamp", "hr"])
    hr = hr[hr["hr"] > 0]
    hr = hr[(hr["hr"] >= 35) & (hr["hr"] <= 220)]

    if accel is not None and {"participantId", "timestamp", "x", "y", "z"}.issubset(accel.columns):
        accel = accel[["participantId", "timestamp", "x", "y", "z"]].copy()
        accel["participantId"] = accel["participantId"].astype(str)
        accel["timestamp"] = pd.to_numeric(accel["timestamp"], errors="coerce")
        accel["x"] = pd.to_numeric(accel["x"], errors="coerce")
        accel["y"] = pd.to_numeric(accel["y"], errors="coerce")
        accel["z"] = pd.to_numeric(accel["z"], errors="coerce")
        accel = accel.dropna(subset=["timestamp", "x", "y", "z"])
        accel["mag"] = np.sqrt(np.square(accel["x"]) + np.square(accel["y"]) + np.square(accel["z"]))
        baseline = accel.groupby("participantId")["mag"].transform("median")
        accel["motion"] = (accel["mag"] - baseline).abs() / np.maximum(baseline, 1.0)
        accel = accel[["participantId", "timestamp", "motion"]]
    else:
        accel = None

    reports = reports[["participantId", "timeOfArousal", "arousal"]].copy()
    reports["participantId"] = reports["participantId"].astype(str)
    reports["timeOfArousal"] = pd.to_numeric(reports["timeOfArousal"], errors="coerce")
    reports = reports.dropna(subset=["timeOfArousal"])
    def map_arousal(value: object) -> float:
        text = str(value).strip().lower() if value is not None else ""
        if text in ignored_values:
            return np.nan
        return float(to_bool_label(text, positive_values))

    reports["label"] = reports["arousal"].map(map_arousal)
    reports = reports.dropna(subset=["label"])

    unified_parts: list[pd.DataFrame] = []
    for participant_id, hr_group in hr.groupby("participantId"):
        rep_group = reports[reports["participantId"] == participant_id].sort_values("timeOfArousal")
        if rep_group.empty:
            continue

        hr_group = hr_group.sort_values("timestamp")
        merged = pd.merge_asof(
            hr_group,
            rep_group[["timeOfArousal", "label"]],
            left_on="timestamp",
            right_on="timeOfArousal",
            direction="nearest",
            tolerance=label_match_window_ms if label_match_window_ms > 0 else None,
        )
        if max_label_age_ms > 0 and "timeOfArousal" in merged.columns:
            age_ms = merged["timestamp"] - merged["timeOfArousal"]
            merged = merged[(age_ms >= 0) & (age_ms <= max_label_age_ms)]
        merged = merged.dropna(subset=["label"])
        if merged.empty:
            continue

        if accel is not None:
            accel_group = accel[accel["participantId"] == participant_id].sort_values("timestamp")
            if not accel_group.empty:
                merged = pd.merge_asof(
                    merged.sort_values("timestamp"),
                    accel_group[["timestamp", "motion"]],
                    on="timestamp",
                    direction="nearest",
                    tolerance=sensor_alignment_tolerance_ms if sensor_alignment_tolerance_ms > 0 else None,
                )
            else:
                merged["motion"] = np.nan
        else:
            merged["motion"] = np.nan

        unified_parts.append(
            pd.DataFrame(
                {
                    "timestamp_ms": merged["timestamp"],
                    "subject": participant_id,
                    "hr": merged["hr"],
                    "label": merged["label"].astype(int),
                    "motion": merged["motion"],
                    "temperature": np.nan,
                }
            )
        )

    if not unified_parts:
        return []

    unified = pd.concat(unified_parts, ignore_index=True)
    return build_windows_from_unified(unified, window_seconds=window_seconds, step_seconds=step_seconds)


def prepare_file(
    path: Path,
    aliases: dict[str, list[str]],
    positive_values: set[str],
    ignored_values: set[str],
    window_seconds: int,
    step_seconds: int,
) -> list[dict[str, float | int | str]]:
    df = read_table(path)
    if df.empty:
        return []

    resolved = resolve_columns(df, aliases)
    if not resolved.get("timestamp") or not resolved.get("heart_rate") or not resolved.get("label"):
        return []

    ts_raw = pd.to_numeric(df[resolved["timestamp"]], errors="coerce")
    ts = ts_raw.copy()
    # If timestamps look like epoch seconds, convert to millis.
    if ts.dropna().median() < 1e11:
        ts = ts * 1000.0

    hr = pd.to_numeric(df[resolved["heart_rate"]], errors="coerce")
    def map_label(value: object) -> float:
        text = str(value).strip().lower() if value is not None else ""
        if text in ignored_values:
            return np.nan
        return float(to_bool_label(text, positive_values))

    labels = df[resolved["label"]].map(map_label)
    motion = to_motion(df, resolved)

    temp_col = resolved.get("temperature")
    if temp_col:
        temp = pd.to_numeric(df[temp_col], errors="coerce")
    else:
        temp = pd.Series([np.nan] * len(df), index=df.index)

    subject_col = resolved.get("subject")
    if subject_col:
        subject = df[subject_col].astype(str).fillna(path.stem)
    else:
        subject = pd.Series([path.stem] * len(df), index=df.index)

    unified = pd.DataFrame(
        {
            "timestamp_ms": ts,
            "subject": subject,
            "hr": hr,
            "label": labels,
            "motion": motion,
            "temperature": temp,
        }
    ).dropna(subset=["timestamp_ms", "hr", "label"])

    if unified.empty:
        return []

    return build_windows_from_unified(
        unified,
        window_seconds=window_seconds,
        step_seconds=step_seconds,
    )


def run() -> None:
    parser = argparse.ArgumentParser(description="Prepare Bemotion windows and app-aligned features.")
    parser.add_argument("--config", default="ml/configs/stress_baseline.yaml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    ds = cfg["dataset"]
    aliases = cfg["columns"]
    windowing = cfg["windowing"]
    training = cfg["training"]

    positive = {str(v).strip().lower() for v in training["positive_label_values"]}
    ignored = {str(v).strip().lower() for v in training.get("ignored_label_values", ["none", "unknown", "nan", ""]) }
    max_label_age_ms = int(training.get("max_label_age_ms", 15 * 60 * 1000))
    label_match_window_ms = int(training.get("label_match_window_ms", 2 * 60 * 1000))
    sensor_alignment_tolerance_ms = int(training.get("sensor_alignment_tolerance_ms", 2 * 1000))
    raw_dir = Path(ds["raw_dir"])
    if not raw_dir.exists():
        raise FileNotFoundError(f"Raw dataset dir not found: {raw_dir}")

    all_rows = prepare_bemotion_repository(
        raw_dir=raw_dir,
        positive_values=positive,
        ignored_values=ignored,
        max_label_age_ms=max_label_age_ms,
        label_match_window_ms=label_match_window_ms,
        sensor_alignment_tolerance_ms=sensor_alignment_tolerance_ms,
        window_seconds=int(windowing["window_seconds"]),
        step_seconds=int(windowing["step_seconds"]),
    )

    if not all_rows:
        files = [
            p
            for p in raw_dir.rglob("*")
            if p.is_file() and p.suffix.lower() in {".csv", ".tsv", ".txt", ".parquet"}
        ]
        if not files:
            raise RuntimeError("No readable data files found under raw dataset directory")

        all_rows = []
        for path in files:
            try:
                rows = prepare_file(
                    path=path,
                    aliases=aliases,
                    positive_values=positive,
                    ignored_values=ignored,
                    window_seconds=int(windowing["window_seconds"]),
                    step_seconds=int(windowing["step_seconds"]),
                )
                all_rows.extend(rows)
            except Exception as exc:  # noqa: BLE001
                print(f"[prepare] Skipping {path}: {exc}")

    if not all_rows:
        raise RuntimeError("No windows were produced. Check config aliases against dataset columns.")

    out_df = pd.DataFrame(all_rows)
    min_windows = int(training.get("min_windows", 0))
    if len(out_df) < min_windows:
        raise RuntimeError(f"Prepared only {len(out_df)} windows, below configured minimum {min_windows}")

    out_csv = Path(ds["prepared_csv"])
    ensure_parent(out_csv)
    out_df.to_csv(out_csv, index=False)

    feature_json = Path(ds["feature_columns_json"])
    ensure_parent(feature_json)
    with open(feature_json, "w", encoding="utf-8") as f:
        json.dump({"feature_columns": FEATURE_COLUMNS}, f, indent=2)

    print(f"[prepare] Wrote {len(out_df)} windows to {out_csv}")
    print(f"[prepare] Feature columns at {feature_json}")


if __name__ == "__main__":
    run()
