import argparse
import hashlib
import logging
import sys
from pathlib import Path

def compute_hash(path: Path) -> str:
    try:
        hasher = hashlib.sha256()
        with path.open('rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                hasher.update(chunk)
        return hasher.hexdigest()
    except Exception as e:
        logging.warning(f"Failed to hash file {path}: {e}")
        return None


def main():
    parser = argparse.ArgumentParser(
        description="Remove exact duplicate files in a directory using SHA-256 hashes."
    )
    parser.add_argument(
        'directory',
        type=Path,
        help='Target directory to scan for duplicates'
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format='%(levelname)s: %(message)s'
    )

    if not args.directory.is_dir():
        logging.error(f"Invalid directory: {args.directory}")
        sys.exit(1)

    report_dir  = args.directory.parent
    report_file = report_dir / 'duplicate_cleaner_report.txt'
    with report_file.open('w') as report:
        report.write(f"Duplicate Cleaner Report for: {args.directory}\n")
        report.write("========================================\n\n")

        logging.info(f"Processing directory: {args.directory}")
        report.write(f"Processing directory: {args.directory}\n\n")

        files = [p for p in args.directory.iterdir() if p.is_file()]

        hash_map = {}
        for file in files:
            digest = compute_hash(file)
            if digest:
                hash_map.setdefault(digest, []).append(file)

        removed_count = 0

        for digest, paths in hash_map.items():
            if len(paths) > 1:
                paths_sorted = sorted(paths, key=lambda p: p.name)
                kept = paths_sorted[0]
                logging.info(f"Keeping file {kept} for hash {digest}")
                report.write(f"Hash: {digest}\n")
                report.write(f"  Kept: {kept.name}\n")
                for duplicate in paths_sorted[1:]:
                    try:
                        duplicate.unlink()
                        removed_count += 1
                        logging.info(f"Deleted duplicate {duplicate}")
                        report.write(f"  Deleted: {duplicate.name}\n")
                    except Exception as e:
                        logging.error(f"Failed to delete {duplicate}: {e}")
                        report.write(f"  Failed to delete: {duplicate.name} ({e})\n")
                report.write("\n")

        summary = f"Duplicate removal complete. Removed {removed_count} files."
        print(summary)
        report.write(summary + "\n")

    logging.info(f"Report written to {report_file}")

if __name__ == '__main__':
    main()
