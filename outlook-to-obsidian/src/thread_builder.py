"""Thread (conversation) note generation.

Thread notes are fully regenerated on every sync (never appended) to avoid
ordering corruption. Membership comes from the sync-state DB (grouped by
``conversation_id``); display details are read back from each message note's
frontmatter so the DB schema stays minimal.
"""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import yaml
from dateutil import parser as date_parser

from .config import Config
from .sync_state import SyncState
from .utils import slugify_tag

logger = logging.getLogger(__name__)

_HEX = re.compile(r"[^0-9a-fA-F]")


def thread_id_from_conversation(conversation_id: str) -> str:
    """Derive the 8-char hex thread id used in ``thread_<id>.md``."""
    cleaned = _HEX.sub("", conversation_id or "")
    if len(cleaned) >= 8:
        return cleaned[:8].lower()
    return hashlib.sha256((conversation_id or "").encode("utf-8")).hexdigest()[:8]


@dataclass
class _ThreadMsg:
    date: datetime
    sender: str
    recipients: str
    direction: str
    link_stem: str
    preview: str
    topic: str
    participants: list[str] = field(default_factory=list)
    categories: list[str] = field(default_factory=list)


def read_note(path: Path) -> tuple[dict, str]:
    """Split a note into (frontmatter dict, body text)."""
    text = path.read_text(encoding="utf-8")
    if text.startswith("---"):
        parts = text.split("---", 2)
        if len(parts) >= 3:
            front = yaml.safe_load(parts[1]) or {}
            return front, parts[2].lstrip("\n")
    return {}, text


def _email_body(note_body: str) -> str:
    """Return the email body that follows the ``---`` divider in a message note."""
    marker = "\n---\n"
    idx = note_body.find(marker)
    if idx != -1:
        return note_body[idx + len(marker) :].strip()
    return note_body.strip()


def _preview(body: str, max_lines: int = 3, width: int = 120) -> str:
    lines: list[str] = []
    for raw in body.splitlines():
        stripped = raw.strip().lstrip(">").strip()
        if not stripped or stripped.startswith(("#", "<details", "<summary", "</details")):
            continue
        if len(stripped) > width:
            stripped = stripped[:width].rstrip() + "…"
        lines.append(f"> {stripped}")
        if len(lines) >= max_lines:
            lines.append("> …")
            break
    return "\n".join(lines) if lines else "> (本文なし)"


def _display(person: dict | None) -> str:
    if not isinstance(person, dict):
        return ""
    name = (person.get("name") or "").strip()
    email = (person.get("email") or "").strip()
    if name and email:
        return f"{name} <{email}>"
    return name or email


def _load_thread_msg(vault_path: Path, note_rel_path: str) -> _ThreadMsg | None:
    note_path = vault_path / note_rel_path
    if not note_path.exists():
        logger.warning("Thread member note missing: %s", note_rel_path)
        return None
    try:
        front, body = read_note(note_path)
    except yaml.YAMLError as exc:
        # Tolerate notes whose frontmatter is malformed (e.g. truncated from
        # an interrupted write). Skipping lets the rest of the rebuild
        # succeed; the user can `sync` again to overwrite the broken note.
        logger.warning(
            "Skipping note with malformed frontmatter: %s — %s",
            note_rel_path,
            exc,
        )
        return None
    try:
        when = date_parser.isoparse(str(front.get("date")))
    except (ValueError, TypeError):
        when = datetime.fromtimestamp(note_path.stat().st_mtime).astimezone()

    sender_name = (front.get("from") or {}).get("name") or (front.get("from") or {}).get("email") or "?"
    recipients = ", ".join(
        (r.get("name") or r.get("email") or "") for r in (front.get("to") or [])
    ) or "—"
    participants = [_display(front.get("from"))]
    participants.extend(_display(r) for r in (front.get("to") or []))
    participants.extend(_display(r) for r in (front.get("cc") or []))

    return _ThreadMsg(
        date=when,
        sender=sender_name,
        recipients=recipients,
        direction=str(front.get("direction") or "received"),
        link_stem=note_path.stem,
        preview=_preview(_email_body(body)),
        topic=str(front.get("conversation_topic") or front.get("subject") or "(no subject)"),
        participants=[p for p in participants if p],
        categories=list(front.get("categories") or []),
    )


def build_thread_note(thread_id: str, conversation_id: str, messages: list[_ThreadMsg]) -> str:
    """Render a thread note from its (date-sorted) member messages."""
    messages = sorted(messages, key=lambda m: m.date)
    topic = messages[0].topic if messages else "(no subject)"

    participants: list[str] = []
    seen: set[str] = set()
    categories: list[str] = []
    for msg in messages:
        for person in msg.participants:
            if person and person not in seen:
                seen.add(person)
                participants.append(person)
        categories.extend(msg.categories)

    tags = ["email", "thread"]
    for cat in categories:
        slug = slugify_tag(cat)
        if slug and slug not in tags:
            tags.append(slug)

    frontmatter = {
        "thread_id": thread_id,
        "conversation_id": conversation_id,
        "conversation_topic": topic,
        "participants": participants,
        "message_count": len(messages),
        "first_message": messages[0].date.isoformat() if messages else None,
        "last_message": messages[-1].date.isoformat() if messages else None,
        "tags": tags,
    }
    dumped = yaml.safe_dump(
        frontmatter, sort_keys=False, allow_unicode=True, default_flow_style=False
    ).strip()

    lines = [f"---\n{dumped}\n---", "", f"# {topic}", "", "## メッセージ一覧（古い順）", ""]
    for msg in messages:
        marker = "  *(自分の返信)*" if msg.direction == "sent" else ""
        when = msg.date.strftime("%Y-%m-%d %H:%M")
        lines.append(f"### {when} — {msg.sender} → {msg.recipients}{marker}")
        lines.append(f"[[{msg.link_stem}|個別ノート]]")
        lines.append("")
        lines.append(msg.preview)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def rebuild_threads(state: SyncState, config: Config) -> int:
    """Regenerate every thread note from the current DB state. Returns count."""
    threads_dir = config.threads_dir
    threads_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for conversation_id in state.all_conversation_ids():
        rows = state.messages_for_conversation(conversation_id)
        messages = []
        for row in rows:
            loaded = _load_thread_msg(config.vault_path, row["note_path"])
            if loaded is not None:
                messages.append(loaded)
        if not messages:
            continue
        thread_id = thread_id_from_conversation(conversation_id)
        content = build_thread_note(thread_id, conversation_id, messages)
        (threads_dir / f"thread_{thread_id}.md").write_text(content, encoding="utf-8")
        written += 1
    logger.info("Rebuilt %d thread note(s)", written)
    return written
