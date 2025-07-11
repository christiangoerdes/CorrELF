import argparse
import hashlib
import logging
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s: %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

def compute_hash(path: Path, algorithm: str = 'md5') -> str:
    hasher = hashlib.new(algorithm)
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            hasher.update(chunk)
    return hasher.hexdigest()

def find_duplicates(directory: Path, algorithm: str):
    if not directory.is_dir():
        logger.error(f"Not a directory: {directory}")
        return

    hashes = {}

    for file_path in directory.rglob('*'):
        if not file_path.is_file():
            continue
        try:
            file_hash = compute_hash(file_path, algorithm)
        except Exception as e:
            logger.warning(f"Skipping {file_path}: could not hash ({e})")
            continue
        hashes.setdefault(file_hash, []).append(file_path)

    has_duplicates = False
    for file_hash, paths in hashes.items():
        if len(paths) > 1:
            has_duplicates = True
            print(f"Hash: {file_hash}")
            for p in paths:
                print(f"  {p}")
            print()

    if not has_duplicates:
        print("No duplicate files found.")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Find duplicate files in a directory based on file content hash."
    )
    parser.add_argument(
        'directory',
        type=Path,
        help='Directory to search for duplicate files'
    )
    parser.add_argument(
        '--algorithm',
        default='md5',
        choices=sorted(hashlib.algorithms_available),
        help='Hash algorithm to use (default: md5)'
    )
    args = parser.parse_args()

    find_duplicates(args.directory, args.algorithm)
