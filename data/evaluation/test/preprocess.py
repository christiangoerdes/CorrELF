import argparse
import json
import math
import glob
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.dataset as ds
from tqdm import tqdm


TAU = {
    "STRING_MINHASH":        0.146484375,
    "CODE_REGION_LIST":      0.3988839387893677,
    "PROGRAM_HEADER_VECTOR": 0.8291192054748535,
}
W = {
    "STRING_MINHASH":        0.6170,
    "CODE_REGION_LIST":      0.2946,
    "PROGRAM_HEADER_VECTOR": 0.0884,
}

PARTITION_COL = "src_family"
DST_FAMILY_COL = "dst_family"
LABEL_COL = "label_same_family"

OUT_FIELDS = [
    pa.field(PARTITION_COL,         pa.string()),
    pa.field(DST_FAMILY_COL,        pa.string()),
    pa.field("anchor",              pa.string()),
    pa.field("candidate",           pa.string()),
    pa.field(LABEL_COL,             pa.int8()),

    pa.field("rep_string_minhash",  pa.float32()),
    pa.field("rep_code_regions",    pa.float32()),
    pa.field("rep_program_header",  pa.float32()),
    pa.field("rep_elf_header",      pa.float32()),
    pa.field("rep_section_sizes",   pa.float32()),

    pa.field("bin_string_minhash",  pa.int8()),
    pa.field("bin_code_regions",    pa.int8()),
    pa.field("bin_program_header",  pa.int8()),

    pa.field("score_weighted",      pa.float32()),
]
OUT_SCHEMA = pa.schema(OUT_FIELDS)

def extract_family(name: str) -> str:
    if not isinstance(name, str):
        return ""
    s = name.strip().lower()
    if "___" in s and len(s.split("___", 1)[0]) > 0:
        return s.split("___", 1)[0]
    return ""

def load_families_csv(path: Path) -> dict[str, str]:
    mapping: dict[str, str] = {}
    if not path:
        return mapping
    import csv
    with open(path, "r", encoding="utf-8") as f:
        r = csv.DictReader(f)
        for row in r:
            fn = (row.get("filename") or "").strip()
            fam = (row.get("family") or "").strip().lower()
            if fn:
                mapping[fn] = fam
    return mapping

def to_float(x) -> float:
    try:
        if x is None or (isinstance(x, float) and math.isnan(x)):
            return float("nan")
        return float(x)
    except Exception:
        return float("nan")

def to_int01(x) -> int:
    try:
        return 1 if int(x) == 1 else 0
    except Exception:
        return 0

