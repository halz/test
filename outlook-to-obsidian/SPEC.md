# Outlook → Obsidian Bridge: 要件定義・実装仕様書

> **対象実装者**: Claude Code
> **作成日**: 2026-05-28
> **言語/環境**: Python 3.11+ / Windows 10/11 / Outlook Desktop (Microsoft 365 版)
> **目的**: 職場の Outlook メール（受信・送信）をローカルで Obsidian Vault に Markdown として取り込み、自分の応答パターンを含めて知識ベース化する

---

## 1. 背景と目的

### 1.1 背景
- ユーザー (Yoshi) は United Petroleum の業務 PC で Outlook デスクトップ版を使用
- IT 管理部の追加認証・Azure AD アプリ登録なしで完結する必要がある
- M365 MCP / Superhuman Mail 等のクラウドコネクタはセキュリティポリシー上利用不可
- Obsidian Vault に取り込むことで、Dataview / 検索 / バックリンクを活用した知識管理を行いたい
- 自身の返信メールも取り込むことで、後段で「自分の文体・判断パターン」を LLM に学習させる素材にする

### 1.2 目的
1. **取り込み**: Outlook の受信トレイ + 送信済みアイテムを Markdown 化して Obsidian Vault に保存
2. **継続同期**: 初回一括インポート後、差分のみを定期的に追加
3. **構造化**: YAML frontmatter で Dataview クエリ可能にする
4. **再現性**: スレッド単位と個別メール単位の両方の表現を生成
5. **ローカル完結**: ネットワーク経由の認証・API 呼び出しを一切行わない

### 1.3 非目標
- メール送信機能（読み取り専用）
- 添付ファイル本体の保存（メタデータのみ記録）
- クラウドメールサーバーへの直接接続（Exchange Web Services / Graph API は使わない）
- リアルタイム同期（定期ポーリングで十分）

---

## 2. アーキテクチャ

```
┌─────────────────────────────────┐
│  Outlook Desktop (ログイン済み) │
└──────────────┬──────────────────┘
               │ COM (pywin32 / win32com.client)
               ▼
┌─────────────────────────────────┐
│  outlook_to_obsidian (Python)   │
│  ┌───────────────────────────┐  │
│  │ 1. Outlook MAPI 接続      │  │
│  │ 2. メール取得 (差分判定)  │  │
│  │ 3. HTML → Markdown 変換   │  │
│  │ 4. frontmatter 生成       │  │
│  │ 5. スレッド集約           │  │
│  │ 6. SQLite で同期状態管理  │  │
│  └───────────────────────────┘  │
└──────────────┬──────────────────┘
               │ ファイル書き込み
               ▼
┌─────────────────────────────────┐
│  Obsidian Vault                 │
│  ├─ Emails/Messages/*.md        │
│  ├─ Emails/Threads/*.md         │
│  └─ Emails/.sync_state.db       │
└─────────────────────────────────┘
```

### 2.1 技術選定

| コンポーネント | 採用技術 | 理由 |
|---------------|---------|------|
| Outlook 接続 | `pywin32` (win32com.client) | 既存セッション利用、認証不要 |
| HTML→MD 変換 | `markdownify` | Outlook の HTML 構造に強い |
| 同期状態管理 | SQLite (標準ライブラリ) | 外部依存なし、EntryID で重複防止 |
| 設定ファイル | YAML (`PyYAML`) | 可読性 |
| ロギング | 標準 `logging` | 外部依存なし |
| スケジューラ | Windows タスクスケジューラ | OS 標準、追加インストール不要 |

### 2.2 依存ライブラリ

```
pywin32>=306
markdownify>=0.11.6
PyYAML>=6.0.1
python-dateutil>=2.8.2
```

---

## 3. ディレクトリ構成

