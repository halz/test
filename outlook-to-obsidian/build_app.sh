#!/usr/bin/env bash
# Build the macOS .app bundle. Run this ON macOS (py2app + AppleScript are
# macOS-only). Apple Silicon: if you hit architecture errors, prefix with
# `arch -arm64`.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

"$PYTHON" -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt -r requirements-build.txt

rm -rf build dist
python setup.py py2app

echo
echo "Built: $(pwd)/dist/OutlookObsidianBridge.app"
echo "GUI:   open dist/OutlookObsidianBridge.app"
echo "CLI:   ./dist/OutlookObsidianBridge.app/Contents/MacOS/OutlookObsidianBridge  (launches the GUI)"
echo
echo "For scheduled syncs use the venv CLI directly, e.g.:"
echo "  .venv/bin/python -m src.main -c config.yaml sync"
