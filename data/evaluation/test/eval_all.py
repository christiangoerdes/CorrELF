import argparse
import csv
import json
import math
from pathlib import Path
from typing import List, Dict, Any
from tqdm import tqdm

DEFAULT_THR = 0.6170

CSV_FIELDS = [
    "scope", "threshold",
    "tp", "fp", "tn", "fn",
    "tpr", "fpr", "precision", "specificity", "accuracy", "f1", "youdenJ",
    "sample_pos", "sample_neg", "rows"
]

def family_from_name(name: str) -> str:
    try:
        s = (name or "").strip().lower()
        i = s.find("___")
        return s[:i] if i > 0 else ""
    except Exception:
        return ""

def metrics_from_counts(tp: int, fp: int, tn: int, fn: int) -> Dict[str, float]:

    pos = tp + fn
    neg = tn + fp
    tpr = tp / pos if pos else 0.0
    fpr = fp / neg if neg else 0.0
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    spec = tn / neg if neg else 0.0
    acc  = (tp + tn) / (pos + neg) if (pos + neg) else 0.0
    f1   = (2 * tp) / (2 * tp + fp + fn) if (2 * tp + fp + fn) else 0.0
    j    = tpr - fpr
    return {
        "tpr": tpr, "fpr": fpr, "precision": prec, "specificity": spec,
        "accuracy": acc, "f1": f1, "youdenJ": j,
        "sample_pos": pos, "sample_neg": neg
    }

def evaluate_overall_from_json_dir(json_dir: Path,
                                   threshold: float,
                                   progress: bool = True) -> Dict[str, Any]:

    tp = fp = tn = fn = rows = 0

    files = sorted([p for p in json_dir.glob("*.json") if p.is_file()])
    iterator = tqdm(files, desc="Scanning JSONs", unit="file") if progress else files

    for jf in iterator:
        try:
            with open(jf, "r", encoding="utf-8") as f:
                data = json.load(f)

            if not isinstance(data, list) or not data:
                continue

            anchor = (data[0] or {}).get("secondFileName", "")
            src_family = family_from_name(anchor)
            if not src_family:
                continue

            for rec in data:
                dst_family = family_from_name(rec.get("fileName", ""))
                y = 1 if (dst_family and dst_family == src_family) else 0

                s = rec.get("score_weighted", None)
                try:
                    s = float(s)
                except Exception:
                    continue
                if not math.isfinite(s):
                    continue

                pred = 1 if s >= threshold else 0
                if pred == 1 and y == 1:
                    tp += 1
                elif pred == 1 and y == 0:
                    fp += 1
                elif pred == 0 and y == 0:
                    tn += 1
                else:
                    fn += 1
                rows += 1

        except Exception:
            continue

    m = metrics_from_counts(tp, fp, tn, fn)
    return {
        "scope": "ALL_JSONS",
        "threshold": threshold,
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
        **m,
        "rows": rows,
    }

def write_csv_single(row: Dict[str, Any], out_csv: Path):
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        w.writerow({k: row.get(k) for k in CSV_FIELDS})


def main():
    ap = argparse.ArgumentParser(
        description="Overall metrics @ fixed threshold from JSON folder (uses score_weighted)."
    )
    ap.add_argument("--json-dir", required=True, type=Path,
                    help="Folder with *.json files (each: array of comparisons)")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THR,
                    help=f"Operating threshold (default: {DEFAULT_THR:.4f})")
    ap.add_argument("--out-csv", type=Path, default=None,
                    help="Output CSV (default: _perf_overall_json.csv next to this script)")
    ap.add_argument("--no-progress", action="store_true",
                    help="Disable progress bar")
    args = ap.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_csv = args.out_csv or (script_dir / "_perf_overall_json.csv")

    row = evaluate_overall_from_json_dir(
        args.json_dir, args.threshold, progress=not args.no_progress
    )
    write_csv_single(row, out_csv)
    print(json.dumps(row, indent=2))
    print(f"[OK] Wrote overall metrics -> {out_csv.resolve()}")

if __name__ == "__main__":
    main()