### 3.1 プロジェクト構成
```
outlook-to-obsidian/
├── README.md                    # セットアップと使い方
├── SPEC.md                      # 本書
├── requirements.txt
├── config.yaml                  # ユーザー設定（要編集）
├── config.example.yaml          # 設定テンプレート
├── run_sync.bat                 # タスクスケジューラから呼ばれるエントリポイント
├── src/
│   ├── __init__.py
│   ├── main.py                  # CLI エントリポイント
│   ├── outlook_client.py        # Outlook COM ラッパー
│   ├── converter.py             # HTML→Markdown 変換
│   ├── note_writer.py           # Markdown ファイル生成
│   ├── thread_builder.py        # スレッド集約
│   ├── sync_state.py            # SQLite 同期状態管理
│   ├── config.py                # 設定ロード
│   └── utils.py                 # 共通ユーティリティ
└── tests/
    ├── test_converter.py
    ├── test_note_writer.py
    └── test_sync_state.py
```

### 3.2 Vault 内の出力構造
```
<VAULT_ROOT>/
└── Emails/
    ├── Messages/
    │   └── 2026/05/             # 受信日でフォルダ分け
    │       └── 2026-05-28_143012_RE-EFTPOS-error_from-tanaka.md
    ├── Threads/
    │   └── thread_<hash>.md     # ConversationID ベースのスレッドノート
    ├── _index/
    │   └── senders.md           # 送信者一覧（任意、Dataview で動的生成も可）
    └── .sync_state.db           # SQLite（Obsidian の検索からは除外）
```

> `.sync_state.db` は Obsidian の `.obsidianignore` で除外可能（後述）。

---

## 4. 機能仕様

### 4.1 メール取得

#### 4.1.1 対象
- **既定フォルダ**: Inbox (受信トレイ) + Sent Items (送信済みアイテム)
- **複数アカウント対応**: Outlook に複数アカウントがある場合、`config.yaml` でアカウント名を指定（未指定なら全アカウントの上記2フォルダを処理）
- **サブフォルダ**: 設定で `include_subfolders: true` の場合は再帰的に処理

#### 4.1.2 取得項目（メール 1 通あたり）
| Outlook プロパティ | frontmatter キー | 用途 |
|-------------------|------------------|------|
| EntryID | `entry_id` | 重複防止の一意キー |
| ConversationID | `conversation_id` | スレッド紐付け |
| ConversationTopic | `conversation_topic` | スレッド見出し |
| Subject | `subject` | 件名 |
| SenderName, SenderEmailAddress | `from` | 差出人 |
| To (受信者リスト) | `to` | 宛先 |
| CC | `cc` | CC |
| ReceivedTime / SentOn | `date` | ISO 8601 |
| Categories | `categories` | Outlook 分類項目 |
| FlagStatus, FlagRequest | `flag` | フラグ状態 |
| Importance | `importance` | 重要度 (low/normal/high) |
| UnRead | `unread` | 既読/未読 |
| Body / HTMLBody | （本文へ） | Markdown 変換対象 |
| Attachments | `attachments` | ファイル名・サイズ・拡張子のリスト（本体は保存しない） |
| Size | `size_bytes` | バイト数 |
| Parent.FolderPath | `folder` | 元フォルダパス |
| - | `direction` | `received` または `sent`（フォルダから判定） |

#### 4.1.3 差分判定
- SQLite テーブル `synced_messages` で EntryID を記録
- 各取り込み実行時、`Items.Restrict("[ReceivedTime] >= '<last_sync_time>'")` で範囲を絞ったあと EntryID で重複除外
- 初回は無制限（全件）

### 4.2 HTML → Markdown 変換

#### 4.2.1 変換方針
1. **優先**: `HTMLBody` が存在すればこちらを `markdownify` で変換
2. **フォールバック**: HTML がない／変換失敗時は `Body` (プレーンテキスト) をそのまま使用
3. **引用処理**: Outlook の引用 (`<blockquote>`) はそのまま `> ` の Markdown 引用に変換
4. **画像**: インライン画像は `[Inline image: <filename>]` のプレースホルダに置換（本体保存しないため）
5. **署名**: 区切り線 `-- ` 以降を `<details><summary>署名</summary>...</details>` で折り畳み

#### 4.2.2 ノイズ除去
- Outlook の Word HTML 由来の不要な class / style を除去
- 連続する空行を 2 行までに圧縮
- ゼロ幅スペース (`\u200b` など) を除去

