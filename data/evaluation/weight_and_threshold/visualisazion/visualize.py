import os
import json
import glob
import math
import random
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

random.seed(42)
np.random.seed(42)

DATA_DIR_CANDIDATES = [
    "../train_busybox_128_minhash/busybox"
]

EXPORT_SUBDIR = "exports"
OUTPUT_DIR = (Path.cwd() / EXPORT_SUBDIR).resolve()
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

COLOR_BUSY = "#2CFF05"
COLOR_NON  = "#4166F5"
POINT_SIZE_NON = 10
POINT_SIZE_BUSY = 12
ALPHA_NON = 0.6
ALPHA_BUSY = 0.85

MAX_POINTS_WARNING = 1_000_000

def find_data_dir(candidates: List[str]) -> Path:
    for c in candidates:
        p = Path(c)
        if p.exists() and p.is_dir():
            return p
    return Path("")

def load_json_file(fp: Path) -> List[Dict[str, Any]]:
    with fp.open("r", encoding="utf-8") as f:
        return json.load(f)

def detect_is_busybox(name: str) -> bool:
    return isinstance(name, str) and ("busybox" in name.lower())

def busybox_id(name: str) -> str:
  
    if not isinstance(name, str):
        return ""
    lower = name.lower()

    if "busybox___" in lower:
        return lower.split("busybox___", 1)[1].strip()
    return lower.strip()


data_dir = find_data_dir(DATA_DIR_CANDIDATES)

if not data_dir:
    print("Data dir not found")
else:
    json_files = sorted([Path(p) for p in glob.glob(str(data_dir / "*.json"))])
    if not json_files:
        print(f"No json files in {data_dir}.")
    else:
        print(f"Found json files: {len(json_files)} in {data_dir}")
        rows = []
        source_map = {}  # src_idx -> src_name
        for src_idx, jf in enumerate(json_files):
            try:
                arr = load_json_file(jf)
            except Exception as e:
                print(f"Fehler beim Laden von {jf.name}: {e}")
                continue
            src_name = None
            if arr and isinstance(arr, list) and isinstance(arr[0], dict):
                src_name = arr[0].get("secondFileName", jf.stem)
            else:
                src_name = jf.stem
            source_map[src_idx] = src_name

            src_is_busy = detect_is_busybox(src_name)
            src_id = busybox_id(src_name)

            for item in arr:
                if not isinstance(item, dict):
                    continue
                target = item.get("fileName", "")
                if src_is_busy and detect_is_busybox(target):
                    if busybox_id(target) == src_id:
                        continue  # skip self match

                comp = item.get("comparisonDetails", {}) or {}
                is_target_busybox = detect_is_busybox(target)
                for rep, val in comp.items():
                    try:
                        x = float(val)
                    except Exception:
                        continue
                    rows.append((src_idx, src_name, target, is_target_busybox, rep, x))

        df = pd.DataFrame(rows, columns=[
            "src_idx", "src_name", "target_name", "is_target_busybox", "representation", "value"
        ])

        if df.empty:
            print("Empty data frame")
        else:
            n_points = len(df)
            reps = sorted(df["representation"].unique())
            print(f"Point total: {n_points:,}")
            print(f"Representations: {reps}")

            raw_csv = OUTPUT_DIR / "busybox_scatter_data.csv"
            df.to_csv(raw_csv, index=False)

            src_map_df = pd.DataFrame(
                [{"src_idx": i, "src_name": n} for i, n in source_map.items()]
            ).sort_values("src_idx")
            srcmap_csv = OUTPUT_DIR / "busybox_source_index_map.csv"
            src_map_df.to_csv(srcmap_csv, index=False)

            stats = (
                df.groupby(["representation", "is_target_busybox"])["value"]
                .agg(["count", "mean", "std", "min", "max"])
                .reset_index()
            )
            stats_csv = OUTPUT_DIR / "busybox_rep_stats.csv"
            stats.to_csv(stats_csv, index=False)

            print(f"Exportiert: {raw_csv.name}, {srcmap_csv.name}, {stats_csv.name} (im aktuellen Verzeichnis)")

            plt.rcParams.update({
                "figure.figsize": (12, 8),
                "axes.titlesize": 16,
                "axes.labelsize": 13,
                "xtick.labelsize": 11,
                "ytick.labelsize": 10,
                "axes.grid": True,
                "grid.alpha": 0.25,
                "grid.linestyle": "--",
            })

            unique_src = df["src_idx"].unique()
            jitter_map = {i: (random.random() - 0.5) * 0.5 for i in unique_src}
            df["y"] = df["src_idx"].map(jitter_map) + df["src_idx"]

            busy = df[df["is_target_busybox"] == True]
            nonbusy = df[df["is_target_busybox"] == False]

            for rep in reps:
                d_all = df[df["representation"] == rep]
                d_non = nonbusy[nonbusy["representation"] == rep]
                d_busy = busy[busy["representation"] == rep]

                fig = plt.figure()
                ax = plt.gca()

                if not d_non.empty:
                    ax.scatter(
                        d_non["value"], d_non["y"],
                        s=POINT_SIZE_NON, alpha=ALPHA_NON, label="Andere", color=COLOR_NON
                    )
                if not d_busy.empty:
                    ax.scatter(
                        d_busy["value"], d_busy["y"],
                        s=POINT_SIZE_BUSY, alpha=ALPHA_BUSY, label="BusyBox", color=COLOR_BUSY, edgecolors="none"
                    )

                ax.spines["top"].set_visible(False)
                ax.spines["right"].set_visible(False)

                for side in ("left", "bottom"):
                    ax.spines[side].set_linewidth(1.4)          
                    ax.spines[side].set_position(("outward", 6))
                    ax.spines[side].set_zorder(10)              

                ax.tick_params(axis="both", length=4, width=1.1)

                ax.set_axisbelow(True)
                ax.grid(axis="y", which="major", linestyle="--", alpha=0.35, linewidth=0.8)
                ax.grid(axis="x", which="major", alpha=0.2, linewidth=0.6)

                if not d_all.empty and d_all["value"].notna().any():
                    xmin = max(0.0, float(d_all["value"].min()) - 0.02)
                    xmax = min(1.0, float(d_all["value"].max()) + 0.02)
                    if math.isfinite(xmin) and math.isfinite(xmax) and xmin < xmax:
                        ax.set_xlim(xmin, xmax)

                src_count = df["src_idx"].nunique()
                if src_count <= 60:
                    ax.set_yticks(range(src_count))
                    ax.set_yticklabels(range(src_count))
                else:
                    step = max(1, src_count // 30)
                    ticks = list(range(0, src_count, step))
                    ax.set_yticks(ticks)
                    ax.set_yticklabels(ticks)

                ax.set_xlabel(f"{rep} – Score (0–1)")
                ax.set_ylabel("Quellenindex")

                handles, labels = ax.get_legend_handles_labels()
                legend = ax.legend(
                    handles, labels,
                    loc="upper center",
                    bbox_to_anchor=(0.5, -0.12), 
                    ncol=2,
                    frameon=False
                )
                fig.subplots_adjust(bottom=0.18) 

                fig.tight_layout()
                out_path = OUTPUT_DIR / f"scatter_{rep}.png"
                fig.savefig(out_path, dpi=160)
                plt.show()

            print("Plots gespeichert (PNG) im aktuellen Verzeichnis.")
