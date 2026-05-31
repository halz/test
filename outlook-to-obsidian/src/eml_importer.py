"""Import emails from local .eml files (RFC 822).

Bypasses AppleScript entirely — each .eml is parsed with Python's ``email``
package and fed through the same pipeline the AppleScript client uses
(converter + note_writer + SQLite state + thread builder).

Typical flow on macOS:
1. In Outlook, select messages → drag to a Finder folder. One .eml per msg.
2. ``python -m src.main import-eml ~/path/to/exported/``
"""

from __future__ import annotations

import hashlib
import logging
from collections.abc import Iterable, Iterator
from datetime import datetime, timezone
from email import policy
from email.parser import BytesParser
from email.utils import getaddresses, parseaddr, parsedate_to_datetime
from pathlib import Path

from .outlook_client import (
    Attachment,
    MessageRecord,
    OutlookClientBase,
    Recipient,
)

logger = logging.getLogger(__name__)


def iter_eml_files(paths: Iterable[Path]) -> Iterator[Path]:
    """Yield ``*.eml`` files under each path (recursive for directories)."""
    for p in paths:
        if p.is_dir():
            yield from sorted(p.rglob("*.eml"))
        elif p.suffix.lower() == ".eml" and p.is_file():
            yield p


def _addresses(field: object) -> list[Recipient]:
    if not field:
        return []
    pairs = getaddresses([str(field)])
    return [Recipient(name=name, email=email) for name, email in pairs if email]


def parse_eml(
    path: Path, direction: str = "received", folder_path: str = ""
) -> MessageRecord:
    """Parse one .eml file into a :class:`MessageRecord`."""
    with open(path, "rb") as f:
        msg = BytesParser(policy=policy.default).parse(f)

    raw_id = (msg.get("Message-ID") or "").strip().strip("<>")
    if raw_id:
        entry_id = raw_id
    else:
        seed = "|".join(
            str(x)
            for x in (
                msg.get("Date") or "",
                msg.get("From") or "",
                msg.get("Subject") or "",
                str(path),
            )
        )
        entry_id = "eml-" + hashlib.sha256(seed.encode("utf-8", "replace")).hexdigest()[:32]

    subject = str(msg.get("Subject") or "")
    sender_name, sender_email = parseaddr(str(msg.get("From") or ""))

    date_str = msg.get("Date")
    date: datetime | None = None
    if date_str:
        try:
            date = parsedate_to_datetime(str(date_str))
        except (TypeError, ValueError):
            date = None
    if date is None:
        date = datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)
    if date.tzinfo is None:
        date = date.replace(tzinfo=timezone.utc)
    date = date.astimezone()

    body_html: str | None = None
    body_plain: str = ""
    attachments: list[Attachment] = []

    for part in msg.walk():
        if part.is_multipart():
            continue
        disposition = part.get_content_disposition()
        ctype = part.get_content_type()
        if disposition == "attachment":
            name = part.get_filename() or ""
            payload = part.get_payload(decode=True) or b""
            ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
            attachments.append(
                Attachment(name=name, size_bytes=len(payload), extension=ext)
            )
            continue
        # Inline content
        try:
            content = part.get_content()
        except (KeyError, LookupError, UnicodeDecodeError):
            continue
        if not isinstance(content, str):
            continue
        if ctype == "text/html" and body_html is None:
            body_html = content
        elif ctype == "text/plain" and not body_plain:
            body_plain = content

    return MessageRecord(
        entry_id=entry_id,
        subject=subject,
        direction=direction,
        sender_name=sender_name,
        sender_email=sender_email,
        date=date,
        to=_addresses(msg.get("To")),
        cc=_addresses(msg.get("Cc")),
        body_html=body_html,
        body_plain=body_plain,
        attachments=attachments,
        folder_path=folder_path,
    )


def parse_eml_paths(
    paths: Iterable[Path],
    direction: str = "auto",
    folder: str | None = None,
) -> Iterator[MessageRecord]:
    """Walk paths and yield parsed records.

    ``direction='auto'`` infers per-file from the parent directory name:
    contains "sent" or "送信" → sent, otherwise received. ``folder``
    overrides the recorded folder name (default: parent dir name).
    """
    for eml in iter_eml_files(paths):
        if direction == "auto":
            parent = eml.parent.name.lower()
            d = "sent" if ("sent" in parent or "送信" in parent) else "received"
        else:
            d = direction
        f = folder if folder is not None else eml.parent.name
        try:
            yield parse_eml(eml, direction=d, folder_path=f)
        except Exception as exc:  # noqa: BLE001
            logger.warning("skipping %s: %s", eml, exc)


class EmlClient(OutlookClientBase):
    """Outlook-shaped client backed by a pre-parsed list of EML records."""

    def __init__(self, records: list[MessageRecord]):
        self._records = records

    def is_available(self) -> bool:
        return True

    def iter_messages(
        self,
        since: datetime | None = None,
        until: datetime | None = None,
        skip_body: bool = False,
    ) -> Iterator[MessageRecord]:
        for r in self._records:
            if since is not None and r.date < since:
                continue
            if until is not None and r.date >= until:
                continue
            yield r

    def diagnose(self) -> str:
        return f"eml client: {len(self._records)} parsed message(s)"
