import argparse, math
from pathlib import Path
import numpy as np
import pyarrow.dataset as ds
from sklearn.metrics import roc_curve, auc
import matplotlib.pyplot as plt

REPS = {
    "rep_string_minhash":    "String-MinHash",
    "rep_code_regions":      "Code-Regionen-Liste",
    "rep_program_header":    "Program-Header-Vektor",
    "rep_elf_header":        "ELF-Header-Vektor",
    "rep_section_sizes":     "Sektionsgrößen-Vektor",
}
LABEL_COL, FAMILY_COL = "label_same_family", "src_family"
RECALL_TARGETS = [0.95, 0.99]


def scan(dataset: ds.Dataset,
         rep_col: str,
         family: str | None,
         neg_pos_ratio: float,
         max_rows: int | None,
         seed: int) -> tuple[np.ndarray, np.ndarray]:
    import random
    rng = random.Random(seed)
    filt = (ds.field(FAMILY_COL) == family) if family else None
    scanner = dataset.scanner(columns=[rep_col, LABEL_COL], filter=filt, batch_size=64_000, use_threads=True)

    xs, ys = [], []
    pos = neg = 0
    for b in scanner.to_batches():
        x = b.column(0).to_numpy(zero_copy_only=False)
        y = b.column(1).to_numpy(zero_copy_only=False)
        m = ~np.isnan(x)
        x, y = x[m], y[m]

        for s, yy in zip(x, y):
            if yy == 1:
                ys.append(1); xs.append(float(s)); pos += 1
            else:
                keep = (neg_pos_ratio <= 0) or (neg < pos * neg_pos_ratio)
                if keep:
                    ys.append(0); xs.append(float(s)); neg += 1

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
    return dict(threshold=float(thr), tp=tp, fp=fp, tn=tn, fn=fn, tpr=tpr, fpr=fpr, precision=prec)


def pick_thresholds(y: np.ndarray, x: np.ndarray):
    fpr, tpr, thr = roc_curve(y, x)
    m = ~np.isinf(thr)
    fpr, tpr, thr = fpr[m], tpr[m], thr[m]

    j = tpr - fpr
    j_idx = int(np.argmax(j))
    best_j = metrics_at_threshold(y, x, float(thr[j_idx]))
    best_j["criterion"] = "youdenJ"
    best_j["youden_recall"] = best_j["tpr"]

    rec_thrs = []
    for target in RECALL_TARGETS:
        idx = np.where(tpr >= target)[0]
        if idx.size == 0:
            rec_thrs.append(dict(criterion=f"recall>={target}", threshold=float("nan"),
                                 tp=None, fp=None, tn=None, fn=None, tpr=float("nan"),
                                 fpr=float("nan"), precision=float("nan")))
            continue
        sub = idx[np.argmin(fpr[idx])]
        mtr = metrics_at_threshold(y, x, float(thr[sub]))
        mtr["criterion"] = f"recall>={target}"
        rec_thrs.append(mtr)

    return (fpr, tpr, thr), best_j, rec_thrs


def plot_rocs(curves, out_png, style, dpi):
    plt.style.use(style)
    fig, ax = plt.subplots(figsize=(9,6), dpi=dpi)
    ax.plot([0,1], [0,1], "--", lw=1.0, color="gray", label="Chance", zorder=1)

    for label, (fpr, tpr, auc_val, best_j) in sorted(curves.items(), key=lambda kv: kv[1][2], reverse=False):
        ax.plot(fpr, tpr, lw=2.2, alpha=0.9, label=f"{label} (AUC={auc_val:.4f})", zorder=3)
        ax.scatter([best_j["fpr"]], [best_j["tpr"]], s=30, zorder=4)
        ax.annotate("J", (best_j["fpr"], best_j["tpr"]), xytext=(6,-6),
                    textcoords="offset points", fontsize=8, zorder=4)

    ax.set_xlabel("FPR")
    ax.set_ylabel("TPR")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(out_png)



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True, type=Path)
    ap.add_argument("--family", default=None)
    ap.add_argument("--neg-pos-ratio", type=float, default=5.0, help="Negatives per positive (0 = keep all)")
    ap.add_argument("--max-rows", type=int, default=1_500_000, help="Approx upper bound on rows (0 = no limit)")
    ap.add_argument("--full", action="store_true", help="Use entire dataset (sets --neg-pos-ratio 0 and --max-rows 0)")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out-csv", type=Path, default=Path("roc_thresholds_summary.csv"))
    ap.add_argument("--out-plot", type=Path, default=Path("roc_curves.png"))
    ap.add_argument("--plot-style", default="seaborn-v0_8-whitegrid")
    ap.add_argument("--plot-dpi", type=int, default=140)
    args = ap.parse_args()

    if args.full:
        args.neg_pos_ratio = 0.0
        args.max_rows = 0

    max_rows = None if not args.max_rows or args.max_rows <= 0 else args.max_rows

    dset = ds.dataset(str(args.data_dir), format="parquet", partitioning="hive")

    rows = []
    curves = {}

    for col, name in REPS.items():
        print(f"[INFO] {name} …")
        y, x = scan(dset, col, args.family, args.neg_pos_ratio, max_rows, args.seed)
        n_pos, n_neg = int((y == 1).sum()), int((y == 0).sum())
        print(f"  sample: n={len(y)} pos={n_pos} neg={n_neg}")

        (fpr, tpr, thr), best_j, recs = pick_thresholds(y, x)
        A = auc(fpr, tpr)
        print(f"  AUC={A:.6f}  YoudenJ thr={best_j['threshold']:.6g} "
              f"(TPR={best_j['tpr']:.3f}, FPR={best_j['fpr']:.3f}, P={best_j['precision']:.3f}) "
              f"recall@J={best_j['youden_recall']:.3f}")

        for r in recs:
            if isinstance(r["tpr"], float) and math.isnan(r["tpr"]):
                print(f"  {r['criterion']}: not reachable")
            else:
                print(f"  {r['criterion']}: thr={r['threshold']:.6g} "
                      f"(TPR={r['tpr']:.3f}, FPR={r['fpr']:.3f}, P={r['precision']:.3f})")

        rows.append({
            "representation": name,
            "auc": A,
            "youden_thr": best_j["threshold"],
            "youden_tpr": best_j["tpr"],
            "youden_fpr": best_j["fpr"],
            "youden_prec": best_j["precision"],
            "youden_recall": best_j["youden_recall"],
            **{f"rec{int(t*100)}_thr": recs[i]["threshold"] for i, t in enumerate(RECALL_TARGETS)},
            **{f"rec{int(t*100)}_tpr": recs[i]["tpr"] for i, t in enumerate(RECALL_TARGETS)},
            **{f"rec{int(t*100)}_fpr": recs[i]["fpr"] for i, t in enumerate(RECALL_TARGETS)},
            **{f"rec{int(t*100)}_prec": recs[i]["precision"] for i, t in enumerate(RECALL_TARGETS)},
            "sample_pos": n_pos,
            "sample_neg": n_neg,
        })

        curves[name] = (fpr, tpr, A, best_j)

    import csv
    keys = list(rows[0].keys()) if rows else []
    with open(args.out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys); w.writeheader(); w.writerows(rows)
    print(f"[OK] Summary written: {args.out_csv.resolve()}")

    plot_rocs(curves, args.out_plot, style=args.plot_style, dpi=args.plot_dpi)


if __name__ == "__main__":
    main()
