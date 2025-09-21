import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Set, Tuple
from tqdm import tqdm

DEFAULT_THR = 0.6170

DEFAULT_FAMILIES: List[str] = [
    "busybox","libcrypto.so","libgcc_s.so","uci","ubus","brctl","crc-ccitt",
    "nf_conntrack","pppd","openssl","ip","iwinfo","ppp_generic","nf_nat","pppoe",
]

PAIR_FIELDS = ["src_family", "dst_family", "fp_count", "threshold"]

def family_from_name(name: str) -> str:
    s = (name or "").strip().lower()
    i = s.find("___")
    return s[:i] if i > 0 else ""

def collect_fp_pairs(json_dir: Path,
                     families_filter: Set[str],
                     threshold: float,
                     include_unknown: bool,
                     progress: bool = True) -> Tuple[Counter, Set[str], Set[str]]:

    pairs = Counter()
    src_set, dst_set = set(), set()

    files = sorted(p for p in json_dir.glob("*.json") if p.is_file())
    it = tqdm(files, desc="Scanning JSONs", unit="file") if progress else files

    for jf in it:
        try:
            data = json.load(open(jf, "r", encoding="utf-8"))
            if not isinstance(data, list) or not data:
                continue

            src_family = family_from_name((data[0] or {}).get("secondFileName", ""))
            if not src_family:
                continue
            if families_filter and src_family not in families_filter:
                continue

            for rec in data:
                dst_family = family_from_name(rec.get("fileName", ""))
                if not dst_family:
                    if not include_unknown:
                        continue
                    dst_family = "__unknown__"

                s = rec.get("score_weighted", None)
                try:
                    s = float(s)
                except Exception:
                    continue
                if not math.isfinite(s):
                    continue

                pred_pos = s >= threshold
                is_pos = (dst_family == src_family)
                if pred_pos and not is_pos:
                    pairs[(src_family, dst_family)] += 1
                    src_set.add(src_family)
                    dst_set.add(dst_family)
        except Exception:
            continue

    return pairs, src_set, dst_set

def write_pairs_csv(pairs: Counter, out_csv: Path, threshold: float):
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=PAIR_FIELDS)
        w.writeheader()
        for (src, dst), cnt in sorted(pairs.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1])):
            w.writerow({
                "src_family": src,
                "dst_family": dst,
                "fp_count": cnt,
                "threshold": threshold,
            })

def write_matrix_csv(pairs: Counter, srcs: Set[str], dsts: Set[str], out_csv: Path):
    src_list = sorted(srcs)
    dst_list = sorted(dsts)
    # Build matrix map
    mat: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for (src, dst), cnt in pairs.items():
        mat[dst][src] = cnt

    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["dst \\ src"] + src_list)
        for dst in dst_list:
            row = [dst] + [mat[dst].get(src, 0) for src in src_list]
            writer.writerow(row)

def main():
    ap = argparse.ArgumentParser(description="List FP pairs (src→dst) and optional matrix from JSON folder.")
    ap.add_argument("--json-dir", required=True, type=Path, help="Folder with *.json files")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THR, help="Operating threshold (default: 0.6170)")
    ap.add_argument("--families", type=str, nargs="*", default=DEFAULT_FAMILIES,
                    help="Anchor families to include (prefix before '___'). Use --all-anchors to include all found.")
    ap.add_argument("--all-anchors", action="store_true",
                    help="Ignore --families filter and include all anchors found.")
    ap.add_argument("--include-unknown", action="store_true",
                    help="Count records with unknown dst family as '__unknown__'.")
    ap.add_argument("--out-pairs-csv", type=Path, default=None,
                    help="CSV of pairs sorted by FP count (default: _fp_pairs_json.csv next to script)")
    ap.add_argument("--out-matrix-csv", type=Path, default=None,
                    help="Optional pivot matrix CSV (dst rows × src columns)")
    ap.add_argument("--top", type=int, default=25, help="Print top-N pairs to stdout")
    args = ap.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_pairs = args.out_pairs_csv or (script_dir / "_fp_pairs_json.csv")

    fam_filter = set() if args.all_anchors else {f.strip().lower() for f in args.families if f.strip()}

    pairs, srcs, dsts = collect_fp_pairs(
        args.json_dir, fam_filter, args.threshold, include_unknown=args.include_unknown, progress=True
    )
    write_pairs_csv(pairs, out_pairs, args.threshold)

    if args.out_matrix_csv:
        write_matrix_csv(pairs, srcs, dsts, args.out_matrix_csv)

    top_n = sorted(pairs.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1]))[:max(args.top, 0)]
    print(f"Top {len(top_n)} FP pairs (threshold={args.threshold}):")
    for (src, dst), c in top_n:
        print(f"{src:20s} -> {dst:20s}  {c}")
    print(f"[OK] Wrote pairs -> {out_pairs.resolve()}")
    if args.out_matrix_csv:
        print(f"[OK] Wrote matrix -> {Path(args.out_matrix_csv).resolve()}")

if __name__ == "__main__":
    main()
