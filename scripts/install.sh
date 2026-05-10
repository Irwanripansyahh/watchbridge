#!/usr/bin/env bash
# WatchBridge installer for macOS / Linux.
# Usage: place this script next to a watchbridge-X.Y.Z.apk and run it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$(ls -1 "$SCRIPT_DIR"/watchbridge-*.apk 2>/dev/null | head -1 || true)"

if [ -z "$APK" ]; then
  echo "Could not find watchbridge-*.apk next to this script."
  echo "Download the APK from https://github.com/rajtiwariee/watchbridge/releases/latest"
  echo "and place it in the same folder as this script."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: 'adb' is not on your PATH."
  echo "Install Android platform-tools first:"
  echo "  macOS:  brew install --cask android-platform-tools"
  echo "  Linux:  sudo apt install adb   (or download from https://developer.android.com/tools/releases/platform-tools)"
  exit 1
fi

echo
echo "==> WatchBridge installer"
echo "    APK: $(basename "$APK")"
echo
echo "On your watch, do this first:"
echo "  1. Settings → About watch → Software → tap 'Software version' 7 times"
echo "  2. Settings → Developer options → enable 'Wireless debugging'"
echo "  3. Tap 'Pair new device' — you'll see an IP:port and a 6-digit code"
echo

read -r -p "Pairing IP:port (from 'Pair new device' screen): " PAIR_ADDR
read -r -p "6-digit pairing code: " PAIR_CODE
adb pair "$PAIR_ADDR" "$PAIR_CODE"

echo
echo "Pairing OK. Now go back one screen so you can see the main 'Wireless debugging' page."
read -r -p "Connect IP:port (from main Wireless debugging screen): " CONN_ADDR

adb connect "$CONN_ADDR"
echo
echo "Installing..."
adb -s "$CONN_ADDR" install -r "$APK"

echo
echo "Done!"
echo "Open WatchBridge on your watch, grant Bluetooth permissions, then on your iPhone:"
echo "  Settings → Bluetooth → look for 'WatchBridge' and tap to pair."
