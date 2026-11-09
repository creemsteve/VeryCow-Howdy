#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
cargo build --locked --release --bin vch
echo 'Run: target/release/vch client (or server / daemon)'
