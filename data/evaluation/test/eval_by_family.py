import argparse
import csv
import json
import math
from pathlib import Path
from typing import Dict, List

from tqdm import tqdm

DEFAULT_THR = 0.6170

# --- Families (prefix before '___' in secondFileName) ---
DEFAULT_FAMILIES: List[str] = [
    "busybox",
    "libcrypto.so",
    "libgcc_s.so",
    "uci",
    "ubus",
    "brctl",
    "crc-ccitt",
    "nf_conntrack",
    "pppd",
    "openssl",
    "ip",
    "iwinfo",
    "ppp_generic",
    "nf_nat",
    "pppoe",
]

CSV_FIELDS = [
    "src_family", "threshold",
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


def metrics_from_counts(tp: int, fp: int, tn: int, fn: int) -> dict:
    pos = tp + fn
    neg = tn + fp
    tpr = tp / pos if pos else 0.0
    fpr = fp / neg if neg else 0.0
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    spec = tn / neg if neg else 0.0
    acc  = (tp + tn) / (pos + neg) if (pos + neg) else 0.0
    f1   = (2 * tp) / (2 * tp + fp + fn) if (2 * tp + fp + fn) else 0.0
    j    = tpr - fpr
    return dict(tpr=tpr, fpr=fpr, precision=prec, specificity=spec, accuracy=acc, f1=f1, youdenJ=j,
                sample_pos=pos, sample_neg=neg)

def evaluate_from_json_dir(json_dir: Path,
                           families: List[str],
                           threshold: float,
                           progress: bool = True) -> List[dict]:
    fam_set = set(f.strip().lower() for f in families if f.strip())
    counts: Dict[str, Dict[str, int]] = {f: dict(tp=0, fp=0, tn=0, fn=0, rows=0) for f in fam_set}

    files = sorted([p for p in json_dir.glob("*.json") if p.is_file()])
    it = tqdm(files, desc="Scanning JSONs", unit="file") if progress else files

    for jf in it:
        try:
            data = json.load(open(jf, "r", encoding="utf-8"))
            if not isinstance(data, list) or not data:
                continue
            anchor = data[0].get("secondFileName", "")
            src_family = family_from_name(anchor)
            if src_family not in fam_set:
                continue

            c = counts[src_family]
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
                    c["tp"] += 1
                elif pred == 1 and y == 0:
                    c["fp"] += 1
                elif pred == 0 and y == 0:
                    c["tn"] += 1
                else:
                    c["fn"] += 1
                c["rows"] += 1

        except Exception:
            continue

    rows: List[dict] = []
    tot = dict(tp=0, fp=0, tn=0, fn=0, rows=0)
    for fam in families:
        f = fam.strip().lower()
        if not f:
            continue
        c = counts.get(f, dict(tp=0, fp=0, tn=0, fn=0, rows=0))
        m = metrics_from_counts(c["tp"], c["fp"], c["tn"], c["fn"])
        row = {
            "src_family": f,
            "threshold": threshold,
            **{k: c[k] for k in ("tp", "fp", "tn", "fn")},
            **m,
            "rows": c["rows"],
        }
        rows.append(row)
        for k in ("tp", "fp", "tn", "fn", "rows"):
            tot[k] += c[k]

    m_tot = metrics_from_counts(tot["tp"], tot["fp"], tot["tn"], tot["fn"])
    rows.append({
        "src_family": "TOTAL",
        "threshold": threshold,
        **{k: tot[k] for k in ("tp", "fp", "tn", "fn")},
        **m_tot,
        "rows": tot["rows"],
    })
    return rows


def write_csv(rows: List[dict], out_csv: Path):
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k) for k in CSV_FIELDS})


def main():
    ap = argparse.ArgumentParser(description="Per-family metrics @ fixed threshold from JSON folder (score_weighted used directly).")
    ap.add_argument("--json-dir", required=True, type=Path, help="Folder with *.json files (each: array of comparisons)")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THR, help="Operating threshold (default: 0.6170)")
    ap.add_argument("--families", type=str, nargs="*", default=DEFAULT_FAMILIES, help="Anchor families to include (prefix before '___')")
    ap.add_argument("--out-csv", type=Path, default=None, help="Output CSV (default: _perf_thr_from_json.csv next to this script)")
    args = ap.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_csv = args.out_csv or (script_dir / "_perf_by_family_json.csv")

    rows = evaluate_from_json_dir(args.json_dir, args.families, args.threshold, progress=True)
    write_csv(rows, out_csv)
    print(f"[OK] Wrote metrics -> {out_csv.resolve()}")

if __name__ == "__main__":
    main()
