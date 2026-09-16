<#
Enroll this Windows 11 machine into Hermes Fleet Console.
- Enables the API server and dashboard password auth in %LOCALAPPDATA%\hermes\.env
- Registers a Scheduled Task that runs `hermes serve --host <tailscale-ip> --port 9119` at logon
- Installs/starts the gateway service (hosts the API server)

Usage (PowerShell, as the Hermes user):
  .\windows.ps1 -Password "<dashboard-password>" [-Username admin] [-ApiKey <key>] [-Port 9119] [-Host <ip>]
#>
param(
  [Parameter(Mandatory = $true)][string]$Password,
  [string]$Username = "admin",
  [string]$ApiKey = "",
  [int]$Port = 9119,
  [string]$Host = ""
)
$ErrorActionPreference = "Stop"

$hermes = (Get-Command hermes -ErrorAction SilentlyContinue)
if (-not $hermes) { throw "hermes CLI が見つかりません (Hermes Desktop / hermes-agent をインストールしてください)" }
$HermesHome = if ($env:HERMES_HOME) { $env:HERMES_HOME } else { Join-Path $env:LOCALAPPDATA "hermes" }
$EnvFile = Join-Path $HermesHome ".env"
New-Item -ItemType Directory -Force -Path $HermesHome | Out-Null
if (-not (Test-Path $EnvFile)) { New-Item -ItemType File -Path $EnvFile | Out-Null }

if (-not $Host) {
  $ts = Get-Command tailscale -ErrorAction SilentlyContinue
  if (-not $ts) { $ts = "C:\Program Files\Tailscale\tailscale.exe" }
  try { $Host = (& $ts ip -4 2>$null | Select-Object -First 1).Trim() } catch {}
  if (-not $Host) { throw "Tailscale IP を検出できません。-Host で指定してください" }
}
function Rand() { $b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_') }
if (-not $ApiKey) { $ApiKey = Rand }

$lines = Get-Content $EnvFile -ErrorAction SilentlyContinue
function SetEnv([string]$k, [string]$v) {
  $script:lines = @($script:lines | Where-Object { $_ -notmatch "^$k=" })
  $script:lines += "$k=$v"
}
SetEnv "API_SERVER_ENABLED" "true"
SetEnv "API_SERVER_KEY" $ApiKey
SetEnv "API_SERVER_HOST" $Host
SetEnv "API_SERVER_PORT" "8642"
SetEnv "HERMES_DASHBOARD_BASIC_AUTH_USERNAME" $Username
SetEnv "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD" $Password
if (-not ($lines | Where-Object { $_ -match "^HERMES_DASHBOARD_BASIC_AUTH_SECRET=" })) { SetEnv "HERMES_DASHBOARD_BASIC_AUTH_SECRET" (Rand) }
Set-Content -Path $EnvFile -Value $lines -Encoding UTF8

# Scheduled task: hermes serve at logon, restart on failure
$taskName = "Hermes Serve (Fleet)"
$action = New-ScheduledTaskAction -Execute $hermes.Source -Argument "serve --host $Host --port $Port --skip-build"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -RunLevel Limited | Out-Null
Start-ScheduledTask -TaskName $taskName

try { & hermes gateway install | Out-Null } catch {}
try { & hermes gateway restart | Out-Null } catch { try { & hermes gateway start | Out-Null } catch {} }

Start-Sleep -Seconds 4
Write-Host "==> 確認"
try { Invoke-RestMethod "http://$Host`:$Port/api/status" | Out-Null; Write-Host "   dashboard  http://$Host`:$Port  OK" } catch { Write-Host "   dashboard  http://$Host`:$Port  応答なし (タスク スケジューラで '$taskName' を確認)" }
try { Invoke-RestMethod "http://$Host`:8642/health/detailed" -Headers @{ Authorization = "Bearer $ApiKey" } | Out-Null; Write-Host "   api server http://$Host`:8642  OK" } catch { Write-Host "   api server http://$Host`:8642  応答なし (hermes gateway status で確認)" }
Write-Host ""
Write-Host "コンソールの「マシンを追加」に入力する値:"
Write-Host "  ダッシュボード URL : http://$Host`:$Port"
Write-Host "  ユーザー名         : $Username"
Write-Host "  API サーバー URL   : http://$Host`:8642"
Write-Host "  API_SERVER_KEY     : $ApiKey"
Write-Host "注意: Windows では埋め込み TUI (/api/pty) は使えません。それ以外の機能は同じです。"
