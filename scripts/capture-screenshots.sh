#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG_DIR="$REPO_ROOT/docs/images"
TEST_CLASS="dev.cupthread.demo.CupThreadDemoScreenshotTest"
APP_ID="dev.cupthread.demo"
DEVICE_SCREENSHOT_DIR="/sdcard/Android/data/$APP_ID/files/Pictures/cupthread_screenshots"
mkdir -p "$IMG_DIR"

DEVICE="${1:-}"

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is required to run screenshot tests." >&2
    exit 1
fi

if [ -z "$DEVICE" ]; then
    CONNECTED_DEVICES="$(adb devices | awk '$2 == "device" { print $1 }')"
    DEVICE_COUNT="$(printf '%s\n' "$CONNECTED_DEVICES" | awk 'NF { count += 1 } END { print count + 0 }')"
    if [ "$DEVICE_COUNT" -ne 1 ]; then
        echo "Expected exactly one connected device; pass its serial as the first argument." >&2
        exit 1
    fi
    DEVICE="$CONNECTED_DEVICES"
fi

if ! adb -s "$DEVICE" get-state | grep -qx "device"; then
    echo "Device '$DEVICE' is not online." >&2
    exit 1
fi

echo "==> Running CupThread Android SDK Screenshot UI Tests..."
adb -s "$DEVICE" shell rm -rf "$DEVICE_SCREENSHOT_DIR"
./gradlew :demo:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
    --serial "$DEVICE"

echo "==> Pulling fresh screenshots from $DEVICE..."
adb -s "$DEVICE" pull "$DEVICE_SCREENSHOT_DIR/." "$IMG_DIR/"

echo "==> Converting screenshots..."
"$REPO_ROOT/scripts/convert-screenshots.sh"

echo "==> Done! Automated screenshots updated in $IMG_DIR"
