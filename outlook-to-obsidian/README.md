# Outlook → Obsidian Bridge (macOS 版)

職場の **Outlook for Mac** のメール（受信トレイ + 送信済みアイテム）を、ローカル完結で
**Obsidian Vault** に Markdown として取り込むツールです。YAML frontmatter 付きで
Dataview / 検索 / バックリンクから活用できます。

> 元仕様 (`SPEC.md`) は Windows + Outlook COM (pywin32) 前提でした。本実装は
> **macOS `.app`** という成果物要件に合わせ、Outlook 接続層を **AppleScript** に
> 作り替えています。差異は下表のとおりです。

---

## Windows 仕様からの作り替え点（Deviations）

| 項目 | SPEC（Windows） | 本実装（macOS） |
|------|-----------------|------------------|
| Outlook 接続 | COM / `pywin32` | AppleScript (`osascript`) |
| 一意キー `entry_id` | `EntryID` | メッセージ `id`（プロファイル内で一意） |
| `conversation_id` | Exchange `ConversationID` | **件名（RE:/FW: 除去）から導出**（topic グルーピング） |
| 本文 | `HTMLBody` / `Body` | `content`（HTML 判定してフォールバック） |
| メッセージ `Size` | `Size` | 取得不可 → `size_bytes: 0` |
| 既読/未読・フラグ | `UnRead` / flag | AppleScript 予約語 `is`（`is read`/`is flagged`）の都合で堅牢に取得できないため省略 → `unread: false` / `flag: none` 固定 |
| 送信済みフォルダ | "Sent Items" | 名前一致で探索（`mail folders whose name contains "Sent"`） |
| 定期実行 | タスクスケジューラ / `.bat` | **launchd** (`*.plist`) |
| パッケージ | `.exe` 相当 | **`.app`**（py2app） |
| UI | （CLI） | CLI 中心 + 最小ステータス画面（Tkinter） |

> `conversation_id` を件名から導出するため、スレッドは「件名トピック単位」で
> まとまります。Exchange の厳密な会話 ID ではない点に留意してください。

---

## 前提条件

- macOS 12 以降 + **Outlook for Mac（クラシック / スクリプティング対応版）**
- Python 3.11 以降
- Obsidian Vault（ローカル）
- ネットワーク不要・追加認証不要（既存の Outlook セッションを利用）

---

## インストール

```bash
git clone <this-repo>
cd outlook-to-obsidian

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

cp config.example.yaml config.yaml
# config.yaml の vault_path を自分の Vault に合わせて編集
```

---

## 設定 (`config.yaml`)

`config.example.yaml` を参照。最低限 `vault_path` を実環境に合わせてください。

| キー | 説明 |
|------|------|
| `vault_path` | Obsidian Vault のルート（`~` 展開可） |
| `output_subdir` | Vault 内の出力先（既定 `Emails`） |
| `accounts` | 対象アカウント（空＝全アカウント） |
| `folders.inbox/sent/include_subfolders` | 取り込み対象 |
| `excluded_folders` | 除外フォルダ（部分一致） |
| `output.preserve_html_signatures` | `false`=署名を `<details>` で折り畳む / `true`=そのまま |
| `sync.lookback_buffer_minutes` | 差分同期の遡りバッファ（時計ズレ対策） |

---

## macOS の権限設定（重要）

初回実行時、macOS が **オートメーション許可** を求めます。許可しないと Outlook を
読み取れません。

1. 初回 `sync` 実行時に出る「"…" が "Microsoft Outlook" を制御しようとしています」
   ダイアログで **OK**。
2. 後から変更する場合: **システム設定 → プライバシーとセキュリティ → オートメーション**
   で、実行元（ターミナル / 本 `.app`）→ **Microsoft Outlook** にチェック。
3. Vault が外付け/保護領域にある場合は **フルディスクアクセス** も付与。

> `.app` 版は `NSAppleEventsUsageDescription` を Info.plist に含めているため、
> 上記プロンプトが正しく表示されます。

---

## 使い方（CLI）

```bash
# 差分同期（通常運用）
python -m src.main sync

# 初回フル同期（強制）
python -m src.main sync --full

# ドライラン（書き込まず件数のみ）
python -m src.main sync --dry-run

# 特定日付以降のみ
python -m src.main sync --since 2026-01-01

# Outlook 不要のサンプルデータで動作確認（パイプライン検証用）
python -m src.main sync --full --mock --dry-run

# スレッドノートのみ再生成
python -m src.main rebuild-threads

# 同期状態の表示
python -m src.main status

# DB リセット（Markdown は消えません）
python -m src.main reset --confirm

# 最小ステータス画面（GUI）
python -m src.main gui
```

