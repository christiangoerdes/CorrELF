import argparse
import json
from pathlib import Path
from typing import Optional, List, Tuple

import numpy as np
import pyarrow.dataset as ds
from sklearn.linear_model import LogisticRegression
from tqdm import tqdm

PARTITION_COL = "src_family"
LABEL_COL     = "label_same_family"

FEATURES = ["PROGRAM_HEADER_VECTOR", "CODE_REGION_LIST", "STRING_MINHASH"]
BIN_COLS = {
    "PROGRAM_HEADER_VECTOR": "bin_program_header",
    "CODE_REGION_LIST":      "bin_code_regions",
    "STRING_MINHASH":        "bin_string_minhash",
}

def _bits_from_pattern(p: int) -> np.ndarray:
    # bit order consistent with encoding below
    b0 = (p >> 0) & 1  # STRING_MINHASH
    b1 = (p >> 1) & 1  # CODE_REGION_LIST
    b2 = (p >> 2) & 1  # PROGRAM_HEADER_VECTOR
    return np.array([b2, b1, b0], dtype=np.float32)

def aggregate_counts(in_dir: Path,
                     families: Optional[List[str]] = None,
                     batch_size: int = 256_000) -> Tuple[np.ndarray, int, int]:

    cols = [PARTITION_COL, LABEL_COL,
            BIN_COLS["STRING_MINHASH"], BIN_COLS["CODE_REGION_LIST"], BIN_COLS["PROGRAM_HEADER_VECTOR"]]
    dset = ds.dataset(str(in_dir), format="parquet", partitioning="hive")
    scanner = dset.scanner(columns=cols, batch_size=batch_size, use_threads=True)

    counts = np.zeros((8, 2), dtype=np.int64)
    total_seen = 0
    total_kept = 0

    pbar = tqdm(desc="Scanning & counting", unit="rows", leave=False)
    for b in scanner.to_batches():
        n = b.num_rows
        total_seen += n
        pbar.update(n)

        fam = b.column(0).to_numpy(zero_copy_only=False).astype(str)
        y   = b.column(1).to_numpy(zero_copy_only=False)

        sm  = b.column(2).to_numpy(zero_copy_only=False)  # STRING_MINHASH (0/1)
        cr  = b.column(3).to_numpy(zero_copy_only=False)  # CODE_REGION_LIST (0/1)
        ph  = b.column(4).to_numpy(zero_copy_only=False)  # PROGRAM_HEADER_VECTOR (0/1)

        # Valid mask
        mask = np.isfinite(y) & np.isfinite(sm) & np.isfinite(cr) & np.isfinite(ph)
        if families:
            mask &= np.isin(fam, families)

        if not np.any(mask):
            continue

        y  = y[mask].astype(np.int8, copy=False)
        sm = sm[mask].astype(np.int8, copy=False)
        cr = cr[mask].astype(np.int8, copy=False)
        ph = ph[mask].astype(np.int8, copy=False)

        pat = ((ph << 2) | (cr << 1) | sm).astype(np.int8, copy=False)

        for lbl in (0, 1):
            sel = (y == lbl)
            if np.any(sel):
                hist = np.bincount(pat[sel], minlength=8)
                counts[:len(hist), lbl] += hist

        total_kept += int(mask.sum())

    pbar.close()
    return counts, total_seen, total_kept

