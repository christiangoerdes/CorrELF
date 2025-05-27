#!/usr/bin/env bash
set -euo pipefail

# This script picks a random BusyBox configuration and a set of Buildroot packages,
# runs a fresh Buildroot build, and then collects the resulting artifacts.

dir=$(cd "$(dirname "$0")" && pwd)
BR2_TOP="$(cd "$dir/../buildroot" && pwd)"

FRAG_DIR="$dir/../random-fragments"
rm -rf "$FRAG_DIR" && mkdir -p "$FRAG_DIR"

ts=$(date +%s)

# Generate a random BusyBox configuration
BB_SRC="$BR2_TOP/package/busybox/busybox.config"
BB_RANDOM="$FRAG_DIR/busybox-${ts}.config"
echo ">>> Generating random BusyBox config"
while IFS= read -r line; do
  if [[ $line =~ ^CONFIG_ ]]; then
    sym=${line%%=*}
    if (( RANDOM % 2 )); then
      echo "${sym}=y"
    else
      echo "${sym}=n"
    fi
  else
    echo "$line"
  fi
done < "$BB_SRC" > "$BB_RANDOM"

# Choose random architecture, optimization, and toolchain
arches=(x86_64 aarch64 arm riscv64 powerpc)
arch=${arches[RANDOM % ${#arches[@]}]}
case "$arch" in
  x86_64) arch_sym="BR2_x86_64=y";;
  aarch64) arch_sym="BR2_aarch64=y";;
  arm) arch_sym="BR2_arm=y";;
  riscv64) arch_sym="BR2_riscv=y";;
  powerpc) arch_sym="BR2_powerpc=y";;
esac
opts=("-O0" "-O1" "-O2" "-O3" "-Os")
opt=${opts[RANDOM % ${#opts[@]}]}
opt_sym="BR2_OPTIMIZATION=\"$opt\""
gccs=("10.3" "11.4" "12.2" "13.1")
gcc_ver=${gccs[RANDOM % ${#gccs[@]}]}
gcc_tag=${gcc_ver//./_}
gcc_sym="BR2_GCC_VERSION_${gcc_tag}=y"

name="rand-${ts}-${arch}-O${opt#-}-gcc${gcc_ver}"
fragment="$FRAG_DIR/$name.config"

# Pick random packages (ensuring busybox, dropbear, openssl)
echo "Picking random packages..."
mapfile -t pkg_dirs < <(find "$BR2_TOP/package" -maxdepth 1 -mindepth 1 -type d -printf '%f\n')
required=(busybox dropbear openssl)
for req in "${required[@]}"; do
  pkg_dirs=("${pkg_dirs[@]/$req}")
done
selected=( $(printf "%s\n" "${pkg_dirs[@]}" | shuf -n10) )

# Write the fragment
{
  echo "# random Buildroot config: $name"
  for req in "${required[@]}"; do
    echo "BR2_PACKAGE_${req^^}=y"
  done
  echo "$arch_sym"
  echo "$opt_sym"
  echo "BR2_TOOLCHAIN_BUILDROOT_GLIBC=y"
  echo "$gcc_sym"
  echo "# random extra packages"
  for pkg in "${selected[@]}"; do
    echo "BR2_PACKAGE_${pkg^^}=y"
  done
  echo "BR2_PACKAGE_BUSYBOX_CONFIG_FILE=\"$BB_RANDOM\""
} > "$fragment"

echo ">>> generated Buildroot fragment: $fragment"
FRAG_PATH="$fragment"
FRAG_NAME=$(basename "$FRAG_PATH" .config)

# Build
BUILD_DIR="$(pwd)/builds/$FRAG_NAME"
pushd "$BR2_TOP" > /dev/null
  echo ">>> make distclean"
  make distclean

  echo ">>> make defconfig"
  make defconfig

  echo ">>> Merging fragment"
  support/kconfig/merge_config.sh .config "$FRAG_PATH"

  echo ">>> make olddefconfig"
  make olddefconfig

  # override BusyBox config
  echo ">>> applying random BusyBox config"
  cp "$BB_RANDOM" "package/busybox/busybox.config"

  echo ">>> make -j$(nproc)"
  make -j"$(nproc)"
popd > /dev/null

# Collect outputs
mkdir -p "$BUILD_DIR"
ROOTFS_SRC="$BR2_TOP/output/images/rootfs.tar"
[ -f "$ROOTFS_SRC" ] || { echo "rootfs.tar not found" >&2; exit 1; }
mv "$ROOTFS_SRC" "$BUILD_DIR/"
TMP=$(mktemp -d)
tar xf "$BUILD_DIR/rootfs.tar" -C "$TMP"
mkdir -p "$BUILD_DIR/elf_bins"
find "$TMP" -type f -exec file {} \; \
  | awk -F: '/ELF/ {print $1}' \
  | xargs -r -I{} mv {} "$BUILD_DIR/elf_bins/"

# Save configs & fragment & BusyBox config
cp "$BR2_TOP/.config" "$BUILD_DIR/buildroot.config"
cp "$FRAG_PATH" "$BUILD_DIR/"
cp "$BB_RANDOM" "$BUILD_DIR/busybox.config"

# cleanup
echo ">>> cleaning up"
rm -rf "$TMP"
rm -rf "$FRAG_DIR"

echo "Done – output in: $BUILD_DIR"