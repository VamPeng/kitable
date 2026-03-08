#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.vam.demov"
LAUNCH_ACTIVITY=".camera.CameraPreviewActivity"

cd "$ROOT_DIR"

echo "[1/4] Building debug APK..."
./gradlew :app:assembleDebug

if [[ ! -f "$APK_PATH" ]]; then
  echo "Error: APK not found at $APK_PATH"
  exit 1
fi

echo "[2/4] Detecting connected physical devices..."
devices=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] && devices+=("$serial")
done < <(adb devices | tail -n +2 | awk '$2 == "device" {print $1}' | grep -v '^emulator-' || true)

if [[ ${#devices[@]} -eq 0 ]]; then
  echo "Error: No connected physical device detected."
  echo "Tip: Connect a device and ensure USB debugging is authorized."
  exit 2
fi

printf "Found %d physical device(s):\n" "${#devices[@]}"
for serial in "${devices[@]}"; do
  echo " - $serial"
done

echo "[3/4] Installing APK via adb install -r..."
for serial in "${devices[@]}"; do
  echo "Installing to $serial"
  adb -s "$serial" install -r "$APK_PATH"
done

echo "[4/4] Launching app..."
for serial in "${devices[@]}"; do
  echo "Launching on $serial"
  adb -s "$serial" shell am start -n "${PACKAGE_NAME}/${LAUNCH_ACTIVITY}"
done

echo "Done."
