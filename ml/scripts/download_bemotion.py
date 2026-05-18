#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

REPO_URL = "https://github.com/Bemotion-dataset/dataset.git"


def run() -> None:
    parser = argparse.ArgumentParser(description="Download or update the Bemotion dataset repository.")
    parser.add_argument(
        "--target-dir",
        default="ml/data/raw/bemotion-dataset",
        help="Directory where the dataset repo should exist.",
    )
    args = parser.parse_args()

    target = Path(args.target_dir)
    target.parent.mkdir(parents=True, exist_ok=True)

    if (target / ".git").exists():
        print(f"[download] Updating existing repo at {target}")
        subprocess.run(["git", "-C", str(target), "pull", "--ff-only"], check=True)
    else:
        print(f"[download] Cloning Bemotion dataset into {target}")
        subprocess.run(["git", "clone", REPO_URL, str(target)], check=True)

    print("[download] Done")


if __name__ == "__main__":
    run()
