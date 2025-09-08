#!/usr/bin/env python3
"""
Preprocess FileComparison JSONs into a tidy Parquet dataset for threshold search.

Input:  directory of JSON files (each is a list of FileComparison objects)
Output: partitioned Parquet dataset (partitioned by src_family), one row per (src,tgt) pair with 5 rep scores

Schema (columns):
- src_file, tgt_file (str)
- src_family, tgt_family (str, derived from name before '___')
- rep_string_minhash, rep_code_regions, rep_program_header, rep_elf_header, rep_section_sizes (float32)
- label_same_family (int8)

Dedup rule (no global state needed):
- Keep only pairs with src_file < tgt_file (lexicographically). Skip src_file == tgt_file.
"""

import argparse
import gzip
import io
import json
import os
import sys
import uuid
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional

try:
    import ijson
    HAS_IJSON = True
except Exception:
    HAS_IJSON = False

import pyarrow as pa
import pyarrow.dataset as ds

# ---------- Config ----------

REPS_MAP = {
    "STRING_MINHASH": "rep_string_minhash",
    "CODE_REGION_LIST": "rep_code_regions",
    "PROGRAM_HEADER_VECTOR": "rep_program_header",
    "ELF_HEADER_VECTOR": "rep_elf_header",
    "SECTION_SIZE_VECTOR": "rep_section_sizes",
}

SCHEMA = pa.schema([
    pa.field("src_file", pa.string()),
    pa.field("tgt_file", pa.string()),
    pa.field("src_family", pa.string()),
    pa.field("tgt_family", pa.string()),
    pa.field("rep_string_minhash", pa.float32()),
    pa.field("rep_code_regions", pa.float32()),
    pa.field("rep_program_header", pa.float32()),
    pa.field("rep_elf_header", pa.float32()),
    pa.field("rep_section_sizes", pa.float32()),
    pa.field("label_same_family", pa.int8()),
])


def is_gzip(path: Path) -> bool:
    return path.suffix.lower() in (".gz", ".gzip")

def open_text(path: Path) -> io.TextIOBase:
    if is_gzip(path):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    return open(path, "r", encoding="utf-8")

def family_from_name(name: str) -> str:
    return name.split("___", 1)[0].lower()

def iter_filecomparisons(path: Path) -> Iterator[Dict]:
    try:
        if HAS_IJSON:
            with open_text(path) as f:
                for obj in ijson.items(f, "item"):
                    if isinstance(obj, dict):
                        yield obj
        else:
            with open_text(path) as f:
                data = json.load(f)
                if isinstance(data, list):
                    for obj in data:
                        if isinstance(obj, dict):
                            yield obj
    except Exception as e:
        print(f"[WARN] Failed to parse {path.name}: {e}", file=sys.stderr)

def to_row(obj: Dict) -> Optional[Dict]:
    tgt = obj.get("fileName")           # DB/target
    src = obj.get("secondFileName")     # input/source
    if not src or not tgt:
        return None
    if src == tgt:
        return None
    if src > tgt:
        return None

    src_fam = family_from_name(src)
    tgt_fam = family_from_name(tgt)

    det = obj.get("comparisonDetails") or {}
    row = {
        "src_file": src,
        "tgt_file": tgt,
        "src_family": src_fam,
        "tgt_family": tgt_fam,
        "rep_string_minhash": _to_f32(det.get("STRING_MINHASH")),
        "rep_code_regions": _to_f32(det.get("CODE_REGION_LIST")),
        "rep_program_header": _to_f32(det.get("PROGRAM_HEADER_VECTOR")),
        "rep_elf_header": _to_f32(det.get("ELF_HEADER_VECTOR")),
        "rep_section_sizes": _to_f32(det.get("SECTION_SIZE_VECTOR")),
        "label_same_family": 1 if src_fam == tgt_fam else 0,
    }
    return row

def _to_f32(v) -> Optional[float]:
    try:
        if v is None:
            return None
        return float(v)
    except Exception:
        return None

def write_chunk_arrow(rows: List[Dict], base_dir: Path) -> None:
    if not rows:
        return

    arrays = {name: [] for name in SCHEMA.names}
    for r in rows:
        for k in arrays:
            arrays[k].append(r.get(k))
    table = pa.table(arrays, schema=SCHEMA)

    # Parquet-Write-Options korrekt erzeugen (keine pa.parquet.* Nutzung!)
    fmt = ds.ParquetFileFormat()
    opts = fmt.make_write_options(compression="snappy")

    ds.write_dataset(
        table,
        base_dir=str(base_dir),
        format=fmt,
        partitioning=ds.partitioning(
            pa.schema([pa.field("src_family", pa.string())]),
            flavor="hive"
        ),
        existing_data_behavior="overwrite_or_ignore",
        file_options=opts,
        max_rows_per_file=2_000_000,
        create_dir=True,
        use_threads=True,
    )

def preprocess(input_dir: Path, output_dir: Path, chunk_rows: int = 500_000) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)

    json_files = sorted([p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in (".json", ".gz", ".gzip")])
    if not json_files:
        print(f"[ERROR] No JSON(.gz) files in {input_dir}", file=sys.stderr)
        sys.exit(2)

    buffer: List[Dict] = []
    total_rows = 0
    kept_rows = 0
    files_ok = files_bad = 0

    for path in json_files:
        n_before = kept_rows
        parsed_any = False
        for obj in iter_filecomparisons(path):
            parsed_any = True
            row = to_row(obj)
            total_rows += 1
            if row is None:
                continue
            buffer.append(row)
            kept_rows += 1
            if len(buffer) >= chunk_rows:
                write_chunk_arrow(buffer, output_dir)
                buffer.clear()
        if parsed_any:
            files_ok += 1
        else:
            files_bad += 1
        print(f"[{files_ok+files_bad:6d}/{len(json_files):6d}] {path.name} → kept {kept_rows-n_before:6d} rows")

    write_chunk_arrow(buffer, output_dir)
    buffer.clear()

    meta = {
        "input_dir": str(input_dir),
        "output_dir": str(output_dir),
        "files_ok": files_ok,
        "files_bad": files_bad,
        "total_pairs_seen": total_rows,
        "total_pairs_kept": kept_rows,
        "dedupe_rule": "keep only src_file < tgt_file; drop src==tgt",
        "partitions": ["src_family"],
        "schema": [f"{f.name}:{f.type}" for f in SCHEMA],
    }
    with open(output_dir / "_meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print("\nDone.")
    print(json.dumps(meta, indent=2))

def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Preprocess FileComparison JSONs into Parquet for thresholding.")
    ap.add_argument("--input-dir", type=Path, required=True, help="Directory with JSON files")
    ap.add_argument("--output-dir", type=Path, required=True, help="Output directory for Parquet dataset")
    ap.add_argument("--chunk-rows", type=int, default=500_000, help="Rows per write chunk (default: 500k)")
    return ap.parse_args()

if __name__ == "__main__":
    args = parse_args()
    preprocess(args.input_dir, args.output_dir, args.chunk_rows)