def build_weighted_design(counts: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    X_rows, y_rows, w_rows = [], [], []
    for p in range(8):
        c0, c1 = int(counts[p, 0]), int(counts[p, 1])
        if c0 > 0:

            bits = np.array([(p >> 2) & 1, (p >> 1) & 1, (p >> 0) & 1], dtype=np.float32)

            X_rows.append(bits)
            y_rows.append(0)
            w_rows.append(c0)
        if c1 > 0:
            bits = np.array([(p >> 2) & 1, (p >> 1) & 1, (p >> 0) & 1], dtype=np.float32)
            X_rows.append(bits)
            y_rows.append(1)
            w_rows.append(c1)

    if not X_rows:
        raise RuntimeError("No patterns present after aggregation. Check input filters.")

    X = np.vstack(X_rows).astype(np.float32)
    y = np.asarray(y_rows, dtype=np.int32)
    w = np.asarray(w_rows, dtype=np.float64)
    return X, y, w

def fit_weights_aggregated(X: np.ndarray,
                           y: np.ndarray,
                           w: np.ndarray,
                           max_iter: int = 400,
                           C: float = 1.0,
                           random_state: int = 42) -> Tuple[np.ndarray, dict]:

    clf = LogisticRegression(
        solver="lbfgs",
        penalty="l2",
        C=C,
        class_weight=None,
        max_iter=max_iter,
        random_state=random_state,
        n_jobs=1,
        verbose=0,
    )
    clf.fit(X, y, sample_weight=w)

    coef = np.asarray(clf.coef_[0], dtype=np.float64)
    coef = np.clip(coef, 0.0, None)
    s = coef.sum()
    if s <= 0:
        weights = np.ones_like(coef) / len(coef)
    else:
        weights = coef / s

    extras = {
        "lr_classes_": list(map(int, clf.classes_)),
        "lr_n_iter_": int(np.max(clf.n_iter_)) if np.ndim(clf.n_iter_) else int(clf.n_iter_),
        "solver": "lbfgs",
        "penalty": "l2",
        "C": float(C),
        "used_sample_weight": True,
    }
    return weights.astype(float), extras

def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Fast weight learning via pattern aggregation (no threshold).")
    ap.add_argument("--in-dir", required=True, type=Path,
                    help="Input Parquet dataset (hive partitioned) with bin_* columns")
    ap.add_argument("--out", required=True, type=Path, help="Output JSON model path")
    ap.add_argument("--families", nargs="*", default=None,
                    help="Optional list of src_family partitions to include (default: all)")
    ap.add_argument("--batch-size", type=int, default=256_000, help="Parquet scan batch size")
    ap.add_argument("--max-iter", type=int, default=400,
                    help="Max iterations for LogisticRegression on aggregated data (200–500 typical)")
    ap.add_argument("--C", type=float, default=1.0, help="Inverse regularization strength")
    ap.add_argument("--random-state", type=int, default=42, help="Random seed")
    return ap.parse_args()

def main():
    args = parse_args()

    counts, rows_seen, rows_kept = aggregate_counts(args.in_dir, args.families, args.batch_size)

    X_small, y_small, w_small = build_weighted_design(counts)

    weights, extras = fit_weights_aggregated(
        X_small, y_small, w_small, max_iter=args.max_iter, C=args.C, random_state=args.random_state
    )

    feature_weights = dict(zip(FEATURES, map(float, weights)))

    model = {
        "features": FEATURES,
        "weights_sum_to_1": [feature_weights[f] for f in FEATURES],
        "aggregation_used": True,
        "unique_patterns_present": int(np.count_nonzero(counts.sum(axis=1))),
        "train_rows_seen": int(rows_seen),
        "train_rows_kept": int(rows_kept),
        "positive_rate": float((y_small[w_small>0] == 1).mean()) if len(y_small) else 0.0,
        "bin_columns": {f: BIN_COLS[f] for f in FEATURES},
        "partition_col": PARTITION_COL,
        "label_col": LABEL_COL,
        "families_filter": args.families or [],
        "lr_details": extras,
        "weights_non_negative": True,
        "weights_sum_constraint": "sum_i w_i = 1",
        "score_formula": "weighted_score = sum_i (w_i * bin_i)",
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

    print("[OK] Weights learned (sum=1) with pattern aggregation")
    print(json.dumps({
        "features": model["features"],
        "weights_sum_to_1": model["weights_sum_to_1"],
        "unique_patterns_present": model["unique_patterns_present"],
        "train_rows_seen": model["train_rows_seen"],
        "lr_n_iter_": model["lr_details"]["lr_n_iter_"],
    }, indent=2))

if __name__ == "__main__":
    main()
