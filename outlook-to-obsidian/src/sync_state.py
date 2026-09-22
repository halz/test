"""SQLite-backed sync state: dedupe by message id and track sync runs.

The database lives at ``<vault>/Emails/.sync_state.db`` and is intentionally kept
out of any Obsidian/cloud sync (see README). All writes go through a small retry
wrapper so a transient ``database is locked`` does not abort a run.
"""

from __future__ import annotations

import logging
import sqlite3
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TypeVar

logger = logging.getLogger(__name__)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS synced_messages (
    entry_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    subject TEXT,
    sender_email TEXT,
    direction TEXT NOT NULL,
    received_at TEXT NOT NULL,
    note_path TEXT NOT NULL,
    folder_path TEXT,
    body_hash TEXT NOT NULL,
    synced_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_conversation ON synced_messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_received_at ON synced_messages(received_at);

CREATE TABLE IF NOT EXISTS sync_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    messages_added INTEGER DEFAULT 0,
    messages_skipped INTEGER DEFAULT 0,
    threads_rebuilt INTEGER DEFAULT 0,
    status TEXT,
    error_message TEXT
);
"""

T = TypeVar("T")


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat()


def _with_retry(func: Callable[[], T], attempts: int = 3) -> T:
    """Run ``func`` retrying on SQLite lock contention with backoff (1s, 2s)."""
    last_error: sqlite3.OperationalError | None = None
    for attempt in range(attempts):
        try:
            return func()
        except sqlite3.OperationalError as exc:
            if "lock" not in str(exc).lower():
                raise
            last_error = exc
            wait = 2**attempt
            logger.warning("SQLite locked, retrying in %ss (attempt %d)", wait, attempt + 1)
            time.sleep(wait)
    assert last_error is not None
    raise last_error


class SyncState:
    """Wrapper around the sync-state database."""

    def __init__(self, db_path: str | Path):
        self.db_path = str(db_path)
        if self.db_path != ":memory:":
            Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.db_path, timeout=30)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(_SCHEMA)
        self.conn.commit()

    # -- messages -----------------------------------------------------------
    def get_message(self, entry_id: str) -> sqlite3.Row | None:
        """Return the stored row for ``entry_id`` or ``None``."""
        cur = self.conn.execute(
            "SELECT * FROM synced_messages WHERE entry_id = ?", (entry_id,)
        )
        return cur.fetchone()

    def is_synced(self, entry_id: str) -> bool:
        return self.get_message(entry_id) is not None

    def upsert_message(
        self,
        *,
        entry_id: str,
        conversation_id: str,
        subject: str,
        sender_email: str,
        direction: str,
        received_at: str,
        note_path: str,
        folder_path: str,
        body_hash: str,
    ) -> None:
        """Insert or update the record for a single message."""

        def _op() -> None:
            self.conn.execute(
                """
                INSERT INTO synced_messages (
                    entry_id, conversation_id, subject, sender_email, direction,
                    received_at, note_path, folder_path, body_hash, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(entry_id) DO UPDATE SET
                    conversation_id=excluded.conversation_id,
                    subject=excluded.subject,
                    sender_email=excluded.sender_email,
                    direction=excluded.direction,
                    received_at=excluded.received_at,
                    note_path=excluded.note_path,
                    folder_path=excluded.folder_path,
                    body_hash=excluded.body_hash,
                    synced_at=excluded.synced_at
                """,
                (
                    entry_id,
                    conversation_id,
                    subject,
                    sender_email,
                    direction,
                    received_at,
                    note_path,
                    folder_path,
                    body_hash,
                    _now(),
                ),
            )
            self.conn.commit()

        _with_retry(_op)

    def messages_for_conversation(self, conversation_id: str) -> list[sqlite3.Row]:
        """Return all messages for a conversation, oldest first."""
        cur = self.conn.execute(
            "SELECT * FROM synced_messages WHERE conversation_id = ? ORDER BY received_at ASC",
            (conversation_id,),
        )
        return cur.fetchall()

    def all_conversation_ids(self) -> list[str]:
        cur = self.conn.execute(
            "SELECT DISTINCT conversation_id FROM synced_messages"
        )
        return [row[0] for row in cur.fetchall()]

    def message_count(self) -> int:
        cur = self.conn.execute("SELECT COUNT(*) FROM synced_messages")
        return int(cur.fetchone()[0])

    # -- runs ---------------------------------------------------------------
    def last_successful_sync(self) -> str | None:
        """Return the ``finished_at`` of the most recent successful run."""
        cur = self.conn.execute(
            """
            SELECT finished_at FROM sync_runs
            WHERE status = 'success' AND finished_at IS NOT NULL
            ORDER BY finished_at DESC LIMIT 1
            """
        )
        row = cur.fetchone()
        return row[0] if row else None

    def start_run(self) -> int:
        cur = self.conn.execute(
            "INSERT INTO sync_runs (started_at, status) VALUES (?, 'running')",
            (_now(),),
        )
        self.conn.commit()
        return int(cur.lastrowid)

    def finish_run(
        self,
        run_id: int,
        *,
        messages_added: int,
        messages_skipped: int,
        threads_rebuilt: int,
        status: str,
        error_message: str | None = None,
    ) -> None:
        def _op() -> None:
            self.conn.execute(
                """
                UPDATE sync_runs SET
                    finished_at = ?, messages_added = ?, messages_skipped = ?,
                    threads_rebuilt = ?, status = ?, error_message = ?
                WHERE id = ?
                """,
                (
                    _now(),
                    messages_added,
                    messages_skipped,
                    threads_rebuilt,
                    status,
                    error_message,
                    run_id,
                ),
            )
            self.conn.commit()

        _with_retry(_op)

    def recent_runs(self, limit: int = 5) -> list[sqlite3.Row]:
        cur = self.conn.execute(
            "SELECT * FROM sync_runs ORDER BY id DESC LIMIT ?", (limit,)
        )
        return cur.fetchall()

    # -- maintenance --------------------------------------------------------
    def reset(self) -> None:
        """Drop and recreate all tables."""
        self.conn.executescript(
            "DROP TABLE IF EXISTS synced_messages; DROP TABLE IF EXISTS sync_runs;"
        )
        self.conn.executescript(_SCHEMA)
        self.conn.commit()

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "SyncState":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()
