"""Shared utilities: logging setup, hashing, tag/datetime helpers.

All modules obtain their logger via ``logging.getLogger(__name__)``.
"""

from __future__ import annotations

import hashlib
import logging
import logging.handlers
import re
from datetime import datetime
from pathlib import Path

LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"

# Characters kept in a tag: ASCII alphanumerics, Japanese ranges, and -_/.
_TAG_KEEP = re.compile(r"[^0-9a-z぀-ヿ一-鿿/_-]")


def setup_logging(
    level: str = "INFO",
    log_file: str | Path | None = None,
    max_size_mb: int = 10,
    backup_count: int = 5,
) -> None:
    """Configure root logging.

    File handler logs at ``level`` with rotation; the console handler only
    surfaces WARNING and above so scheduled runs stay quiet on stdout.

    Args:
        level: File log level name (DEBUG/INFO/WARNING/ERROR).
        log_file: Path to the rotating log file, or ``None`` to skip file logging.
        max_size_mb: Size in MB at which the log rotates.
        backup_count: Number of rotated files to retain.
    """
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    for handler in list(root.handlers):
        root.removeHandler(handler)

    formatter = logging.Formatter(LOG_FORMAT)

    console = logging.StreamHandler()
    console.setLevel(logging.WARNING)
    console.setFormatter(formatter)
    root.addHandler(console)

    if log_file:
        path = Path(log_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.handlers.RotatingFileHandler(
            path,
            maxBytes=max_size_mb * 1024 * 1024,
            backupCount=backup_count,
            encoding="utf-8",
        )
        file_handler.setLevel(getattr(logging, level.upper(), logging.INFO))
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)


def sha256_text(text: str) -> str:
    """Return the hex SHA-256 digest of ``text`` (UTF-8 encoded)."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def slugify_tag(value: str) -> str:
    """Normalise a string into an Obsidian-safe tag fragment.

    Lower-cases, turns whitespace into hyphens, and drops characters Obsidian
    would reject. Japanese characters are preserved.
    """
    slug = value.strip().lower()
    slug = re.sub(r"\s+", "-", slug)
    slug = _TAG_KEEP.sub("", slug)
    slug = re.sub(r"-{2,}", "-", slug).strip("-/_")
    return slug


def iso8601(dt: datetime) -> str:
    """Return an ISO-8601 string with an explicit timezone offset.

    Naive datetimes are assumed to be in the host's local timezone (this mirrors
    Outlook for Mac, which returns local times).
    """
    if dt.tzinfo is None:
        dt = dt.astimezone()
    return dt.isoformat()