### 4.3 Markdown ファイル生成

#### 4.3.1 個別メールノート
ファイル名規則:
```
<YYYY-MM-DD>_<HHMMSS>_<sanitized_subject>_<direction-suffix>.md
```
- `sanitized_subject`: 件名から `RE:` / `FW:` を残しつつ、ファイル名禁止文字 (`<>:"/\|?*`) を `-` に置換、80 文字で切り詰め
- `direction-suffix`: `received` のときは省略、`sent` のときは `_sent`
- 衝突時は末尾に `_2`, `_3` を付加

#### 4.3.2 Frontmatter テンプレート
```yaml
---
entry_id: "00000000C7F5..."        # 完全な EntryID
conversation_id: "0100000022..."
conversation_topic: "EFTPOS terminal error at site 0234"
subject: "RE: EFTPOS terminal error at site 0234"
direction: received                  # received / sent
from:
  name: "Tanaka Hiroshi"
  email: "h.tanaka@example.com"
to:
  - name: "Yoshi"
    email: "yoshi@unitedpetroleum.com.au"
cc: []
date: 2026-05-28T14:30:12+10:00
folder: "\\\\yoshi@up.com.au\\Inbox\\Support"
categories: ["EFTPOS", "Urgent"]
flag: "flagged"                      # none / flagged / completed
importance: normal                   # low / normal / high
unread: false
size_bytes: 24531
attachments:
  - name: "screenshot.png"
    size_bytes: 18234
    extension: "png"
tags: [email, received, eftpos]      # categories + direction から自動生成
---

# RE: EFTPOS terminal error at site 0234

**From**: Tanaka Hiroshi <h.tanaka@example.com>
**To**: Yoshi <yoshi@unitedpetroleum.com.au>
**Date**: 2026-05-28 14:30 (+10:00)

> [!info] Thread
> [[thread_a8f3c2d1|EFTPOS terminal error at site 0234]]

---

<メール本文 Markdown>
```

#### 4.3.3 タグ生成ルール
- 常に `email` タグを付与
- `direction` に応じて `received` または `sent` を追加
- Outlook の Categories を小文字化・空白を `-` 置換してタグ化
- フラグ付きなら `flagged` タグ追加

### 4.4 スレッドノート

#### 4.4.1 識別
- `ConversationID` の最初の 8 バイトを 16 進化した値を `thread_<hex8>` として使用
- 同じスレッドの 2 通目以降は、既存スレッドノートに追記（または完全再生成）

#### 4.4.2 構造
```yaml
---
thread_id: "a8f3c2d1"
conversation_id: "0100000022..."
conversation_topic: "EFTPOS terminal error at site 0234"
participants:
  - "Tanaka Hiroshi <h.tanaka@example.com>"
  - "Yoshi <yoshi@unitedpetroleum.com.au>"
message_count: 4
first_message: 2026-05-27T09:15:00+10:00
last_message: 2026-05-28T16:42:00+10:00
tags: [email, thread, eftpos]
---

# EFTPOS terminal error at site 0234

## メッセージ一覧（古い順）

### 2026-05-27 09:15 — Tanaka Hiroshi → Yoshi
[[2026-05-27_091500_EFTPOS-terminal-error-at-site-0234|個別ノート]]

> 本文の最初の 3 行プレビュー...

### 2026-05-27 11:42 — Yoshi → Tanaka Hiroshi  *(自分の返信)*
[[2026-05-27_114200_RE-EFTPOS-terminal-error-at-site-0234_sent|個別ノート]]

> ...
```

- スレッドノートは差分同期のたびに**完全再生成**する（追記式だと順序破綻のリスクが高いため）
- スレッドに属するメッセージは SQLite から `conversation_id` で逆引き

### 4.5 同期状態管理 (SQLite)

