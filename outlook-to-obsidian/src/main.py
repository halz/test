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
from .outlook_client import OutlookClientBase, OutlookClientError, build_client
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
    until: datetime | None = None,
    use_mock: bool = False,
    skip_body: bool = False,
    client: OutlookClientBase | None = None,
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
        if client is None:
            client = build_client(config, use_mock=use_mock)
            if not use_mock and not client.is_available():
                raise OutlookClientError(
                    "Microsoft Outlook is not running or not scriptable (macOS + Outlook required)"
                )

        since_filter = _determine_since(state, config, full=full, since=since)
        logger.info(
            "Sync start (full=%s dry_run=%s mock=%s since=%s until=%s)",
            full,
            dry_run,
            use_mock,
            since_filter,
            until,
        )

        for index, record in enumerate(
            client.iter_messages(since_filter, until, skip_body=skip_body), start=1
        ):
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
            skip_body=args.skip_body,
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


def plan_backfill_chunks(
    end: datetime, cutoff: datetime, chunk_days: int
) -> list[tuple[datetime, datetime]]:
    """Return the list of ``(start, end)`` windows to sync, walking backwards."""
    if chunk_days < 1:
        raise ValueError("chunk_days must be >= 1")
    if end <= cutoff:
        return []
    chunks: list[tuple[datetime, datetime]] = []
    cursor = end
    while cursor > cutoff:
        start = cursor - timedelta(days=chunk_days)
        if start < cutoff:
            start = cutoff
        chunks.append((start, cursor))
        cursor = start
    return chunks


def cmd_backfill(args: argparse.Namespace, config: Config) -> int:
    from datetime import timezone

    if args.cutoff:
        cutoff = _parse_since(args.cutoff)
    else:
        cutoff = datetime.now(timezone.utc).astimezone() - timedelta(
            days=365 * args.years_back
        )
    end = datetime.now(timezone.utc).astimezone() + timedelta(days=1)
    chunks = plan_backfill_chunks(end, cutoff, args.chunk_days)

    print(
        f"Backfill plan: {len(chunks)} chunks of {args.chunk_days} days, "
        f"from {end.date()} back to {cutoff.date()}"
    )
    if args.plan:
        for start, stop in chunks:
            print(f"  [chunk] {start.date()} → {stop.date()}")
        return 0

    total_added = total_skipped = empty_streak = chunk_idx = 0
    for start, stop in chunks:
        chunk_idx += 1
        print(
            f"[chunk {chunk_idx}/{len(chunks)}] {start.date()} → {stop.date()}",
            flush=True,
        )
        try:
            summary = run_sync(
                config,
                since=start,
                until=stop,
                use_mock=args.mock,
                skip_body=args.skip_body,
            )
        except (FileNotFoundError, OutlookClientError) as exc:
            print(f"  chunk failed: {exc}; continuing", file=sys.stderr)
            continue
        added = summary["messages_added"]
        skipped = summary["messages_skipped"]
        total_added += added
        total_skipped += skipped
        print(f"  added={added} skipped={skipped}")
        if added == 0 and skipped == 0:
            empty_streak += 1
            if empty_streak >= args.stop_after_empty:
                print(
                    f"Stopping early: {empty_streak} consecutive empty chunks "
                    f"(threshold: --stop-after-empty {args.stop_after_empty})"
                )
                break
        else:
            empty_streak = 0
    print(
        f"\nBackfill done: {chunk_idx} chunk(s) processed, "
        f"added={total_added} skipped={total_skipped}"
    )
    return 0


