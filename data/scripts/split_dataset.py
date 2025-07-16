
import random
import shutil
from pathlib import Path

SRC_DIR   = Path('../dataset/All')
TRAIN_DIR = Path('../dataset/Train')
TEST_DIR  = Path('../dataset/Test')

TRAIN_DIR.mkdir(parents=True, exist_ok=True)
TEST_DIR.mkdir(parents=True, exist_ok=True)

files = [f for f in SRC_DIR.iterdir() if f.is_file() and not f.is_symlink()]

random.seed(42)
random.shuffle(files)

n_total = len(files)
if n_total == 0:
    print(f"No files found in {SRC_DIR}")
    exit(1)

n_train = int(0.7 * n_total)
train_files, test_files = files[:n_train], files[n_train:]

def copy_list(file_list, dest_dir):
    for src in file_list:
        dst = dest_dir / src.name
        shutil.copy2(src, dst)
        print(f"Copied {src.name} -> {dest_dir}")

copy_list(train_files, TRAIN_DIR)
copy_list(test_files, TEST_DIR)

print(f"Done. {len(train_files)} files -> Train, {len(test_files)} files -> Test.")