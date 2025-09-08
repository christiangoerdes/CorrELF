import argparse
import csv
import math
from pathlib import Path

import numpy as np
import pyarrow.dataset as ds
from sklearn.metrics import roc_curve, auc
import matplotlib.pyplot as plt

FAMILY_COL = "src_family"
LABEL_COL  = "label_same_family"
COL_SM     = "rep_string_minhash"
COL_CR     = "rep_code_regions"
COL_PH     = "rep_program_header"

TAU_SM = 0.146484375
TAU_CR = 0.3988839387893677
TAU_PH = 0.8291192054748535

W_SM = 0.6170
W_PH = 0.0884
W_CR = 0.2946

RECALL_TARGETS = [0.95, 0.99]


def scan_weighted_scores(dataset: ds.Dataset,
                         family: str | None,
                         neg_pos_ratio: float,
                         max_rows: int | None,
                         batch_size: int = 128_000) -> tuple[np.ndarray, np.ndarray]:
    filt = (ds.field(FAMILY_COL) == family) if family else None
    cols = [COL_SM, COL_CR, COL_PH, LABEL_COL]
    scanner = dataset.scanner(columns=cols, filter=filt, batch_size=batch_size, use_threads=True)

    xs, ys = [], []
    pos = neg = 0
    for b in scanner.to_batches():
        sm = b.column(0).to_numpy(zero_copy_only=False)
        cr = b.column(1).to_numpy(zero_copy_only=False)
        ph = b.column(2).to_numpy(zero_copy_only=False)
        y  = b.column(3).to_numpy(zero_copy_only=False)

        m = np.isfinite(sm) & np.isfinite(cr) & np.isfinite(ph) & np.isfinite(y)
        if not np.any(m):
            continue

        sm = sm[m].astype(np.float32, copy=False)
        cr = cr[m].astype(np.float32, copy=False)
        ph = ph[m].astype(np.float32, copy=False)
        y  = y[m].astype(np.int8,   copy=False)

        b_sm = (sm >= TAU_SM).astype(np.int8, copy=False)
        b_cr = (cr >= TAU_CR).astype(np.int8, copy=False)
        b_ph = (ph >= TAU_PH).astype(np.int8, copy=False)

        s = (W_SM * b_sm + W_CR * b_cr + W_PH * b_ph).astype(np.float32, copy=False)

        if neg_pos_ratio > 0:
            for si, yi in zip(s, y):
                if yi == 1:
                    ys.append(1); xs.append(float(si)); pos += 1
                else:
                    keep = neg < pos * neg_pos_ratio
                    if keep:
                        ys.append(0); xs.append(float(si)); neg += 1
        else:
            ys.extend(map(int, y))
            xs.extend(map(float, s))

        if max_rows and len(ys) >= max_rows:
            break

    return np.asarray(ys, np.int8), np.asarray(xs, np.float32)