def cmd_import_eml(args: argparse.Namespace, config: Config) -> int:
    from . import eml_importer

    paths = [Path(p) for p in args.paths]
    print(f"Scanning {len(paths)} path(s) for .eml files...", flush=True)
    records = list(
        eml_importer.parse_eml_paths(
            paths, direction=args.direction, folder=args.folder
        )
    )
    print(f"Parsed {len(records)} EML file(s)")
    if not records:
        return 0
    client = eml_importer.EmlClient(records)
    try:
        summary = run_sync(
            config,
            client=client,
            since=_parse_since(args.since),
            dry_run=args.dry_run,
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


def cmd_import_olm(args: argparse.Namespace, config: Config) -> int:
    from . import olm_importer

    olm_path = Path(args.olm)
    if not olm_path.exists():
        print(f"ERROR: file not found: {olm_path}", file=sys.stderr)
        return 1
    print(f"Reading {olm_path} ...", flush=True)
    records = list(olm_importer.iter_messages_from_olm(olm_path))
    print(f"Parsed {len(records)} message(s) from .olm")
    if not records:
        return 0
    client = olm_importer.OlmClient(records)
    try:
        summary = run_sync(
            config,
            client=client,
            since=_parse_since(args.since),
            dry_run=args.dry_run,
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


def cmd_doctor(args: argparse.Namespace, config: Config) -> int:
    print("=== Outlook → Obsidian Doctor ===")
    print(f"Vault:              {config.vault_path}")
    print(f"Output subdir:      {config.output_subdir!r} → {config.emails_dir}")
    print(f"Excluded folders:   {config.excluded_folders}")
    print(f"Include subfolders: {config.folders.include_subfolders}")
    print(f"Inbox enabled:      {config.folders.inbox}")
    print(f"Sent enabled:       {config.folders.sent}")
    print(f"DB path:            {config.db_path}")
    if config.db_path.exists():
        with SyncState(config.db_path) as state:
            print(f"DB messages:        {state.message_count()}")
            print(f"Last OK:            {state.last_successful_sync() or '(never)'}")
    else:
        print("DB:                 (does not exist)")
    print("--- Outlook probe ---")
    client = build_client(config, use_mock=args.mock)
    print(client.diagnose())
    return 0


def cmd_gui(args: argparse.Namespace, config: Config) -> int:
    from . import gui

    return gui.launch(config, args.config)


def cmd_schedule(args: argparse.Namespace, config: Config) -> int:
    from . import scheduler

    try:
        if args.action == "install":
            return scheduler.install(config, args.config)
        if args.action == "uninstall":
            return scheduler.uninstall()
        if args.action == "status":
            return scheduler.status()
        if args.action == "preview":
            return scheduler.preview(config, args.config)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 2


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
    p_sync.add_argument(
        "--skip-body",
        action="store_true",
        help="Don't fetch message body content (metadata-only; fast for uncached mail)",
    )
    p_sync.set_defaults(func=cmd_sync)

    p_backfill = sub.add_parser(
        "backfill",
        help="Chunked backward import of historical mail (resumable, bounded per chunk)",
    )
    p_backfill.add_argument(
        "--chunk-days", type=int, default=30, help="Days per chunk (default 30)"
    )
    p_backfill.add_argument(
        "--cutoff",
        help="Don't sync mail received before this date (YYYY-MM-DD). "
        "Default: --years-back years before today.",
    )
    p_backfill.add_argument(
        "--years-back",
        type=int,
        default=5,
        help="Default cutoff: N years before today when --cutoff not given (default 5)",
    )
    p_backfill.add_argument(
        "--stop-after-empty",
        type=int,
        default=3,
        help="Stop after N consecutive chunks returning 0 messages (default 3)",
    )
    p_backfill.add_argument(
        "--plan",
        action="store_true",
        help="Print the planned chunks and exit without running them",
    )
    p_backfill.add_argument(
        "--mock", action="store_true", help="Use built-in sample data"
    )
    p_backfill.add_argument(
        "--skip-body",
        action="store_true",
        help="Don't fetch message body content. Recommended for historical "
        "backfill — body fetch is the dominant per-message cost when mail "
        "isn't locally cached. Re-run sync later to fill bodies in.",
    )
    p_backfill.set_defaults(func=cmd_backfill)

    p_eml = sub.add_parser(
        "import-eml",
        help="Import emails from local .eml files (no Outlook / AppleScript)",
    )
    p_eml.add_argument(
        "paths",
        nargs="+",
        help="Files or directories to scan for .eml (directories are recursive)",
    )
    p_eml.add_argument(
        "--direction",
        default="auto",
        choices=["auto", "received", "sent"],
        help="Default direction (auto = infer per file from parent dir name)",
    )
    p_eml.add_argument(
        "--folder", help="Folder name to record (default: parent directory name)"
    )
    p_eml.add_argument("--since", help="Only EMLs on/after this date (YYYY-MM-DD)")
    p_eml.add_argument(
        "--dry-run", action="store_true", help="Count only; don't write notes"
    )
    p_eml.set_defaults(func=cmd_import_eml)

    p_olm = sub.add_parser(
        "import-olm",
        help="Import emails from an Outlook for Mac .olm archive",
    )
    p_olm.add_argument("olm", help="Path to the .olm archive")
    p_olm.add_argument("--since", help="Only messages on/after this date (YYYY-MM-DD)")
    p_olm.add_argument(
        "--dry-run", action="store_true", help="Count only; don't write notes"
    )
    p_olm.set_defaults(func=cmd_import_olm)

    p_rebuild = sub.add_parser("rebuild-threads", help="Regenerate thread notes only")
    p_rebuild.set_defaults(func=cmd_rebuild_threads)

    p_status = sub.add_parser("status", help="Show sync state")
    p_status.set_defaults(func=cmd_status)

    p_reset = sub.add_parser("reset", help="Wipe the sync DB")
    p_reset.add_argument("--confirm", action="store_true", help="Skip the prompt")
    p_reset.set_defaults(func=cmd_reset)

    p_doctor = sub.add_parser("doctor", help="Probe Outlook scripting access and config")
    p_doctor.add_argument("--mock", action="store_true", help="Use built-in sample data")
    p_doctor.set_defaults(func=cmd_doctor)

    p_gui = sub.add_parser("gui", help="Open the status window")
    p_gui.set_defaults(func=cmd_gui)

    p_schedule = sub.add_parser(
        "schedule", help="Manage the macOS launchd LaunchAgent schedule"
    )
    schedule_sub = p_schedule.add_subparsers(dest="action", required=True)
    schedule_sub.add_parser("install", help="Install/reload the LaunchAgent from config.yaml")
    schedule_sub.add_parser("uninstall", help="Stop and remove the LaunchAgent")
    schedule_sub.add_parser("status", help="Show LaunchAgent status")
    schedule_sub.add_parser("preview", help="Print the generated plist without installing")
    p_schedule.set_defaults(func=cmd_schedule)

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
