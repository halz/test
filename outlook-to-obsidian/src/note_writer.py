"""Markdown note generation: filenames, frontmatter, and message bodies."""

from __future__ import annotations

import os
import re
from pathlib import Path

import yaml

from .outlook_client import MessageRecord
from .utils import iso8601, slugify_tag

# Characters illegal in file names on macOS/Windows, plus control chars.
_FORBIDDEN = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def sanitize_subject(subject: str, max_len: int = 80) -> str:
    """Turn an email subject into a filesystem-safe slug.

    RE:/FW: tokens are kept (only the illegal ``:`` becomes ``-``); whitespace
    becomes ``-``; the result is truncated to ``max_len``.
    """
    text = subject or "no-subject"
    text = _FORBIDDEN.sub("-", text)
    text = re.sub(r"\s+", "-", text.strip())
    text = re.sub(r"-{2,}", "-", text).strip("-. ")
    if not text:
        text = "no-subject"
    if len(text) > max_len:
        text = text[:max_len].rstrip("-. ") or "no-subject"
    return text


def build_filename(record: MessageRecord, max_len: int = 80) -> str:
    """``YYYY-MM-DD_HHMMSS_<subject>[_sent].md``."""
    prefix = record.date.strftime("%Y-%m-%d_%H%M%S")
    subject = sanitize_subject(record.subject, max_len)
    suffix = "_sent" if record.direction == "sent" else ""
    return f"{prefix}_{subject}{suffix}.md"


def unique_path(directory: Path, filename: str, taken: set[str] | None = None) -> Path:
    """Return a non-colliding path, appending ``_2``, ``_3`` ... as needed."""
    taken = taken if taken is not None else set()
    stem, ext = os.path.splitext(filename)
    candidate = directory / filename
    counter = 2
    while str(candidate) in taken or candidate.exists():
        candidate = directory / f"{stem}_{counter}{ext}"
        counter += 1
    taken.add(str(candidate))
    return candidate


def message_subdir(messages_dir: Path, record: MessageRecord) -> Path:
    """``Messages/YYYY/MM`` for the message's date."""
    return messages_dir / record.date.strftime("%Y") / record.date.strftime("%m")


def generate_tags(record: MessageRecord) -> list[str]:
    """``email`` + direction + slugified categories + ``flagged``."""
    tags = ["email", record.direction]
    tags.extend(slugify_tag(c) for c in record.categories)
    if record.flag == "flagged":
        tags.append("flagged")
    seen: set[str] = set()
    ordered: list[str] = []
    for tag in tags:
        if tag and tag not in seen:
            seen.add(tag)
            ordered.append(tag)
    return ordered


def _format_offset(record: MessageRecord) -> str:
    offset = record.date.strftime("%z")
    if not offset:
        return ""
    return f"{offset[:3]}:{offset[3:]}"


def build_frontmatter(record: MessageRecord, tags: list[str]) -> str:
    """Render the YAML frontmatter block (including delimiters)."""
    data = {
        "entry_id": record.entry_id,
        "conversation_id": record.conversation_id,
        "conversation_topic": record.conversation_topic,
        "subject": record.subject,
        "direction": record.direction,
        "from": {"name": record.sender_name, "email": record.sender_email},
        "to": [{"name": r.name, "email": r.email} for r in record.to],
        "cc": [{"name": r.name, "email": r.email} for r in record.cc],
        "date": iso8601(record.date),
        "folder": record.folder_path,
        "categories": record.categories,
        "flag": record.flag,
        "importance": record.importance,
        "unread": record.unread,
        "size_bytes": record.size_bytes,
        "attachments": [
            {"name": a.name, "size_bytes": a.size_bytes, "extension": a.extension}
            for a in record.attachments
        ],
        "tags": tags,
    }
    dumped = yaml.safe_dump(
        data, sort_keys=False, allow_unicode=True, default_flow_style=False
    ).strip()
    return f"---\n{dumped}\n---"


def build_message_note(
    record: MessageRecord, thread_id: str, body_markdown: str, tags: list[str]
) -> str:
    """Assemble the full Markdown note for a single message."""
    frontmatter = build_frontmatter(record, tags)
    title = record.subject or "(no subject)"
    to_display = ", ".join(r.display() for r in record.to) or "—"
    offset = _format_offset(record)
    date_line = record.date.strftime("%Y-%m-%d %H:%M")
    when = f"{date_line} ({offset})" if offset else date_line
    thread_link = f"[[thread_{thread_id}|{record.conversation_topic}]]"

    lines = [
        frontmatter,
        "",
        f"# {title}",
        "",
        f"**From**: {record.from_display()}",
        f"**To**: {to_display}",
        f"**Date**: {when}",
        "",
        "> [!info] Thread",
        f"> {thread_link}",
        "",
        "---",
        "",
        body_markdown.strip(),
        "",
    ]
    return "\n".join(lines)


def write_note(path: Path, content: str) -> None:
    """Write ``content`` to ``path`` (UTF-8), creating parent directories."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if not content.endswith("\n"):
        content += "\n"
    path.write_text(content, encoding="utf-8")
