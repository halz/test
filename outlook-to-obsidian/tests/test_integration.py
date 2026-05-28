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
