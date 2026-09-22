from __future__ import annotations

import textwrap

import pytest

from src.config import Config


def test_load_expands_user_and_overrides(tmp_path) -> None:
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(
        textwrap.dedent(
            """
            vault_path: "~/Obsidian/WorkVault"
            output_subdir: "Emails"
            folders:
              inbox: true
              sent: false
            excluded_folders:
              - Junk
            """
        ),
        encoding="utf-8",
    )
    config = Config.load(cfg_file)
    assert "~" not in str(config.vault_path)
    assert str(config.vault_path).endswith("Obsidian/WorkVault")
    assert config.folders.inbox is True
    assert config.folders.sent is False
    assert config.excluded_folders == ["Junk"]
    assert config.emails_dir.name == "Emails"
    assert config.db_path.name == ".sync_state.db"


def test_missing_vault_path_raises(tmp_path) -> None:
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text("output_subdir: X\n", encoding="utf-8")
    with pytest.raises(ValueError):
        Config.load(cfg_file)


def test_defaults_applied(tmp_path) -> None:
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text('vault_path: "/tmp/v"\n', encoding="utf-8")
    config = Config.load(cfg_file)
    assert config.output.filename_max_length == 80
    assert config.sync.lookback_buffer_minutes == 60
    assert "Deleted Items" in config.excluded_folders
    assert config.output.generate_threads is True