def process(input_dir: Path,
            out_dir: Path,
            families_csv: Path | None,
            drop_self_matches: bool,
            csv_sample: int,
            batch_rows: int = 200_000) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    fam_map = load_families_csv(families_csv) if families_csv else {}

    fmt = ds.ParquetFileFormat()
    wopts = fmt.make_write_options(compression="snappy")

    files = sorted(glob.glob(str(input_dir / "*.json")))
    if not files:
        raise SystemExit(f"No JSON files found in: {input_dir}")

    total_in = total_out = 0
    sample_rows = []
    batch_acc = {k: [] for k in [f.name for f in OUT_FIELDS]}

    def flush_batch():
        nonlocal total_out, batch_acc
        if not batch_acc["anchor"]:
            return
        table = pa.table({k: batch_acc[k] for k in batch_acc}, schema=OUT_SCHEMA)
        ds.write_dataset(
            table,
            base_dir=str(out_dir),
            format=fmt,
            partitioning=ds.partitioning(
                pa.schema([pa.field(PARTITION_COL, pa.string())]),
                flavor="hive"
            ),
            existing_data_behavior="overwrite_or_ignore",
            file_options=wopts,
            max_rows_per_file=2_000_000,
            create_dir=True,
            use_threads=True,
        )
        total_out += table.num_rows

        batch_acc = {k: [] for k in batch_acc}

    pbar = tqdm(files, desc="Processing JSONs", unit="file")
    for jf in pbar:
        try:
            data = json.load(open(jf, "r", encoding="utf-8"))
            if not isinstance(data, list):
                continue
        except Exception:
            continue

        for d in data:
            anchor = str(d.get("secondFileName", "") or "")
            cand   = str(d.get("fileName", "") or "")
            if drop_self_matches and anchor.lower() == cand.lower():
                continue

            det = d.get("comparisonDetails", {}) or {}

            sm = to_float(det.get("STRING_MINHASH"))
            cr = to_float(det.get("CODE_REGION_LIST"))
            ph = to_float(det.get("PROGRAM_HEADER_VECTOR"))
            eh = to_float(det.get("ELF_HEADER_VECTOR"))
            ss = to_float(det.get("SECTION_SIZE_VECTOR"))

            bin_blk = d.get("binary") or {}
            b_sm = to_int01(bin_blk.get("bin_string_minhash")) if bin_blk else (0 if math.isnan(sm) else int(sm >= TAU["STRING_MINHASH"]))
            b_cr = to_int01(bin_blk.get("bin_code_regions"))   if bin_blk else (0 if math.isnan(cr) else int(cr >= TAU["CODE_REGION_LIST"]))
            b_ph = to_int01(bin_blk.get("bin_program_header")) if bin_blk else (0 if math.isnan(ph) else int(ph >= TAU["PROGRAM_HEADER_VECTOR"]))

            s_weighted = d.get("score_weighted")
            if s_weighted is None:
                s_weighted = float(W["STRING_MINHASH"] * b_sm + W["CODE_REGION_LIST"] * b_cr + W["PROGRAM_HEADER_VECTOR"] * b_ph)
            else:
                s_weighted = to_float(s_weighted)

            src_family = fam_map.get(anchor, extract_family(anchor))
            dst_family = fam_map.get(cand,   extract_family(cand))

            label_same = 1 if (src_family and dst_family and src_family == dst_family) else 0

            batch_acc[PARTITION_COL].append(src_family)
            batch_acc[DST_FAMILY_COL].append(dst_family)
            batch_acc["anchor"].append(anchor)
            batch_acc["candidate"].append(cand)
            batch_acc[LABEL_COL].append(np.int8(label_same))
            batch_acc["rep_string_minhash"].append(np.float32(sm) if not math.isnan(sm) else None)
            batch_acc["rep_code_regions"].append(np.float32(cr) if not math.isnan(cr) else None)
            batch_acc["rep_program_header"].append(np.float32(ph) if not math.isnan(ph) else None)
            batch_acc["rep_elf_header"].append(np.float32(eh) if not math.isnan(eh) else None)
            batch_acc["rep_section_sizes"].append(np.float32(ss) if not math.isnan(ss) else None)
            batch_acc["bin_string_minhash"].append(np.int8(b_sm))
            batch_acc["bin_code_regions"].append(np.int8(b_cr))
            batch_acc["bin_program_header"].append(np.int8(b_ph))
            batch_acc["score_weighted"].append(np.float32(s_weighted))

            total_in += 1

            if csv_sample > 0 and len(sample_rows) < csv_sample:
                sample_rows.append({
                    PARTITION_COL: src_family,
                    DST_FAMILY_COL: dst_family,
                    "anchor": anchor,
                    "candidate": cand,
                    LABEL_COL: label_same,
                    "rep_string_minhash": sm,
                    "rep_code_regions": cr,
                    "rep_program_header": ph,
                    "rep_elf_header": eh,
                    "rep_section_sizes": ss,
                    "bin_string_minhash": b_sm,
                    "bin_code_regions": b_cr,
                    "bin_program_header": b_ph,
                    "score_weighted": s_weighted,
                })

            if len(batch_acc["anchor"]) >= batch_rows:
                flush_batch()

        pbar.set_postfix(rows_in=total_in, rows_out=total_out)

    flush_batch()

    if csv_sample > 0 and sample_rows:
        import csv
        csv_path = out_dir / "_sample_preprocessed.csv"
        keys = list(sample_rows[0].keys())
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader(); w.writerows(sample_rows)

    meta = {
        "input_dir": str(input_dir),
        "output_dir": str(out_dir),
        "partition_col": PARTITION_COL,
        "label_col": LABEL_COL,
        "dst_family_col": DST_FAMILY_COL,
        "thresholds": TAU,
        "weights": W,
        "rows_read": int(total_in),
        "rows_written": int(total_out),
        "drop_self_matches": bool(drop_self_matches),
        "families_csv": str(families_csv) if families_csv else "",
        "schema": [f"{f.name}:{f.type}" for f in OUT_SCHEMA],
        "score_formula": "score_weighted = w_SM*bin_string_minhash + w_CR*bin_code_regions + w_PH*bin_program_header",
    }
    with open(out_dir / "_meta_preprocess.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    print("[OK] Preprocessed dataset written")
    print(json.dumps(meta, indent=2, ensure_ascii=False))


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Preprocess exported JSON comparisons into an analysis-ready Parquet dataset.")
    ap.add_argument("--in-dir",  type=Path, required=True, help="Folder with exported JSON files (one array per file)")
    ap.add_argument("--out-dir", type=Path, required=True, help="Output Parquet dataset (hive partitioned by src_family)")
    ap.add_argument("--families-csv", type=Path, default=None,
                    help="Optional CSV mapping filename->family (columns: filename,family). Overrides inference.")
    ap.add_argument("--drop-self-matches", action="store_true", help="Drop rows where anchor==candidate")
    ap.add_argument("--csv-sample", type=int, default=10_000, help="Write a small CSV sample with this many rows (0=disable)")
    ap.add_argument("--batch-rows", type=int, default=200_000, help="Rows per Parquet write batch")
    return ap.parse_args()


if __name__ == "__main__":
    args = parse_args()
    process(args.in_dir, args.out_dir, args.families_csv, args.drop_self_matches, args.csv_sample, args.batch_rows)
