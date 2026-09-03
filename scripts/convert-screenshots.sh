#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG_DIR="$REPO_ROOT/docs/images"

mkdir -p "$IMG_DIR"

echo "==> Optimizing screenshots in $IMG_DIR..."

for name in roadmap feature_requests submit_request whats_new changelog_overlay feedback_composer; do
    src_png="$IMG_DIR/$name.png"
    if [ -f "$src_png" ]; then
        echo "Converting $name.png -> $name.jpg..."
        sips -s format jpeg -s formatOptions 85 "$src_png" --out "$IMG_DIR/$name.jpg" >/dev/null 2>&1
        rm "$src_png"
    fi
done

echo "==> Screenshots ready in $IMG_DIR:"
ls -lh "$IMG_DIR"/*.jpg