#### 4.5.1 スキーマ
```sql
CREATE TABLE synced_messages (
    entry_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    subject TEXT,
    sender_email TEXT,
    direction TEXT NOT NULL,         -- received / sent
    received_at TEXT NOT NULL,       -- ISO 8601
    note_path TEXT NOT NULL,         -- Vault ルートからの相対パス
    folder_path TEXT,
    body_hash TEXT NOT NULL,         -- 本文の SHA-256（編集検知用）
    synced_at TEXT NOT NULL
);

CREATE INDEX idx_conversation ON synced_messages(conversation_id);
CREATE INDEX idx_received_at ON synced_messages(received_at);

CREATE TABLE sync_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    messages_added INTEGER DEFAULT 0,
    messages_skipped INTEGER DEFAULT 0,
    threads_rebuilt INTEGER DEFAULT 0,
    status TEXT,                     -- success / partial / failed
    error_message TEXT
);
```

#### 4.5.2 動作
- 同期前: 最新の `sync_runs.finished_at` を取得（初回は NULL → 全件モード）
- メール毎: EntryID が `synced_messages` に存在すれば原則スキップ
- 編集検知: 本文 SHA-256 が変化していれば再生成
- 同期後: `sync_runs` に結果記録

### 4.6 設定ファイル (config.yaml)

```yaml
# Obsidian Vault のルート絶対パス
vault_path: "C:/Users/Yoshi/Obsidian/WorkVault"

# Vault 内での出力先サブディレクトリ
output_subdir: "Emails"

# Outlook アカウント（未指定 or 空配列なら全アカウント）
accounts: []
# accounts:
#   - "yoshi@unitedpetroleum.com.au"

# 同期対象フォルダ
folders:
  inbox: true              # 受信トレイ
  sent: true               # 送信済みアイテム
  include_subfolders: true # サブフォルダも再帰的に処理

# 除外フォルダ（部分一致）
excluded_folders:
  - "Junk"
  - "Deleted Items"
  - "RSS Subscriptions"

# 出力オプション
output:
  generate_threads: true        # スレッドノートを生成
  filename_max_length: 80       # ファイル名最大長
  preserve_html_signatures: false  # 署名を折り畳むか

# 差分同期
sync:
  initial_full_import: true     # 初回は全件取り込み
  lookback_buffer_minutes: 60   # 差分同期時のバッファ（時計ズレ対策）

# ロギング
logging:
  level: INFO                   # DEBUG / INFO / WARNING / ERROR
  file: "logs/sync.log"
  max_size_mb: 10
  backup_count: 5
```

### 4.7 CLI

```bash
# 通常同期（差分）
python -m src.main sync

# 初回フル同期（強制）
python -m src.main sync --full

# ドライラン（書き込まずに件数だけ表示）
python -m src.main sync --dry-run

# 特定の日付以降のみ
python -m src.main sync --since 2026-01-01

# スレッドノートのみ再生成
python -m src.main rebuild-threads

# 同期状態の表示
python -m src.main status

# DB リセット（確認プロンプトあり）
python -m src.main reset --confirm
```

### 4.8 タスクスケジューラ統合

`run_sync.bat`:
```bat
@echo off
cd /d "%~dp0"
"C:\Python311\python.exe" -m src.main sync >> logs\scheduled.log 2>&1
```

タスクスケジューラ登録例（README に記載）:
- トリガー: ログオン時 + 1 時間ごと
- 操作: `run_sync.bat`
- 実行ユーザー: 現在のユーザー（Outlook セッション共有のため）
- 「最高の権限で実行」は **不要**（むしろ Outlook と権限ズレが起きるので付けない）

---

## 5. エラーハンドリング

### 5.1 想定エラーと対応

| エラー | 対応 |
|-------|------|
| Outlook 未起動 | 起動を試行（`Outlook.Application` の COM 生成）→ 失敗時はログ記録して終了 |
| MAPI セキュリティプロンプト | ドキュメントで Trust Center の "Programmatic Access" 設定を案内 |
| メール本文が破損 | そのメールはスキップ、ログに WARN |
| Vault パス不在 | エラー終了（手動修復が必要） |
| ファイル書き込み権限なし | エラー終了 |
| 同名ファイル衝突 | 末尾に `_2`, `_3` を付加 |
| HTML 変換例外 | プレーンテキストにフォールバック、ログに WARN |
| SQLite ロック | 3 回まで指数バックオフでリトライ |

