import argparse
import json
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.dataset as ds

RAW_COLS = {
    "STRING_MINHASH":        "rep_string_minhash",
    "CODE_REGION_LIST":      "rep_code_regions",
    "PROGRAM_HEADER_VECTOR": "rep_program_header",
    "ELF_HEADER_VECTOR":     "rep_elf_header",
    "SECTION_SIZE_VECTOR":   "rep_section_sizes",
}
LABEL_COL = "label_same_family"
PARTITION_COL = "src_family"

TAU = {
    "STRING_MINHASH":        0.146484375,
    "CODE_REGION_LIST":      0.3988839387893677,
    "PROGRAM_HEADER_VECTOR": 0.8291192054748535,
    # skip these two in output:
    "ELF_HEADER_VECTOR":     1.0,
    "SECTION_SIZE_VECTOR":   0.9762353301048279,
}

USE_FEATS = ["STRING_MINHASH", "CODE_REGION_LIST", "PROGRAM_HEADER_VECTOR"]

OUT_FIELDS = [
    pa.field(PARTITION_COL, pa.string()),
    pa.field(LABEL_COL, pa.int8()),
    pa.field("bin_string_minhash", pa.int8()),
    pa.field("bin_code_regions",   pa.int8()),
    pa.field("bin_program_header", pa.int8()),
]
OUT_SCHEMA = pa.schema(OUT_FIELDS)


def process(in_dir: Path, out_dir: Path, batch_size: int = 64_000) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    cols_needed = [PARTITION_COL, LABEL_COL] + [RAW_COLS[f] for f in USE_FEATS]
    dset = ds.dataset(str(in_dir), format="parquet", partitioning="hive")
    scanner = dset.scanner(columns=cols_needed, batch_size=batch_size, use_threads=True)

    fmt = ds.ParquetFileFormat()
    wopts = fmt.make_write_options(compression="snappy")

    total_in = total_out = 0

    for b in scanner.to_batches():
        fam = b.column(0).to_numpy(zero_copy_only=False)
        y   = b.column(1).to_numpy(zero_copy_only=False).astype(np.int8, copy=False)

        mats = []
        for i, f in enumerate(USE_FEATS, start=2):
            mats.append(b.column(i).to_numpy(zero_copy_only=False).astype(np.float32, copy=False))
        M = np.column_stack(mats) if mats else np.empty((len(y), 0), dtype=np.float32)

        total_in += len(y)

        if M.size:
            mask_valid = np.all(~np.isnan(M), axis=1) & ~np.isnan(y)
        else:
            mask_valid = ~np.isnan(y)

        if not np.any(mask_valid):
            continue

        fam = fam[mask_valid]
        y   = y[mask_valid]
        M   = M[mask_valid] if M.size else M

        thr_vec = np.array([TAU[f] for f in USE_FEATS], dtype=np.float32)
        B = (M >= thr_vec).astype(np.int8)

        arrays = {
            PARTITION_COL: pa.array(fam, type=pa.string()),
            LABEL_COL:     pa.array(y,   type=pa.int8()),
            "bin_string_minhash": pa.array(B[:, 0], type=pa.int8()),
            "bin_code_regions":   pa.array(B[:, 1], type=pa.int8()),
            "bin_program_header": pa.array(B[:, 2], type=pa.int8()),
        }
        table = pa.table(arrays, schema=OUT_SCHEMA)
        total_out += table.num_rows

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

    meta = {
        "input_dir": str(in_dir),
        "output_dir": str(out_dir),
        "partition_col": PARTITION_COL,
        "label_col": LABEL_COL,
        "used_features": USE_FEATS,
        "thresholds": {f: TAU[f] for f in USE_FEATS},
        "output_schema": [f"{f.name}:{f.type}" for f in OUT_SCHEMA],
        "rows_read": int(total_in),
        "rows_written": int(total_out),
        "rule_positive": "score >= tau_i",
        "dropped_rows_policy": "drop rows with NaN in any used score",
    }
    with open(out_dir / "_meta_apply_thresholds.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    print("[OK] Binarized dataset written")
    print(json.dumps(meta, indent=2, ensure_ascii=False))


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Apply per-representation thresholds to Parquet and emit minimal binary features.")
    ap.add_argument("--in-dir",  type=Path, required=True, help="Input Parquet dataset (hive: src_family=...)")
    ap.add_argument("--out-dir", type=Path, required=True, help="Output Parquet dataset with binary features")
    ap.add_argument("--batch-size", type=int, default=64_000, help="Scanner batch size")
    return ap.parse_args()


if __name__ == "__main__":
    args = parse_args()
    process(args.in_dir, args.out_dir, args.batch_size)
