import argparse
import csv
import json
import math
from collections import Counter, defaultdict
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

CSV_FIELDS = ["src_family", "dst_family", "fp_count", "threshold"]


def family_from_name(name: str) -> str:
    s = (name or "").strip().lower()
    i = s.find("___")
    return s[:i] if i > 0 else ""


def collect_fp_families(json_dir: Path,
                        families: List[str],
                        threshold: float,
                        progress: bool = True) -> Dict[str, Counter]:
    fam_set = {f.strip().lower() for f in families if f.strip()}
    fp_counts: Dict[str, Counter] = defaultdict(Counter)

    files = sorted([p for p in json_dir.glob("*.json") if p.is_file()])
    it = tqdm(files, desc="Scanning JSONs", unit="file") if progress else files

    for jf in it:
        try:
            with open(jf, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            if not isinstance(data, list) or not data:
                continue

            anchor = data[0].get("secondFileName", "")
            src_family = family_from_name(anchor)
            if src_family not in fam_set:
                continue

            cnt = fp_counts[src_family]
            for rec in data:
                dst_family = family_from_name(rec.get("fileName", ""))
                dst_family_norm = dst_family if dst_family else "__unknown__"

                s = rec.get("score_weighted", None)
                try:
                    s = float(s)
                except Exception:
                    continue
                if not math.isfinite(s):
                    continue

                pred_pos = s >= threshold
                is_pos = (dst_family and dst_family == src_family)
                if pred_pos and not is_pos:
                    cnt[dst_family_norm] += 1

        except Exception:
            continue

    return fp_counts


def write_fp_csv(fp_counts: Dict[str, Counter], out_csv: Path, threshold: float):
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        for src_family in sorted(fp_counts.keys()):
            items = sorted(fp_counts[src_family].items(), key=lambda kv: (-kv[1], kv[0]))
            for dst_family, c in items:
                w.writerow({
                    "src_family": src_family,
                    "dst_family": dst_family,
                    "fp_count": c,
                    "threshold": threshold,
                })


def main():
    ap = argparse.ArgumentParser(description="Collect FP target families per anchor family from JSON folder.")
    ap.add_argument("--json-dir", required=True, type=Path, help="Folder with *.json files (each: array of comparisons)")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THR, help="Operating threshold (default: 0.6170)")
    ap.add_argument("--families", type=str, nargs="*", default=DEFAULT_FAMILIES,
                    help="Anchor families to include (prefix before '___' in secondFileName)")
    ap.add_argument("--out-csv", type=Path, default=None,
                    help="Output CSV (default: _fp_by_family_json.csv next to this script)")
    args = ap.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_csv = args.out_csv or (script_dir / "_fp_by_family_json.csv")

    fp_counts = collect_fp_families(args.json_dir, args.families, args.threshold, progress=True)
    write_fp_csv(fp_counts, out_csv, args.threshold)
    print(f"[OK] Wrote FP families -> {out_csv.resolve()}")


if __name__ == "__main__":
    main()
