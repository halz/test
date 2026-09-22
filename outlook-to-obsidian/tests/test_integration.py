from __future__ import annotations

import yaml

from src.config import Config
from src.main import build_parser, run_sync


def _config(tmp_path) -> Config:
    vault = tmp_path / "vault"
    vault.mkdir()
    return Config(vault_path=vault)


def test_full_then_idempotent(tmp_path) -> None:
    config = _config(tmp_path)

    first = run_sync(config, full=True, use_mock=True)
    assert first["messages_added"] == 2
    assert first["threads_rebuilt"] == 1
    assert len(list(config.messages_dir.rglob("*.md"))) == 2
    assert len(list(config.threads_dir.glob("thread_*.md"))) == 1

    # Re-run with --full to bypass the time-based filter: everything is a skip.
    second = run_sync(config, full=True, use_mock=True)
    assert second["messages_added"] == 0
    assert second["messages_skipped"] == 2


def test_dry_run_writes_nothing(tmp_path) -> None:
    config = _config(tmp_path)
    summary = run_sync(config, full=True, dry_run=True, use_mock=True)
    assert summary["messages_added"] == 2
    assert not config.messages_dir.exists()


def test_note_frontmatter_valid(tmp_path) -> None:
    config = _config(tmp_path)
    run_sync(config, full=True, use_mock=True)
    note = sorted(config.messages_dir.rglob("*.md"))[0]
    text = note.read_text(encoding="utf-8")
    front = yaml.safe_load(text.split("---")[1])
    assert front["entry_id"] in {"0001", "0002"}
    assert "email" in front["tags"]
    assert front["conversation_topic"] == "EFTPOS terminal error at site 0234"


def test_output_subdir_override(tmp_path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    config = Config(vault_path=vault, output_subdir="Work/Mail")
    summary = run_sync(config, full=True, use_mock=True)
    assert summary["messages_added"] == 2
    out = vault / "Work" / "Mail"
    assert list((out / "Messages").rglob("*.md"))
    assert list((out / "Threads").glob("thread_*.md"))
    assert (out / ".sync_state.db").exists()


def test_cli_output_subdir_flag_is_global() -> None:
    args = build_parser().parse_args(["--output-subdir", "X/Y", "sync", "--full"])
    assert args.output_subdir == "X/Y"
    assert args.command == "sync"
    assert args.full is True


def test_plan_backfill_chunks_walks_backwards() -> None:
    from datetime import datetime, timezone

    from src.main import plan_backfill_chunks

    end = datetime(2026, 5, 28, tzinfo=timezone.utc)
    cutoff = datetime(2026, 3, 1, tzinfo=timezone.utc)
    chunks = plan_backfill_chunks(end, cutoff, chunk_days=30)
    # Expect 3 chunks: [Apr 28, May 28), [Mar 29, Apr 28), [Mar 1, Mar 29)
    assert len(chunks) == 3
    assert chunks[0] == (datetime(2026, 4, 28, tzinfo=timezone.utc), end)
    assert chunks[-1][0] == cutoff


def test_plan_backfill_chunks_empty_when_cutoff_in_future() -> None:
    from datetime import datetime, timezone

    from src.main import plan_backfill_chunks

    end = datetime(2026, 1, 1, tzinfo=timezone.utc)
    cutoff = datetime(2027, 1, 1, tzinfo=timezone.utc)
    assert plan_backfill_chunks(end, cutoff, 30) == []


def test_run_sync_until_excludes_newer_messages(tmp_path) -> None:
    from datetime import datetime, timezone

    vault = tmp_path / "vault"
    vault.mkdir()
    config = Config(vault_path=vault)
    # sample dates: 2026-05-27 09:15 +10 (= 23:15 UTC May 26) and 11:42 +10 (01:42 UTC May 27)
    until = datetime(2026, 5, 27, 0, 0, 0, tzinfo=timezone.utc)
    summary = run_sync(config, full=True, use_mock=True, until=until)
    assert summary["messages_added"] == 1  # only the earlier message


def test_backfill_command_runs_chunks_with_mock(tmp_path) -> None:
    from src.main import main

    vault = tmp_path / "vault"
    vault.mkdir()
    cfg = tmp_path / "config.yaml"
    cfg.write_text(
        f"vault_path: \"{vault}\"\n"
        f"logging:\n  file: \"{tmp_path / 'sync.log'}\"\n"
    )
    # Use a tight window around the sample messages, 1 chunk only
    code = main(
        [
            "-c",
            str(cfg),
            "backfill",
            "--mock",
            "--chunk-days",
            "30",
            "--cutoff",
            "2026-04-01",
            "--stop-after-empty",
            "1",
        ]
    )
    assert code == 0
    assert list((vault / "Emails" / "Messages").rglob("*.md"))
