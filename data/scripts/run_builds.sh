#!/usr/bin/env bash
set -euo pipefail

# Wrapper to run build.sh multiple times
# Usage: ./run_builds.sh [count]

# Default number of runs
count=${1:-5}

# Path to build script
build_script="$(cd "$(dirname "$0")" && pwd)/build.sh"

# Check build script exists
if [[ ! -x "$build_script" ]]; then
  echo "build script not found or not executable at $build_script" >&2
  exit 1
fi

echo ">>> Starting $count build(s)"

for i in $(seq 1 "$count"); do
  echo
  echo "=== Build #$i ==="
  if "$build_script"; then
    echo ">>> Build #$i completed"
  else
    echo ">>>  Build #$i failed, continuing..."
  fi
done

echo "Done running $count build(s)"
