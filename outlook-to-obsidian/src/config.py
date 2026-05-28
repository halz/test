"""Configuration loading for the Outlook → Obsidian bridge.

The on-disk format is ``config.yaml`` (see ``config.example.yaml``). Unknown keys
are ignored and any omitted key falls back to a sensible default so a partial
config still loads.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml


@dataclass
class FoldersConfig:
    """Which Outlook folders to traverse."""

    inbox: bool = True
    sent: bool = True
    include_subfolders: bool = True
    # Name fragments to match the Sent folder. Outlook for Mac AppleScript has
    # no reliable "sent items" keyword, so we name-match. Include localized
    # variants — default covers English + Japanese.
    sent_name_patterns: list[str] = field(
        default_factory=lambda: ["Sent", "送信済み"]
    )


@dataclass
class OutputConfig:
    """Markdown output behaviour."""

    generate_threads: bool = True
    filename_max_length: int = 80
    # When False (default) the signature after a ``-- `` delimiter is folded into
    # a collapsible <details> block. When True the signature is left inline.
    preserve_html_signatures: bool = False


@dataclass
class SyncConfig:
    """Incremental sync behaviour."""

    initial_full_import: bool = True
    lookback_buffer_minutes: int = 60


@dataclass
class LoggingConfig:
    """Logging destination and rotation."""

    level: str = "INFO"
    file: str = "logs/sync.log"
    max_size_mb: int = 10
    backup_count: int = 5


@dataclass
class Config:
    """Top-level configuration."""

    vault_path: Path
    output_subdir: str = "Emails"
    accounts: list[str] = field(default_factory=list)
    folders: FoldersConfig = field(default_factory=FoldersConfig)
    excluded_folders: list[str] = field(
        default_factory=lambda: ["Junk", "Deleted Items", "RSS Subscriptions"]
    )
    output: OutputConfig = field(default_factory=OutputConfig)
    sync: SyncConfig = field(default_factory=SyncConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)

    @property
    def emails_dir(self) -> Path:
        return self.vault_path / self.output_subdir

    @property
    def messages_dir(self) -> Path:
        return self.emails_dir / "Messages"

    @property
    def threads_dir(self) -> Path:
        return self.emails_dir / "Threads"

    @property
    def db_path(self) -> Path:
        return self.emails_dir / ".sync_state.db"

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Config":
        """Build a :class:`Config` from a parsed YAML mapping."""
        data = data or {}
        raw_vault = data.get("vault_path")
        if not raw_vault:
            raise ValueError("config.yaml is missing required key 'vault_path'")
        vault_path = Path(os.path.expanduser(str(raw_vault)))

        folders = FoldersConfig(**_subset(data.get("folders"), FoldersConfig))
        output = OutputConfig(**_subset(data.get("output"), OutputConfig))
        sync = SyncConfig(**_subset(data.get("sync"), SyncConfig))
        logging_cfg = LoggingConfig(**_subset(data.get("logging"), LoggingConfig))

        return cls(
            vault_path=vault_path,
            output_subdir=str(data.get("output_subdir", "Emails")),
            accounts=list(data.get("accounts") or []),
            excluded_folders=list(
                data.get("excluded_folders")
                or ["Junk", "Deleted Items", "RSS Subscriptions"]
            ),
            folders=folders,
            output=output,
            sync=sync,
            logging=logging_cfg,
        )

    @classmethod
    def load(cls, path: str | Path) -> "Config":
        """Load configuration from a YAML file."""
        with open(path, encoding="utf-8") as handle:
            data = yaml.safe_load(handle) or {}
        return cls.from_dict(data)


def _subset(raw: Any, dataclass_type: type) -> dict[str, Any]:
    """Return only the keys of ``raw`` that ``dataclass_type`` accepts."""
    if not isinstance(raw, dict):
        return {}
    allowed = dataclass_type.__dataclass_fields__.keys()
    return {k: v for k, v in raw.items() if k in allowed}
