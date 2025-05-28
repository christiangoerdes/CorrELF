# Build Dataset

These scripts generate a collection of Buildroot builds with varied configurations.

### Directory Structure

```
./
├── buildroot/                  # cloned Buildroot repo
├── scripts/
│   ├── build.sh                # single randomized build + artifact collection
│   ├── run_builds.sh           # repeat build.sh N times
│   └── collect_elfs.sh         # collect all elf files, add fragment-name and zip it
├── builds/                     # each run’s output lands here
│   └── <fragment-name>/        # one folder per build
│       ├── rootfs.tar
│       ├── elf_bins/           # extracted ELF binaries
│       ├── buildroot.config    
│       ├── <fragment-name>.config  
│       └── busybox.config      
└── README.md                              
```

### Usage

#### Single Build

```bash
chmod +x scripts/build.sh
./build.sh
```

This will:

1. Generate a random BusyBox `.config`.
2. Create a Buildroot fragment selecting:

    * A random architecture (x86\_64, aarch64, arm, riscv64, powerpc).
    * A random optimization level (-O0, -O1, -O2, -O3, -Os).
    * A random GCC version (10.3, 11.4, 12.2, 13.1).
    * A fixed set of packages (`busybox`, `dropbear`, `openssl`) plus 10 additional random packages.
3. Merge the fragment and override the BusyBox config.
4. Build Buildroot.
5. Collect `rootfs.tar`, extracted ELF binaries, and all configs into `builds/<fragment-name>/`.

#### Multiple Builds

```bash
chmod +x scripts/run_builds.sh
scripts/run_builds.sh [count]
```

* **count** (optional): Number of builds to run (default: 5).

### Output

All artifacts are stored under `builds/<fragment-name>/`:

* `rootfs.tar`: generated root filesystem archive
* `elf_bins/`: extracted ELF binaries from the rootfs
* `buildroot.config`: the final Buildroot `.config`
* `<fragment-name>.config`: the Buildroot fragment used
* `busybox.config`: the randomized BusyBox config

