import tarfile
import shutil
import logging
import tempfile
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s: %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

SCRIPT_DIR = Path(__file__).resolve().parent
BUILDS_DIR = (SCRIPT_DIR / '..' / 'builds').resolve()


def is_elf_file(path: Path) -> bool:
    try:
        with path.open('rb') as f:
            return f.read(4) == b'\x7fELF'
    except Exception:
        return False


def extract_elfs_from_roots():
    for image_dir in sorted(p for p in BUILDS_DIR.iterdir() if p.is_dir()):
        logger.info(f"Checking {image_dir.name}")

        candidates = list(image_dir.glob('rootfs.tar*'))
        if not candidates:
            logger.warning(f"{image_dir.name}: no roots.tar* found, skipping")
            continue

        tar_path = candidates[0]
        logger.info(f"{image_dir.name}: using archive {tar_path.name}")

        elfs_dir = image_dir / 'elfs'
        elfs_dir.mkdir(exist_ok=True)
        logger.info(f"{image_dir.name}: writing to {elfs_dir}")

        with tempfile.TemporaryDirectory(dir=image_dir) as tmpdir:
            tmp_path = Path(tmpdir)
            with tarfile.open(tar_path, 'r:*') as tar:
                tar.extractall(path=tmp_path)

            seen = {}
            for src in tmp_path.rglob('*'):
                if not src.is_file():
                    continue
                if not is_elf_file(src):
                    continue

                base = src.name
                count = seen.get(base, 0)
                dest_name = f"{Path(base).stem}_{count}{Path(base).suffix}" if count else base
                seen[base] = count + 1

                dst_path = elfs_dir / dest_name
                shutil.copy2(src, dst_path)
                rel = src.relative_to(tmp_path)
                logger.info(f"{image_dir.name}: extracted {rel} → elfs/{dest_name}")

    logger.info("All done.")

if __name__ == '__main__':
    extract_elfs_from_roots()
