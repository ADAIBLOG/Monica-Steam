#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust"
JNI_OUTPUT="$ROOT_DIR/app/src/main/jniLibs"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: Rust/Cargo is required" >&2
  exit 1
fi

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "error: cargo-ndk is required (cargo install cargo-ndk --locked)" >&2
  exit 1
fi

if command -v rustup >/dev/null 2>&1; then
  rustup target add aarch64-linux-android armv7-linux-androideabi
fi

# Only replace this crate's libraries. Other native dependencies may share these folders.
rm -f "$JNI_OUTPUT/arm64-v8a/libmonica_steam_android.so" "$JNI_OUTPUT/armeabi-v7a/libmonica_steam_android.so"
mkdir -p "$JNI_OUTPUT"

(
  cd "$RUST_DIR"
  cargo ndk \
    --platform 26 \
    -t arm64-v8a \
    -t armeabi-v7a \
    -o "$JNI_OUTPUT" \
    build \
    --locked \
    --release \
    -p monica-steam-android
)

for abi in arm64-v8a armeabi-v7a; do
  library="$JNI_OUTPUT/$abi/libmonica_steam_android.so"
  if [[ ! -s "$library" ]]; then
    echo "error: expected native library was not produced: $library" >&2
    exit 1
  fi
  echo "Built $library"
done
