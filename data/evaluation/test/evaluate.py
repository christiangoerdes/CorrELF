import argparse
import csv
from pathlib import Path
from collections import defaultdict

import numpy as np
import pyarrow as pa
import pyarrow.dataset as ds
from tqdm import tqdm


PARTITION_COL = "src_family"
LABEL_COL     = "label_same_family"
BIN_SM_COL    = "bin_string_minhash"
BIN_CR_COL    = "bin_code_regions"
BIN_PH_COL    = "bin_program_header"

W_SM = 0.6170
W_CR = 0.2946
W_PH = 0.0884

SCORES_BY_PATTERN = np.array([
    0.0,                    # 000
    W_SM,                   # 001
    W_CR,                   # 010
    W_SM + W_CR,            # 011
    W_PH,                   # 100
    W_SM + W_PH,            # 101
    W_CR + W_PH,            # 110
    W_SM + W_CR + W_PH      # 111
], dtype=np.float64)


CSV_FIELDS = [
    "threshold", "tp", "fp", "tn", "fn",
    "tpr", "fpr", "precision", "specificity", "accuracy", "f1", "youdenJ",
    "sample_pos", "sample_neg"
]

def aggregate_counts(in_dir: Path,
                     family: str | None,
                     batch_size: int = 512_000,
                     by_family: bool = False):

    cols = [LABEL_COL, BIN_SM_COL, BIN_CR_COL, BIN_PH_COL]
    if by_family or family:
        cols = [PARTITION_COL] + cols

    dset = ds.dataset(str(in_dir), format="parquet", partitioning="hive")
    filt = (ds.field(PARTITION_COL) == family) if family else None
    scanner = dset.scanner(columns=cols, filter=filt, batch_size=batch_size, use_threads=True)

    counts_global = np.zeros((8, 2), dtype=np.int64)
    counts_by_family: dict[str, np.ndarray] = {} if by_family else None
    n_pos = n_neg = 0

    pbar = tqdm(desc="Scanning", unit="rows", leave=False)
    for b in scanner.to_batches():
        if by_family or family:
            fam  = b.column(0).to_numpy(zero_copy_only=False).astype(str)
            y    = b.column(1).to_numpy(zero_copy_only=False)
            sm   = b.column(2).to_numpy(zero_copy_only=False)
            cr   = b.column(3).to_numpy(zero_copy_only=False)
            ph   = b.column(4).to_numpy(zero_copy_only=False)
        else:
            y    = b.column(0).to_numpy(zero_copy_only=False)
            sm   = b.column(1).to_numpy(zero_copy_only=False)
            cr   = b.column(2).to_numpy(zero_copy_only=False)
            ph   = b.column(3).to_numpy(zero_copy_only=False)

        m = np.isfinite(y) & np.isfinite(sm) & np.isfinite(cr) & np.isfinite(ph)
        if not np.any(m):
            continue

        y  = y[m].astype(np.int8, copy=False)
        sm = sm[m].astype(np.int8, copy=False)
        cr = cr[m].astype(np.int8, copy=False)
        ph = ph[m].astype(np.int8, copy=False)

        pat = ((ph << 2) | (cr << 1) | sm).astype(np.int8, copy=False)

        for lbl in (0, 1):
            sel = (y == lbl)
            if np.any(sel):
                hist = np.bincount(pat[sel], minlength=8)
                counts_global[:len(hist), lbl] += hist

        # family counts
        if by_family:
            fam = fam[m]
            if fam.size:
                # group by family with numpy unique
                ufam, inv = np.unique(fam, return_inverse=True)
                for idx, f in enumerate(ufam):
                    sel_f = (inv == idx)
                    if not np.any(sel_f):
                        continue
                    y_f  = y[sel_f]
                    pat_f = pat[sel_f]
                    arr = counts_by_family.get(f)
                    if arr is None:
                        arr = np.zeros((8, 2), dtype=np.int64)
                        counts_by_family[f] = arr
                    for lbl in (0, 1):
                        sel = (y_f == lbl)
                        if np.any(sel):
                            hist = np.bincount(pat_f[sel], minlength=8)
                            arr[:len(hist), lbl] += hist

        # totals
        n_pos += int((y == 1).sum())
        n_neg += int((y == 0).sum())

        pbar.update(len(y))
    pbar.close()

    totals = {"n_pos": n_pos, "n_neg": n_neg}
    return counts_global, (counts_by_family or {}), totals