### 5.2 ログ
- すべての操作を `logs/sync.log` にローテーション付きで記録
- 構造化ログ形式: `<timestamp> <level> <module> <message>`
- 1 回の同期サマリーは INFO レベルで先頭に出力

---

## 6. セキュリティと運用配慮

### 6.1 セキュリティ
- **ローカル完結**: ネットワーク I/O なし
- **認証情報を扱わない**: Outlook 既存セッション利用のため資格情報の保管不要
- **添付ファイル本体は保存しない**: 機密文書の意図しない複製を防止
- **`.sync_state.db` は Vault 同期から除外**: クラウド同期（Obsidian Sync 等）に SQLite を載せない

### 6.2 Obsidian 側設定
README に以下を案内:
- `.obsidianignore` に `Emails/.sync_state.db` を追加
- Dataview プラグイン推奨（フロントマターを活かす）
- メールフォルダはグラフビューから除外推奨（ノードが大量に増えるため）

### 6.3 業務情報の取扱注意
- ユーザー責任で Vault 自体の暗号化・バックアップを行うこと
- README に明記

---

## 7. テスト戦略

### 7.1 ユニットテスト対象
- `converter.py`: 既知の HTML サンプル → 期待 Markdown
- `note_writer.py`: ファイル名サニタイズ・衝突回避
- `sync_state.py`: SQLite CRUD・差分判定ロジック
- `thread_builder.py`: 複数メッセージのスレッド構築

### 7.2 統合テスト
- モック Outlook オブジェクト（`unittest.mock`）で 10 通のサンプルメールを流す
- 初回フル → 差分同期 → スレッド再生成の一連シナリオを検証

### 7.3 手動受け入れテスト（README に記載）
1. 5 通程度のサンプルでフル同期実行
2. Vault 内のノートを Obsidian で確認
3. 同じコマンドを再実行 → "messages_added: 0" になることを確認
4. 新規メール 1 通受信後、再実行 → 1 件のみ追加されることを確認

---

## 8. 開発手順（Claude Code 向け）

### 8.1 推奨実装順序
1. `config.py` + `config.example.yaml` — 設定ロード
2. `sync_state.py` — SQLite スキーマと CRUD
3. `outlook_client.py` — pywin32 ラッパー（モック可能な設計に）
4. `converter.py` — HTML→Markdown 変換と署名処理
5. `note_writer.py` — ファイル生成とファイル名サニタイズ
6. `thread_builder.py` — スレッドノート生成
7. `main.py` — CLI と各コンポーネントの統合
8. `tests/` — 各モジュールに対応するテスト
9. `run_sync.bat` + `README.md` — デプロイ手順

### 8.2 コーディング規約
- Python 3.11+ 構文（型ヒント必須、`from __future__ import annotations`）
- フォーマッタ: `ruff format`
- リンター: `ruff check`
- docstring: Google スタイル
- 全モジュールで `logging.getLogger(__name__)` を使用

### 8.3 完了条件
- [ ] `python -m src.main sync --dry-run` がエラーなく完走する（モック環境含む）
- [ ] ユニットテストが全パス（`pytest tests/`）
- [ ] README に従えば未経験者でもセットアップ可能
- [ ] サンプル 1 通で実機動作確認（手動）

---

## 9. 変更確認点（実装中に最適解で決定した項目 — 後で見直し可能）

> 以下は仕様確定時に明示的に選択肢を提示しなかった項目について、**最適解で先行決定**した点です。完成後にユーザーが見直して仕様変更したい箇所をここから選んでください。

### 9.1 ⚙️ 決定事項一覧

