from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml


def load_config(path: str) -> dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def ensure_parent(path: str | Path) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)


def write_json(path: str | Path, payload: dict[str, Any]) -> None:
    ensure_parent(path)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)


def resolve_column(df_columns: list[str], aliases: list[str]) -> str | None:
    lower_to_original = {c.lower(): c for c in df_columns}
    for alias in aliases:
        hit = lower_to_original.get(alias.lower())
        if hit is not None:
            return hit
    return None


def to_bool_label(value: Any, positive_values: set[str]) -> int:
    if value is None:
        return 0
    text = str(value).strip().lower()
    return 1 if text in positive_values else 0

