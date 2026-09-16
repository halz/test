#!/usr/bin/env bash
# Install / update Hermes Fleet Console on the Mac mini host and register it with launchd.
# Usage:  bash scripts/macmini/install.sh [--host <bind-ip>] [--port 8080]
#   --host   Bind address. Default: this machine's Tailscale IPv4 (falls back to 127.0.0.1).
#   --port   Listen port (default 8080).
# Re-running is safe: it rebuilds, reinstalls the LaunchAgent and restarts the service.
set -euo pipefail

PORT=8080
HOST=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DATA_DIR="$HOME/Library/Application Support/hermes-fleet-console"
LOG_DIR="$HOME/Library/Logs/hermes-fleet-console"
LABEL="ai.hermes.fleet-console"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

if [[ -z "$HOST" ]]; then
  if command -v tailscale >/dev/null 2>&1; then
    HOST="$(tailscale ip -4 2>/dev/null | head -n1 || true)"
  elif [[ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]]; then
    HOST="$(/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4 2>/dev/null | head -n1 || true)"
  fi
  HOST="${HOST:-127.0.0.1}"
fi

command -v node >/dev/null 2>&1 || { echo "Node.js 22+ が必要です: brew install node@22" >&2; exit 1; }
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if (( NODE_MAJOR < 22 )); then echo "Node.js 22 以上が必要です (現在 $(node -v))" >&2; exit 1; fi

echo "==> building ($ROOT)"
cd "$ROOT"
npm install --no-audit --no-fund
npm run build

mkdir -p "$DATA_DIR" "$LOG_DIR" "$HOME/Library/LaunchAgents"
chmod 700 "$DATA_DIR"

echo "==> writing $PLIST"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(command -v node)</string>
    <string>$ROOT/apps/server/dist/index.js</string>
  </array>
  <key>WorkingDirectory</key><string>$ROOT</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>FLEET_HOST</key><string>$HOST</string>
    <key>FLEET_PORT</key><string>$PORT</string>
    <key>FLEET_DATA_DIR</key><string>$DATA_DIR</string>
    <key>FLEET_STATIC_DIR</key><string>$ROOT/apps/web/dist</string>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOG_DIR/console.log</string>
  <key>StandardErrorPath</key><string>$LOG_DIR/console.err.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/$LABEL"
sleep 2
if curl -fsS "http://$HOST:$PORT/api/health" >/dev/null; then
  echo "==> Fleet Console is running: http://$HOST:$PORT"
  echo "    初回アクセス時に管理者パスワードを設定してください。"
  echo "    ログ: $LOG_DIR"
else
  echo "!! サーバーが応答しません。ログを確認してください: $LOG_DIR/console.err.log" >&2
  exit 1
fi