def metrics_at_threshold(y: np.ndarray, x: np.ndarray, thr: float) -> dict:
    pred = x >= thr
    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    tn = int(((pred == 0) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    pos = tp + fn; neg = tn + fp
    tpr = tp / pos if pos else 0.0
    fpr = fp / neg if neg else 0.0
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    return dict(threshold=float(thr), tp=tp, fp=fp, tn=tn, fn=fn,
                tpr=tpr, fpr=fpr, precision=prec)


def pick_thresholds(y: np.ndarray, x: np.ndarray):
    fpr, tpr, thr = roc_curve(y, x)
    m = ~np.isinf(thr)
    fpr, tpr, thr = fpr[m], tpr[m], thr[m]

    # Youden's J
    j = tpr - fpr
    j_idx = int(np.argmax(j))
    best_j = metrics_at_threshold(y, x, float(thr[j_idx]))
    best_j["criterion"] = "youdenJ"

    rec_thrs = []
    for target in RECALL_TARGETS:
        idx = np.where(tpr >= target)[0]
        if idx.size == 0:
            rec_thrs.append(dict(criterion=f"recall>={target}", threshold=float("nan"),
                                 tp=None, fp=None, tn=None, fn=None,
                                 tpr=float("nan"), fpr=float("nan"), precision=float("nan")))
            continue
        sub = idx[np.argmin(fpr[idx])]
        mtr = metrics_at_threshold(y, x, float(thr[sub]))
        mtr["criterion"] = f"recall>={target}"
        rec_thrs.append(mtr)
    return (fpr, tpr, thr), best_j, rec_thrs


def plot_roc(fpr, tpr, best_j: dict, auc_val: float, out_png: Path, style: str, dpi: int):
    plt.style.use(style)
    fig, ax = plt.subplots(figsize=(9, 6), dpi=dpi)
    ax.plot([0, 1], [0, 1], "--", lw=1.0, color="gray", label="Chance", zorder=1)
    ax.plot(fpr, tpr, lw=2.2, alpha=0.95, label=f"Gewichteter Score (AUC={auc_val:.4f})", zorder=3)
    ax.scatter([best_j["fpr"]], [best_j["tpr"]], s=36, zorder=4)
    ax.annotate("J", (best_j["fpr"], best_j["tpr"]), xytext=(6, -6),
                textcoords="offset points", fontsize=8, zorder=4)
    ax.set_xlabel("FPR")
    ax.set_ylabel("TPR")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(out_png)


def main():
    ap = argparse.ArgumentParser(description="Final threshold (Youden's J) on weighted score from raw rep columns")
    ap.add_argument("--data-dir", required=True, type=Path, help="Parquet dataset (hive: src_family=...)")
    ap.add_argument("--family", default=None, help="Optional src_family filter")
    ap.add_argument("--neg-pos-ratio", type=float, default=0.0, help="Negatives per positive (0 = keep all)")
    ap.add_argument("--max-rows", type=int, default=0, help="Row cap (0 = no limit)")
    ap.add_argument("--batch-size", type=int, default=128_000)
    ap.add_argument("--out-csv", type=Path, default=Path("roc_final_weighted_summary.csv"))
    ap.add_argument("--out-plot", type=Path, default=Path("roc_final_weighted.png"))
    ap.add_argument("--plot-style", default="seaborn-v0_8-whitegrid")
    ap.add_argument("--plot-dpi", type=int, default=140)
    args = ap.parse_args()

    dset = ds.dataset(str(args.data_dir), format="parquet", partitioning="hive")

    print("[INFO] Scanning and scoring with strict mask (all reps finite)…")
    y, x = scan_weighted_scores(
        dset,
        family=args.family,
        neg_pos_ratio=args.neg_pos_ratio,
        max_rows=(None if args.max_rows <= 0 else args.max_rows),
        batch_size=args.batch_size,
    )
    n_pos, n_neg = int((y == 1).sum()), int((y == 0).sum())
    print(f"  sample: n={len(y)} pos={n_pos} neg={n_neg}")

    (fpr, tpr, thr), best_j, recs = pick_thresholds(y, x)
    A = auc(fpr, tpr)
    print(f"  AUC={A:.6f}  YoudenJ thr={best_j['threshold']:.6g} "
          f"(TPR={best_j['tpr']:.3f}, FPR={best_j['fpr']:.3f}, P={best_j['precision']:.3f})")

    for r in recs:
        if isinstance(r["tpr"], float) and math.isnan(r["tpr"]):
            print(f"  {r['criterion']}: not reachable")
        else:
            print(f"  {r['criterion']}: thr={r['threshold']:.6g} "
                  f"(TPR={r['tpr']:.3f}, FPR={r['fpr']:.3f}, P={r['precision']:.3f})")

    row = {
        "representation": "FinalWeighted",
        "weights": {"SM": W_SM, "CR": W_CR, "PH": W_PH},
        "taus": {"SM": TAU_SM, "CR": TAU_CR, "PH": TAU_PH},
        "auc": A,
        "youden_thr": best_j["threshold"],
        "youden_tpr": best_j["tpr"],
        "youden_fpr": best_j["fpr"],
        "youden_prec": best_j["precision"],
        **{f"rec{int(t*100)}_thr": recs[i]["threshold"] for i, t in enumerate(RECALL_TARGETS)},
        **{f"rec{int(t*100)}_tpr": recs[i]["tpr"] for i, t in enumerate(RECALL_TARGETS)},
        **{f"rec{int(t*100)}_fpr": recs[i]["fpr"] for i, t in enumerate(RECALL_TARGETS)},
        **{f"rec{int(t*100)}_prec": recs[i]["precision"] for i, t in enumerate(RECALL_TARGETS)},
        "sample_pos": n_pos,
        "sample_neg": n_neg,
        "family_filter": args.family or "",
        "mask_policy": "finite on all three reps",
        "neg_pos_ratio": args.neg_pos_ratio,
        "max_rows": args.max_rows,
    }
    with open(args.out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(row.keys()))
        w.writeheader(); w.writerow(row)
    print(f"[OK] Summary written: {args.out_csv.resolve()}")

    plot_roc(fpr, tpr, best_j, A, args.out_plot, style=args.plot_style, dpi=args.plot_dpi)
    print(f"[OK] Plot written: {args.out_plot.resolve()}")


if __name__ == "__main__":
    main()
