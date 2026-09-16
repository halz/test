#!/usr/bin/env bash
# Enroll this Mac into Hermes Fleet Console: enable the API server + dashboard password auth,
# and run a Tailscale-bound `hermes serve` as a LaunchAgent (separate from the one Hermes Desktop spawns on 127.0.0.1).
#
# Usage: bash macos.sh --password <dashboard-password> [--username admin] [--api-key <key>] [--port 9119] [--host <ip>]
# Prints the values to paste into the console's "マシンを追加" form.
set -euo pipefail

USERNAME="admin"; PASSWORD=""; API_KEY=""; PORT=9119; HOST=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --username) USERNAME="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --api-key) API_KEY="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$PASSWORD" ]] || { echo "--password が必要です" >&2; exit 2; }
command -v hermes >/dev/null 2>&1 || { echo "hermes CLI が見つかりません (Hermes Desktop / hermes-agent をインストールしてください)" >&2; exit 1; }

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
ENV_FILE="$HERMES_HOME/.env"
if [[ -z "$HOST" ]]; then
  HOST="$( (command -v tailscale >/dev/null && tailscale ip -4 2>/dev/null | head -n1) || (/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4 2>/dev/null | head -n1) || true)"
  [[ -n "$HOST" ]] || { echo "Tailscale IP を検出できません。--host で指定してください" >&2; exit 1; }
fi
rand() { python3 -c 'import secrets;print(secrets.token_urlsafe(32))'; }
[[ -n "$API_KEY" ]] || API_KEY="$(rand)"
SECRET="$(rand)"

mkdir -p "$HERMES_HOME"; touch "$ENV_FILE"; chmod 600 "$ENV_FILE"
setenv() { # key value
  if grep -q "^$1=" "$ENV_FILE"; then sed -i '' "s|^$1=.*|$1=$2|" "$ENV_FILE"; else printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"; fi
}
setenv API_SERVER_ENABLED true
setenv API_SERVER_KEY "$API_KEY"
setenv API_SERVER_HOST "$HOST"
setenv API_SERVER_PORT 8642
setenv HERMES_DASHBOARD_BASIC_AUTH_USERNAME "$USERNAME"
setenv HERMES_DASHBOARD_BASIC_AUTH_PASSWORD "$PASSWORD"
if ! grep -q '^HERMES_DASHBOARD_BASIC_AUTH_SECRET=' "$ENV_FILE"; then setenv HERMES_DASHBOARD_BASIC_AUTH_SECRET "$SECRET"; fi

LABEL="ai.hermes.serve-fleet"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
mkdir -p "$HOME/Library/LaunchAgents" "$HERMES_HOME/logs"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(command -v hermes)</string>
    <string>serve</string>
    <string>--host</string><string>$HOST</string>
    <string>--port</string><string>$PORT</string>
    <string>--skip-build</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$HOME/.local/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HERMES_HOME/logs/serve-fleet.log</string>
  <key>StandardErrorPath</key><string>$HERMES_HOME/logs/serve-fleet.err.log</string>
</dict>
</plist>
PLIST
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/$LABEL"

# gateway (hosts the API server) as a service
hermes gateway install >/dev/null 2>&1 || true
hermes gateway restart >/dev/null 2>&1 || hermes gateway start >/dev/null 2>&1 || true

sleep 3
echo "==> 確認"
curl -fsS "http://$HOST:$PORT/api/status" >/dev/null && echo "   dashboard  http://$HOST:$PORT  OK" || echo "   dashboard  http://$HOST:$PORT  応答なし（数秒後に再確認: launchctl list | grep $LABEL）"
curl -fsS -H "Authorization: Bearer $API_KEY" "http://$HOST:8642/health/detailed" >/dev/null && echo "   api server http://$HOST:8642  OK" || echo "   api server http://$HOST:8642  応答なし（hermes gateway status で確認）"
cat <<OUT

コンソールの「マシンを追加」に入力する値:
  ダッシュボード URL : http://$HOST:$PORT
  ユーザー名         : $USERNAME
  パスワード         : (指定したもの)
  API サーバー URL   : http://$HOST:8642
  API_SERVER_KEY     : $API_KEY
OUT
