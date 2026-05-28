"""CLI entry point wiring every component together.

    python -m src.main sync                # incremental sync
    python -m src.main sync --full         # force full re-import
    python -m src.main sync --dry-run      # count only, write nothing
    python -m src.main sync --mock         # use built-in sample data (no Outlook)
    python -m src.main sync --since 2026-01-01
    python -m src.main rebuild-threads     # regenerate thread notes only
    python -m src.main status              # show sync state
    python -m src.main reset --confirm     # wipe the sync DB
    python -m src.main gui                 # open the status window
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from dateutil import parser as date_parser

from .config import Config
from .converter import message_body_markdown
from .note_writer import (
    build_filename,
    build_message_note,
    generate_tags,
    message_subdir,
    unique_path,
    write_note,
)
from .outlook_client import OutlookClientError, build_client
from .sync_state import SyncState
from .thread_builder import rebuild_threads, thread_id_from_conversation
from .utils import iso8601, setup_logging, sha256_text

logger = logging.getLogger(__name__)


def _parse_since(value: str | None) -> datetime | None:
    if not value:
        return None
    dt = date_parser.parse(value)
    return dt if dt.tzinfo else dt.astimezone()


def _is_excluded(folder_path: str, excluded: list[str]) -> bool:
    folder = (folder_path or "").lower()
    return any(ex.lower() in folder for ex in excluded if ex)


def _determine_since(
    state: SyncState, config: Config, *, full: bool, since: datetime | None
) -> datetime | None:
    if since is not None:
        return since
    if full:
        return None
    last = state.last_successful_sync()
    if not last:
        # First run: full import (matches sync.initial_full_import).
        return None
    last_dt = date_parser.isoparse(last)
    return last_dt - timedelta(minutes=config.sync.lookback_buffer_minutes)


def run_sync(
    config: Config,
    *,
    full: bool = False,
    dry_run: bool = False,
    since: datetime | None = None,
    use_mock: bool = False,
) -> dict[str, Any]:
    """Execute a sync and return a summary dict."""
    if not dry_run and not config.vault_path.exists():
        raise FileNotFoundError(f"Vault path does not exist: {config.vault_path}")

    state = SyncState(":memory:" if dry_run else config.db_path)
    fold = not config.output.preserve_html_signatures
    taken: set[str] = set()
    added = skipped = 0
    run_id = state.start_run()

    try:
        client = build_client(config, use_mock=use_mock)
        if not use_mock and not client.is_available():
            raise OutlookClientError(
                "Microsoft Outlook is not running or not scriptable (macOS + Outlook required)"
            )

        since_filter = _determine_since(state, config, full=full, since=since)
        logger.info(
            "Sync start (full=%s dry_run=%s mock=%s since=%s)",
            full,
            dry_run,
            use_mock,
            since_filter,
        )

        for index, record in enumerate(client.iter_messages(since_filter), start=1):
            if _is_excluded(record.folder_path, config.excluded_folders):
                continue

            body_md = message_body_markdown(
                record.body_html, record.body_plain, fold_signatures=fold
            )
            body_hash = sha256_text(body_md)
            existing = state.get_message(record.entry_id)
            if existing and existing["body_hash"] == body_hash:
                skipped += 1
                continue

            if existing:
                note_path = config.vault_path / existing["note_path"]
            else:
                directory = message_subdir(config.messages_dir, record)
                filename = build_filename(record, config.output.filename_max_length)
                note_path = unique_path(directory, filename, taken)

            rel_path = note_path.relative_to(config.vault_path).as_posix()
            thread_id = thread_id_from_conversation(record.conversation_id)
            tags = generate_tags(record)
            content = build_message_note(record, thread_id, body_md, tags)

            if not dry_run:
                write_note(note_path, content)

            state.upsert_message(
                entry_id=record.entry_id,
                conversation_id=record.conversation_id,
                subject=record.subject,
                sender_email=record.sender_email,
                direction=record.direction,
                received_at=iso8601(record.date),
                note_path=rel_path,
                folder_path=record.folder_path,
                body_hash=body_hash,
            )
            added += 1
            if index % 50 == 0:
                logger.info("Processed %d messages...", index)

        threads_rebuilt = 0
        if config.output.generate_threads and not dry_run:
            threads_rebuilt = rebuild_threads(state, config)

        state.finish_run(
            run_id,
            messages_added=added,
            messages_skipped=skipped,
            threads_rebuilt=threads_rebuilt,
            status="success",
        )
        summary = {
            "messages_added": added,
            "messages_skipped": skipped,
            "threads_rebuilt": threads_rebuilt,
            "dry_run": dry_run,
        }
        logger.info("Sync summary: %s", summary)
        return summary
    except Exception as exc:
        state.finish_run(
            run_id,
            messages_added=added,
            messages_skipped=skipped,
            threads_rebuilt=0,
            status="failed",
            error_message=str(exc),
        )
        logger.error("Sync failed: %s", exc, exc_info=True)
        raise
    finally:
        state.close()


def cmd_sync(args: argparse.Namespace, config: Config) -> int:
    try:
        summary = run_sync(
            config,
            full=args.full,
            dry_run=args.dry_run,
            since=_parse_since(args.since),
            use_mock=args.mock,
        )
    except (FileNotFoundError, OutlookClientError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    prefix = "[dry-run] " if summary["dry_run"] else ""
    print(
        f"{prefix}added={summary['messages_added']} "
        f"skipped={summary['messages_skipped']} "
        f"threads_rebuilt={summary['threads_rebuilt']}"
    )
    return 0


def cmd_rebuild_threads(args: argparse.Namespace, config: Config) -> int:
    if not config.vault_path.exists():
        print(f"ERROR: Vault path does not exist: {config.vault_path}", file=sys.stderr)
        return 1
    with SyncState(config.db_path) as state:
        count = rebuild_threads(state, config)
    print(f"threads_rebuilt={count}")
    return 0


def cmd_status(args: argparse.Namespace, config: Config) -> int:
    if not config.db_path.exists():
        print("No sync state yet (database not found). Run `sync` first.")
        return 0
    with SyncState(config.db_path) as state:
        print(f"Vault:    {config.vault_path}")
        print(f"DB:       {config.db_path}")
        print(f"Messages: {state.message_count()}")
        last = state.last_successful_sync()
        print(f"Last OK:  {last or '(never)'}")
        print("Recent runs:")
        for run in state.recent_runs():
            print(
                f"  #{run['id']} {run['status']:<8} "
                f"added={run['messages_added']} skipped={run['messages_skipped']} "
                f"threads={run['threads_rebuilt']} @ {run['finished_at'] or run['started_at']}"
            )
    return 0


def cmd_reset(args: argparse.Namespace, config: Config) -> int:
    if not args.confirm:
        reply = input(f"Wipe sync DB at {config.db_path}? Type 'yes' to confirm: ")
        if reply.strip().lower() != "yes":
            print("Aborted.")
            return 1
    if config.db_path.exists():
        with SyncState(config.db_path) as state:
            state.reset()
        print("Sync DB reset. (Markdown notes were NOT deleted.)")
    else:
        print("No DB to reset.")
    return 0


def cmd_gui(args: argparse.Namespace, config: Config) -> int:
    from . import gui

    return gui.launch(config, args.config)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="outlook-to-obsidian")
    parser.add_argument(
        "-c", "--config", default="config.yaml", help="Path to config.yaml"
    )
    parser.add_argument(
        "--output-subdir",
        help="Override config.output_subdir (vault-relative output folder, e.g. 'Work/Emails')",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_sync = sub.add_parser("sync", help="Sync messages (incremental by default)")
    p_sync.add_argument("--full", action="store_true", help="Force full re-import")
    p_sync.add_argument("--dry-run", action="store_true", help="Count only, write nothing")
    p_sync.add_argument("--since", help="Only messages on/after this date (YYYY-MM-DD)")
    p_sync.add_argument("--mock", action="store_true", help="Use built-in sample data")
    p_sync.set_defaults(func=cmd_sync)

    p_rebuild = sub.add_parser("rebuild-threads", help="Regenerate thread notes only")
    p_rebuild.set_defaults(func=cmd_rebuild_threads)

    p_status = sub.add_parser("status", help="Show sync state")
    p_status.set_defaults(func=cmd_status)

    p_reset = sub.add_parser("reset", help="Wipe the sync DB")
    p_reset.add_argument("--confirm", action="store_true", help="Skip the prompt")
    p_reset.set_defaults(func=cmd_reset)

    p_gui = sub.add_parser("gui", help="Open the status window")
    p_gui.set_defaults(func=cmd_gui)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        config = Config.load(args.config)
    except (FileNotFoundError, ValueError) as exc:
        print(f"ERROR: cannot load config '{args.config}': {exc}", file=sys.stderr)
        return 2

    if args.output_subdir:
        config.output_subdir = args.output_subdir

    setup_logging(
        level=config.logging.level,
        log_file=config.logging.file,
        max_size_mb=config.logging.max_size_mb,
        backup_count=config.logging.backup_count,
    )
    return int(args.func(args, config))


if __name__ == "__main__":
    raise SystemExit(main())
