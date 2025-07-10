import shutil
import logging
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s: %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

SCRIPT_DIR = Path(__file__).resolve().parent

BUILDS_DIR = (SCRIPT_DIR / '..' / 'builds').resolve()

DATASET_DIR = (SCRIPT_DIR / '..' / 'dataset' / 'buildroot').resolve()

def aggregate_elfs():
    DATASET_DIR.mkdir(parents=True, exist_ok=True)
    logger.info(f"Copying ELF binaries into {DATASET_DIR}")

    for image_dir in sorted(p for p in BUILDS_DIR.iterdir() if p.is_dir()):
        elfs_dir = image_dir / 'elfs'
        if not elfs_dir.is_dir():
            logger.warning(f"{image_dir.name}: no 'elfs' directory, skipping")
            continue

        for elf_file in sorted(elfs_dir.iterdir()):
            if not elf_file.is_file():
                continue
            new_name = f"{elf_file.stem}___{image_dir.name}{elf_file.suffix}"
            dest_path = DATASET_DIR / new_name
            try:
                shutil.copy2(elf_file, dest_path)
                logger.info(f"Copied {elf_file.name} → {new_name}")
            except Exception as e:
                logger.error(f"Failed to copy {elf_file}: {e}")

    logger.info("Aggregation complete.")

if __name__ == '__main__':
    aggregate_elfs()