def eval_thresholds_from_counts(counts: np.ndarray, thresholds: list[float]) -> list[dict]:
    s = SCORES_BY_PATTERN  # shape (8,)
    pos_counts = counts[:, 1]
    neg_counts = counts[:, 0]
    total_pos = int(pos_counts.sum())
    total_neg = int(neg_counts.sum())

    rows = []
    for thr in thresholds:
        mask = (s >= thr)
        tp = int(pos_counts[mask].sum())
        fp = int(neg_counts[mask].sum())
        fn = total_pos - tp
        tn = total_neg - fp

        tpr = tp / total_pos if total_pos else 0.0
        fpr = fp / total_neg if total_neg else 0.0
        prec = tp / (tp + fp) if (tp + fp) else 0.0
        spec = tn / total_neg if total_neg else 0.0
        acc  = (tp + tn) / (total_pos + total_neg) if (total_pos + total_neg) else 0.0
        f1   = (2 * tp) / (2 * tp + fp + fn) if (2 * tp + fp + fn) else 0.0
        j    = tpr - fpr

        rows.append({
            "threshold": float(thr),
            "tp": tp, "fp": fp, "tn": tn, "fn": fn,
            "tpr": tpr, "fpr": fpr, "precision": prec,
            "specificity": spec, "accuracy": acc, "f1": f1, "youdenJ": j,
            "sample_pos": total_pos, "sample_neg": total_neg,
        })
    return rows


def auto_thresholds() -> list[float]:
    return sorted(set(map(float, SCORES_BY_PATTERN.tolist())))


def write_csv(rows: list[dict], out_csv: Path, extra_fields: dict | None = None):
    if not rows:
        return
    keys = list(CSV_FIELDS)
    if extra_fields:
        for k in extra_fields.keys():
            if k not in keys:
                keys.append(k)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        for r in rows:
            if extra_fields:
                rr = {**r, **extra_fields}
            else:
                rr = r
            w.writerow(rr)


def main():
    ap = argparse.ArgumentParser(description="Compute TP/FP/TN/FN and metrics from Parquet via binary pattern aggregation.")
    ap.add_argument("--in-dir", required=True, type=Path, help="Parquet dataset (hive: src_family=...)")
    ap.add_argument("--family", default=None, help="Optional src_family filter")
    ap.add_argument("--threshold", type=float, action="append",
                    help="Additional threshold to evaluate (can be given multiple times). Default: all 8 discrete levels")
    ap.add_argument("--by-family", action="store_true", help="Also emit per-family metrics CSV")
    ap.add_argument("--batch-size", type=int, default=512_000, help="Arrow scanner batch size")
    ap.add_argument("--out-csv", type=Path, default=None,
                    help="Output CSV for global metrics (default: ./_perf_summary.csv)")
    ap.add_argument("--out-csv-families", type=Path, default=None,
                    help="Output CSV for per-family metrics (default:./_perf_by_family.csv)")
    args = ap.parse_args()

    in_dir = args.in_dir
    script_dir = Path(__file__).resolve().parent

    out_csv = args.out_csv or (script_dir / "_perf_summary.csv")
    out_csv_fam = args.out_csv_families or (script_dir / "_perf_by_family.csv")

    counts_global, counts_by_family, totals = aggregate_counts(
        in_dir, family=args.family, batch_size=args.batch_size, by_family=args.by_family
    )

    thrs = auto_thresholds()
    if args.threshold:
        thrs = sorted(set(thrs + args.threshold))

    rows_global = eval_thresholds_from_counts(counts_global, thrs)

    best_idx = int(np.argmax([r["youdenJ"] for r in rows_global])) if rows_global else -1
    if best_idx >= 0:
        best = rows_global[best_idx]
        print(f"[INFO] Best Youden J @ thr={best['threshold']:.6f}  "
              f"(TPR={best['tpr']:.4f}, FPR={best['fpr']:.4f}, F1={best['f1']:.4f})")

    write_csv(rows_global, out_csv, extra_fields={
        "family_filter": args.family or "",
        "n_pos": totals["n_pos"],
        "n_neg": totals["n_neg"],
    })
    print(f"[OK] Global metrics -> {out_csv.resolve()}")

    if args.by_family and counts_by_family:
        rows_fam_all = []
        for fam, cnt in counts_by_family.items():
            fam_rows = eval_thresholds_from_counts(cnt, thrs)
            for r in fam_rows:
                r = r.copy()
                r["src_family"] = fam
                rows_fam_all.append(r)
        fam_fields = ["src_family"] + CSV_FIELDS
        if rows_fam_all:
            with open(out_csv_fam, "w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=fam_fields)
                w.writeheader()
                for r in rows_fam_all:
                    w.writerow({k: r.get(k) for k in fam_fields})
            print(f"[OK] Per-family metrics -> {out_csv_fam.resolve()}")

if __name__ == "__main__":
    main()