| # | 項目 | 採用した最適解 | 代替案 |
|---|------|---------------|-------|
| 1 | 取り込みフォルダのデフォルト | 受信トレイ + 送信済みアイテム + サブフォルダ再帰 | サブフォルダを除外する／特定フォルダのみホワイトリスト |
| 2 | 除外フォルダ | Junk / Deleted Items / RSS Subscriptions | Drafts も除外する／除外しない |
| 3 | 添付ファイル | メタデータ（ファイル名・サイズ・拡張子）のみ記録 | ファイル名のみ／完全無視 |
| 4 | インライン画像 | プレースホルダ `[Inline image: <filename>]` に置換 | 完全削除／base64 で埋め込み |
| 5 | 署名処理 | `-- ` 区切り以降を `<details>` で折り畳み | 削除する／そのまま残す |
| 6 | ファイル名規則 | `YYYY-MM-DD_HHMMSS_subject_direction.md` | UUID ベース／件名のみ |
| 7 | フォルダ構成 | 年/月で分割 (`Messages/2026/05/`) | フラット／日単位／送信者別 |
| 8 | スレッド表現 | 個別ノートとスレッドノートの両方を生成 | 片方のみ |
| 9 | スレッドノートの更新方式 | 毎回完全再生成 | 追記式（順序破綻リスクあり） |
| 10 | タグ自動生成 | `email` + `received`/`sent` + Outlook Categories + `flagged` | 最小化（`email` のみ）／全部展開 |
| 11 | 編集検知 | 本文 SHA-256 比較で再生成 | EntryID 一致なら常にスキップ |
| 12 | 同期間隔（推奨） | 1 時間ごと + ログオン時 | 15 分／4 時間／手動のみ |
| 13 | ログレベル | INFO（ファイル）/ WARNING（コンソール） | DEBUG ベース |
| 14 | ログローテーション | 10MB × 5 世代 | 日次／無制限 |
| 15 | DB の場所 | `<vault>/Emails/.sync_state.db` | プロジェクトディレクトリ内／ユーザーホーム |
| 16 | スレッド ID | ConversationID の先頭 8 バイトの hex | 完全 ConversationID／件名ハッシュ |
| 17 | タイムゾーン | Outlook のローカル時刻を ISO 8601 で `+10:00` 付与 | UTC 統一／タイムゾーン情報なし |
| 18 | 差分同期バッファ | 60 分前から再スキャン（時計ズレ対策） | 0 分／24 時間 |
| 19 | 複数アカウント | 全アカウントを対象（設定で絞り込み可） | プライマリのみ |
| 20 | テスト方針 | モック Outlook で統合テスト | 実機テストのみ |

### 9.2 🔍 ユーザー確認推奨ポイント（優先度高）

実装完了後、特に以下を確認してください:

1. **Vault パス** (`config.yaml`): デフォルトは `C:/Users/Yoshi/Obsidian/WorkVault` — 実環境に合わせて修正必須
2. **アカウント指定**: 個人アカウントと業務アカウントが両方 Outlook に登録されている場合、業務のみに絞るべきか
3. **送信済みアイテムの範囲**: 個人的な返信も全部取り込むか、特定フォルダに整理済みのもののみか
4. **同期頻度**: 1 時間ごとは適切か（バッテリ駆動時など考慮）
5. **添付メタデータの粒度**: 件名や本文に添付情報を併記すべきか

---

## 10. 参考情報

### 10.1 Outlook COM オブジェクトモデル
- 主要オブジェクト: `Application` → `Namespace("MAPI")` → `Folders` → `Items` → `MailItem`
- `MailItem.Class == 43` がメール（その他: 予定 26, 連絡先 40 など）
- EntryID は Outlook プロファイル間で**変わる可能性がある**ため、プロファイル変更時は再フル同期が必要 → README に注記

### 10.2 既知の制約
- Outlook が起動していないと COM 経由でアクセスできない
- Outlook の "Programmatic Access" 警告が出る場合あり（README で対処法を案内）
- 大量メール（10,000 件超）の初回同期は数十分かかる可能性あり → 進捗バー表示で対応

### 10.3 将来拡張の余地（本実装では未対応）
- カレンダー・タスクへの拡張
- 双方向同期（Obsidian でフラグを変えると Outlook に反映）
- LLM による自動要約・タグ付け
- ベクトル DB（Chroma / LanceDB）連携で意味検索
- 自分の返信スタイル学習 → Claude Code でドラフト生成パイプライン
