import argparse, csv, math
from pathlib import Path
import numpy as np
import pyarrow.dataset as ds
import matplotlib.pyplot as plt
from sklearn.metrics import roc_curve, auc

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

def scan_weighted_scores(dataset: ds.Dataset,
                         family: str | None,
                         batch_size: int = 128_000) -> tuple[np.ndarray, np.ndarray]:
    filt = (ds.field(FAMILY_COL) == family) if family else None
    cols = [COL_SM, COL_CR, COL_PH, LABEL_COL]
    sc = dataset.scanner(columns=cols, filter=filt, batch_size=batch_size, use_threads=True)

    xs, ys = [], []
    for b in sc.to_batches():
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

        ys.extend(map(int, y))
        xs.extend(map(float, s))

    return np.asarray(ys, np.int8), np.asarray(xs, np.float32)


def read_agg_csv(perf_csv: Path) -> tuple[np.ndarray, np.ndarray]:
    fprs, tprs = [], []
    with open(perf_csv, "r", encoding="utf-8") as f:
        r = csv.DictReader(f)
        for row in r:
            if "fpr" in row and "tpr" in row:
                try:
                    fp = float(row["fpr"]); tp = float(row["tpr"])
                except Exception:
                    continue
                if math.isfinite(fp) and math.isfinite(tp):
                    fprs.append(fp); tprs.append(tp)
    if not fprs:
        raise SystemExit(f"No usable rows in CSV: {perf_csv}")
    pairs = sorted(zip(fprs, tprs))
    xs, ys = [], []
    for fp, tp in pairs:
        if xs and abs(fp - xs[-1]) < 1e-12:
            ys[-1] = max(ys[-1], tp)
        else:
            xs.append(fp); ys.append(tp)

    if xs[0] > 0.0:
        xs.insert(0, 0.0); ys.insert(0, 0.0)
    if xs[-1] < 1.0:
        xs.append(1.0); ys.append(1.0)
    return np.array(xs, float), np.array(ys, float)


def youden_from_curve(fpr: np.ndarray, tpr: np.ndarray) -> tuple[float,float,int]:
    j = tpr - fpr
    idx = int(np.argmax(j))
    return float(fpr[idx]), float(tpr[idx]), idx


def plot_both(fpr_csv, tpr_csv, fpr_raw, tpr_raw, out_png: Path, style: str, dpi: int):
    import matplotlib.pyplot as plt
    from sklearn.metrics import auc

    plt.style.use(style)
    fig, ax = plt.subplots(figsize=(9, 6), dpi=dpi)
    ax.plot([0, 1], [0, 1], "--", lw=1.0, color="gray", label="Chance", zorder=1)

    color_csv = "C1"
    color_raw = "C0"

    auc_csv = auc(fpr_csv, tpr_csv)
    auc_raw = auc(fpr_raw, tpr_raw)

    def youden_from_curve(fpr, tpr):
        j = tpr - fpr
        i = int(np.argmax(j))
        return float(fpr[i]), float(tpr[i])

    jx_csv, jy_csv = youden_from_curve(fpr_csv, tpr_csv)
    jx_raw, jy_raw = youden_from_curve(fpr_raw, tpr_raw)

    ax.plot(fpr_csv, tpr_csv, lw=2.2, alpha=0.95, color=color_csv,
            label=f"Test (AUC={auc_csv:.4f})", zorder=4)
    ax.plot(fpr_raw, tpr_raw, lw=2.2, alpha=0.95, color=color_raw,
            label=f"Train (AUC={auc_raw:.4f})", zorder=5)

    ax.scatter([jx_csv], [jy_csv], s=36, color=color_csv, zorder=6)
    ax.annotate("J", (jx_csv, jy_csv), xytext=(6, -6),
                textcoords="offset points", fontsize=8, color=color_csv)
    ax.scatter([jx_raw], [jy_raw], s=36, color=color_raw, zorder=6)
    ax.annotate("J", (jx_raw, jy_raw), xytext=(6, 8),
                textcoords="offset points", fontsize=8, color=color_raw)

    ax.set_xlabel("FPR")
    ax.set_ylabel("TPR")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(out_png)


def main():
    ap = argparse.ArgumentParser(description="Plot ROC from aggregated CSV and overlay old direct ROC.")
    script_dir = Path(__file__).resolve().parent
    ap.add_argument("--perf-csv", type=Path, default=script_dir / "_perf_summary.csv",
                    help="CSV from perf_from_parquet.py")
    ap.add_argument("--data-dir", type=Path, required=True,
                    help="Parquet dataset (hive: src_family=...) with rep_* columns")
    ap.add_argument("--family", default=None, help="Optional src_family filter")
    ap.add_argument("--out-png", type=Path, default=Path("roc_compare_weighted.png"))
    ap.add_argument("--plot-style", default="seaborn-v0_8-whitegrid")
    ap.add_argument("--plot-dpi", type=int, default=140)
    args = ap.parse_args()

    fpr_csv, tpr_csv = read_agg_csv(args.perf_csv)

    dset = ds.dataset(str(args.data_dir), format="parquet", partitioning="hive")
    y, x = scan_weighted_scores(dset, args.family)
    fpr_raw, tpr_raw, thr = roc_curve(y, x)
    m = ~np.isinf(thr)
    fpr_raw, tpr_raw = fpr_raw[m], tpr_raw[m]

    plot_both(fpr_csv, tpr_csv, fpr_raw, tpr_raw, args.out_png, args.plot_style, args.plot_dpi)
    print(f"[OK] ROC comparison written: {args.out_png.resolve()}")

if __name__ == "__main__":
    main()