別 Vault/設定を使う場合は `-c path/to/config.yaml` を付与。

出力先フォルダ（Vault 内）を実行時に上書きするには、グローバルオプション
`--output-subdir`（サブコマンドより前に指定）を使います:

```bash
# ~/Obsidian/WorkVault/Work/Inbox/ に出力
python -m src.main --output-subdir "Work/Inbox" sync --full
```

> 同じ `--output-subdir` を `status` / `rebuild-threads` / `reset` でも指定して
> ください（DB も出力先フォルダ直下に置かれるため、コマンド間で一致させる必要が
> あります）。恒久的に変えたい場合は `config.yaml` の `output_subdir` を編集します。

GUI（ステータス画面）では **「出力先を選択…」** ボタンで Vault 内のフォルダを
選べます（その起動セッション中だけ有効）。

---

## 出力構造

```
<VAULT_ROOT>/Emails/
├── Messages/2026/05/2026-05-28_143012_RE-EFTPOS-error.md   # 個別メール
├── Threads/thread_<hex8>.md                                # スレッド集約
└── .sync_state.db                                          # 同期状態(SQLite)
```

---

## `.app` のビルド（macOS 上で実施）

```bash
./build_app.sh
# → dist/OutlookObsidianBridge.app
open dist/OutlookObsidianBridge.app   # GUI 起動
```

- `.app` をダブルクリックすると最小ステータス画面が開きます（「今すぐ同期」「Vault を開く」「ログを開く」）。
- Apple Silicon でアーキテクチャ系のエラーが出たら `arch -arm64 ./build_app.sh`。
- 定期実行は `.app` ではなく venv の CLI を launchd から呼ぶ構成を推奨（下記）。

---

## 定期実行（launchd）

`com.unitedpetroleum.outlook-obsidian.plist.example` を編集して導入します。

```bash
# パスを自分の環境に合わせて編集後:
cp com.unitedpetroleum.outlook-obsidian.plist.example \
   ~/Library/LaunchAgents/com.unitedpetroleum.outlook-obsidian.plist
launchctl load ~/Library/LaunchAgents/com.unitedpetroleum.outlook-obsidian.plist
```

- ログイン時 + 1 時間ごとに `sync` を実行します。
- 解除: `launchctl unload ~/Library/LaunchAgents/com.unitedpetroleum.outlook-obsidian.plist`

---

## Obsidian 側の推奨設定

- `.obsidianignore` に `Emails/.sync_state.db` を追加（クラウド同期に SQLite を載せない）。
- **Dataview** プラグインを有効化（frontmatter を活用）。
- メールフォルダはグラフビューから除外推奨（ノードが増えるため）。

---

## 手動受け入れテスト

1. `python -m src.main sync --full` を実行。
2. Vault の `Emails/Messages` と `Emails/Threads` を Obsidian で確認。
3. 同じコマンドを再実行 → `status` で `added=0` を確認。
4. 新規メールを 1 通受信後に再実行 → 1 件のみ追加されることを確認。

---

## 開発 / テスト

```bash
pip install pytest
python -m pytest          # 全ユニット + 統合テスト（モック Outlook）
```

テストとモックは macOS / Outlook なしで動作します（パイプライン検証用）。

---

## セキュリティ

- ネットワーク I/O なし・資格情報を保管しない（既存 Outlook セッション利用）。
- 添付ファイル本体は保存せず、メタデータ（名前・拡張子・サイズ）のみ記録。
- `.sync_state.db` は Vault のクラウド同期から除外推奨。
- Vault 自体の暗号化・バックアップはユーザー責任で実施してください。

---

## 既知の制約・トラブルシュート

- **「新しい Outlook for Mac」は AppleScript 対応が限定的**です。スクリプティングが
  効かない場合は、Outlook のメニュー「新しい Outlook」をオフ（クラシック表示）にして
  ください。
- AppleScript の取得項目は Outlook のバージョンで差があります。取得が空になる場合は、
  `src/outlook_client.py` の `build_applescript()` が**最も調整が必要な箇所**です
  （フォルダ名 `"inbox"` / `"sent mail"` やプロパティ名）。Python 側パイプラインは
  この層から完全に分離され、テスト済みです。
- プロファイル変更後はメッセージ `id` が変わり得るため、`reset --confirm` 後に再フル同期
  してください。
- 大量メール（数千件超）の初回同期は数十分かかることがあります。
