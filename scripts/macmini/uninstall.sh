#!/usr/bin/env bash
# Stop and remove the launchd service (keeps data in ~/Library/Application Support/hermes-fleet-console).
set -euo pipefail
LABEL="ai.hermes.fleet-console"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
echo "removed $LABEL (data dir kept)"
