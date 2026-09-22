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
| フリート | ダッシュボード: オンライン台数 / 実行中 / 要対応 / 更新ありの集計、直近のプロンプト・一括操作のアクティビティ、要対応リスト、マシンカード（ゲートウェイ / API 状態、使用中モデル、CPU / メモリ、アラート）。ライブ更新 |
| マシン詳細 | ホスト情報、ログ（レベル / 検索 / 自動更新）、セッションと履歴、cron の作成・一時停止・実行、config / .env の閲覧、単体の操作 |
| マシン詳細 › モデル・プロバイダ | メインモデル、補助タスクごとのモデル、プロバイダの API キー（検証つき）、カスタムエンドポイント、フォールバック / provider_routing / API サーバーのモデル別名。プロファイル単位で切り替え可 |
| マシン詳細 › プロファイル | プロファイルの一覧・作成（複製）・削除・名前変更・アクティブ化・モデル・SOUL.md・説明。プロファイルごとの API キー（生成してプロファイルの .env に配布、または手入力）と接続テスト |
| プロンプト | チャット形式の画面。左に履歴、右に送信先（マシン選択と、マシンごとの送信先プロファイル）。選択したマシン / プロファイルに同じプロンプトを同時送信し、進捗と回答を横並びで表示。承認要求への応答、停止 |
| 一括操作 | ゲートウェイ起動 / 停止 / 再起動、Hermes アップデート（カナリア方式）、doctor / セキュリティ監査 / バックアップ。進捗をライブ表示 |
| 設定配布 | config.yaml のキーと .env 変数を複数マシンへ配布。差分プレビュー → 適用 |
| cron / セッション | 全マシン横断の一覧・検索・操作 |
| 監査ログ | いつ・どのマシンに・何をしたか |

### プロファイル単位でプロンプトを送る

Hermes の名前付きプロファイル（`hermes -p coder`）にもプロンプトを送れます。仕組みは Hermes の multiplex 機能で、既定プロファイルのゲートウェイが `config.yaml` の `gateway.multiplex_profiles: true` のとき、API サーバーが `http://<host>:8642/p/<profile>/v1/runs` で各プロファイルを提供します。認証は **そのプロファイル自身の** `API_SERVER_KEY`（`~/.hermes/profiles/<profile>/.env`）です。

1. マシン側: 既定プロファイルの `config.yaml` に `gateway.multiplex_profiles: true` を設定してゲートウェイを再起動
2. コンソール: マシン詳細 › プロファイル で対象プロファイルの「キーを配布」を押す（コンソールがキーを生成し、ダッシュボード経由でプロファイルの .env に書き込んで保存）。既にキーがある場合は「API キー」から手入力。プロファイルが独自のゲートウェイ / ポートで動いている場合は同じ画面で API URL を指定
3. プロンプト画面の送信先で、マシンごとにプロファイルを選ぶ（「プロファイル一括」で選択中の全マシンにまとめて指定）

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
- **APK の上書き更新（署名キー）**: Android は署名が変わった APK を上書きインストールできません。CI が毎回別の debug キーで署名しないよう、リポジトリの Secrets に固定キーを登録してください（一度だけ）。
  1. キーを作る: `keytool -genkeypair -keystore fleet.jks -storetype PKCS12 -alias fleet -keyalg RSA -keysize 2048 -validity 36500 -storepass <パスワード> -keypass <パスワード> -dname "CN=Hermes Fleet"`
  2. GitHub の Settings → Secrets and variables → Actions に `FLEET_KEYSTORE_B64`（`base64 -w0 fleet.jks` の出力）と `FLEET_KEYSTORE_PASSWORD` を追加
  3. 以後のビルドは同じキーで署名され、`versionCode` はワークフローの実行番号なので新しい APK をそのまま上書きできます。
  キーを変えた直後の 1 回だけは、端末の旧アプリをアンインストールしてから入れ直してください。Secrets が無い場合は警告を出して従来どおり使い捨てキーで署名します。
  ローカルでビルドする場合は Android Studio / SDK を入れて `cd apps/web && npx cap sync android && cd android && ./gradlew assembleDebug`。

## Level Lock ウィジェット（Android）

このリポジトリにはフリートコンソールとは独立した Android アプリ `apps/level-widget` も入っています。
Level Lock / Level Bolt をホーム画面のウィジェットから 1 タップで施錠 / 解錠するためのもので、
Level アプリに API が無いためアクセシビリティサービスで画面を代わりにタップします。
詳細は [apps/level-widget/README.md](apps/level-widget/README.md) を参照してください。

## 構成

```
packages/hermes-client   Hermes のダッシュボード API / API サーバーの型付きクライアント
apps/server              コンソール本体 (Hono + node:sqlite)。/api/* と静的 SPA
apps/web                 React SPA (Vite, PWA)。android/ は Capacitor プロジェクト
apps/mock-hermes         テスト用モック Hermes（6 台）
apps/level-widget        Level Lock を操作する Android ウィジェット（フリートコンソールとは独立）
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
