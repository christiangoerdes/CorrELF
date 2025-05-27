# Build Dataset

These scripts generate a collection of Buildroot builds with varied configurations.

### Directory Structure

```
./
├── buildroot/                 # Clone the buildroot repo here (https://github.com/buildroot/buildroot.git)
├── scripts/
│   ├── build.sh               # Generates a random fragment, random BusyBox config, runs a build, and collects artifacts
│   └── run_builds.sh          # Wrapper to execute build.sh multiple times, even if some builds fail
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

