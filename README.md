# Hermes Fleet Console

Hermes Desktop（`hermes serve`）を入れた複数マシンを、1 つの Web コンソールから **監視・一括操作・プロンプト送信** するためのツールです。
計画書は [docs/planning/hermes-fleet-console.md](docs/planning/hermes-fleet-console.md)。

- マシン側に追加ソフトは不要。Hermes 標準の **ダッシュボード API（:9119）** と **API サーバー（:8642）** を使います。
- サーバーは Node 22 + SQLite の単一プロセス。Mac mini に置いて Tailscale 経由で開きます。
- ブラウザ / PWA / Android アプリ（Capacitor）から同じ画面を使えます。

## まず試す（実機不要のデモ）

```bash
npm install
npm run build
npm run demo          # モック Hermes 6 台 + コンソール http://127.0.0.1:8080
```

ブラウザで http://127.0.0.1:8080 を開き、管理者パスワードを決めるとログインできます。
モックには 5 台の Mac + 1 台の Windows が登録済みで、更新あり / ディスク逼迫 / ゲートウェイ停止 などの状態を含みます。
プロンプトに「please approve」を含めると承認フロー、「slow」を含めると遅い実行、「fail」を含めると失敗を再現します。

## Mac mini に配備する

```bash
git clone <this repo> ~/hermes-fleet-console && cd ~/hermes-fleet-console
bash scripts/macmini/install.sh            # Tailscale IP にバインドして launchd に登録
# → http://<macmini の Tailscale IP>:8080
```

- データ: `~/Library/Application Support/hermes-fleet-console/`（SQLite + 暗号鍵 `master.key`。バックアップはこのフォルダをコピー）
- ログ: `~/Library/Logs/hermes-fleet-console/`
- 更新: `git pull && bash scripts/macmini/install.sh`
- 環境変数: `FLEET_HOST` / `FLEET_PORT` / `FLEET_DATA_DIR` / `FLEET_POLL_INTERVAL_MS`（既定 30 秒）/ `FLEET_CORS_ORIGINS`

## 主な画面

| 画面 | できること |
|---|---|
| フリート | 全マシンのオンライン状態、バージョン、ゲートウェイ状態、CPU / メモリ / ディスク、アラート（オフライン、更新あり、ディスク逼迫、ゲートウェイ停止）。ライブ更新 |
| マシン詳細 | ホスト情報、ログ（レベル / 検索 / 自動更新）、セッションと履歴、cron の作成・一時停止・実行、config / .env の閲覧、単体の操作 |
| マシン詳細 › モデル・プロバイダ | メインモデル、補助タスクごとのモデル、プロバイダの API キー（検証つき）、カスタムエンドポイント、フォールバック / provider_routing / API サーバーのモデル別名。プロファイル単位で切り替え可 |
| マシン詳細 › プロファイル | プロファイルの一覧・作成（複製）・削除・名前変更・アクティブ化・モデル・SOUL.md・説明 |
| プロンプト | 選択したマシンに同じプロンプトを同時送信し、進捗と回答を横並びで表示。承認要求への応答、停止、履歴 |
| 一括操作 | ゲートウェイ起動 / 停止 / 再起動、Hermes アップデート（カナリア方式）、doctor / セキュリティ監査 / バックアップ。進捗をライブ表示 |
| 設定配布 | config.yaml のキーと .env 変数を複数マシンへ配布。差分プレビュー → 適用 |
| cron / セッション | 全マシン横断の一覧・検索・操作 |
| 監査ログ | いつ・どのマシンに・何をしたか |

## 各マシンを登録する

各マシンで 1 回だけ、API サーバーの有効化とダッシュボードのパスワード認証、Tailscale アドレスへのバインドを行います。

```bash
# macOS
bash scripts/enroll/macos.sh --password '<ダッシュボード用パスワード>'
# Windows 11 (PowerShell)
.\scripts\enroll\windows.ps1 -Password '<ダッシュボード用パスワード>'
```

スクリプトが表示する URL / ユーザー名 / API_SERVER_KEY を、コンソールの「マシン → ＋ 追加」に入力し「接続テスト」で確認します。
コンソールの「有効化手順」ボタンからも同じ内容を確認できます。

> Hermes Desktop が自動起動する `127.0.0.1:9119` の serve とは別に、Tailscale アドレスにバインドした serve を常駐させます。ポートが衝突する場合は `--port 9120` などに変更してください。

## ホストされたデモ（Vercel）

コンソールとモック 6 台を 1 つのサーバーレス関数に同居させたデモ構成を同梱しています（`vercel.json` と `scripts/build-vercel-demo.mjs`、Build Output API v3）。

1. https://vercel.com/new/import?s=https://github.com/halz/test でリポジトリを Import（設定は `vercel.json` から自動）
2. デプロイ後の URL を開き、パスワード `demo` でログイン（環境変数 `FLEET_DEMO_PASSWORD` で変更可）

デモではデータはインスタンス内メモリのみで、コールドスタートで初期化されます。実マシンの登録には使わないでください。

## Android アプリ

- **PWA**: コンソールを Chrome で開き「ホーム画面に追加」。
- **APK（ダウンロード）**: 最新の debug APK は Release **apk-latest** から取得できます: https://github.com/halz/test/releases/tag/apk-latest
  （`apk-builds` ブランチにも同じファイルがあります）
- **APK（ビルド）**: GitHub Actions の `Android APK` ワークフロー（`apps/web/**` の変更時と手動実行）が Artifact / Release / `apk-builds` ブランチに出力します。
  端末にインストールして初回起動時にコンソールの URL（例 `http://100.x.y.z:8080`）を入力してください。
  ローカルでビルドする場合は Android Studio / SDK を入れて `cd apps/web && npx cap sync android && cd android && ./gradlew assembleDebug`。

## 構成

```
packages/hermes-client   Hermes のダッシュボード API / API サーバーの型付きクライアント
apps/server              コンソール本体 (Hono + node:sqlite)。/api/* と静的 SPA
apps/web                 React SPA (Vite, PWA)。android/ は Capacitor プロジェクト
apps/mock-hermes         テスト用モック Hermes（6 台）
scripts/macmini          Mac mini への配備 (launchd)
scripts/enroll           各マシンの有効化 (macOS / Windows)
```

## テスト

```bash
npm test                              # 単体テスト (hermes-client, server)
npm run test:e2e -w apps/server       # API e2e (モック + サーバーを別途起動しておく)
CHROMIUM_PATH=... npm run test:ui -w apps/web   # Playwright で UI を操作 (npm run demo を起動しておく)
```

## セキュリティ

- コンソールは全マシンの端末権限を持つため、Tailscale 内のみで公開し、公開インターネットに出さないでください。
- マシンのパスワード / API キーは AES-256-GCM で暗号化して保存され、API からは返しません。
- 破壊的な一括操作は確認ダイアログを経由し、すべての操作が監査ログに残ります。アップデートは既定でカナリア方式です。
