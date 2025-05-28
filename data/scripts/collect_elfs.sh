#!/usr/bin/env bash
set -euo pipefail

# Collect all ELF binaries from builds/rand* into one ZIP, appending fragment name to avoid duplicates.

# Determine paths
root_dir=$(cd "$(dirname "$0")" && pwd)
builds_dir="$root_dir/../builds"
out_zip="$root_dir/../all_elfs.zip"

if [[ ! -d "$builds_dir" ]]; then
  echo "Builds directory not found: $builds_dir" >&2
  exit 1
fi

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

echo "Collecting ELF files..."
for frag in "$builds_dir"/rand*; do
  [[ -d "$frag" ]] || continue
  frag_name=$(basename "$frag")
  elf_dir="$frag/elf_bins"
  [[ -d "$elf_dir" ]] || continue

  for bin in "$elf_dir"/*; do
    [[ -f "$bin" ]] || continue
    base=$(basename "$bin")
    dest="${base}_${frag_name}"
    echo " - $base  →  $dest"
    cp "$bin" "$tmpdir/$dest"
  done
done

echo "Creating archive: $out_zip"
zip -q -j "$out_zip" "$tmpdir"/*

echo "All done. Archive created at:"
echo "$out_zip"
