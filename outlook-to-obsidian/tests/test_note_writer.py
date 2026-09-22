from __future__ import annotations

from datetime import datetime, timedelta, timezone

import yaml

from src.note_writer import (
    build_filename,
    build_frontmatter,
    build_message_note,
    generate_tags,
    sanitize_subject,
    unique_path,
)
from src.outlook_client import Attachment, MessageRecord, Recipient

TZ = timezone(timedelta(hours=10))


def _record(**kw) -> MessageRecord:
    base = dict(
        entry_id="0001",
        subject="RE: EFTPOS terminal error at site 0234",
        direction="received",
        sender_name="Tanaka Hiroshi",
        sender_email="h.tanaka@example.com",
        date=datetime(2026, 5, 28, 14, 30, 12, tzinfo=TZ),
        to=[Recipient("Yoshi", "yoshi@unitedpetroleum.com.au")],
        categories=["EFTPOS", "Urgent"],
        flag="flagged",
        attachments=[Attachment("screenshot.png", 18234, "png")],
        folder_path="Inbox/Support",
    )
    base.update(kw)
    return MessageRecord(**base)


def test_sanitize_keeps_re_replaces_colon() -> None:
    assert sanitize_subject("RE: Hello/World").startswith("RE-")
    assert "/" not in sanitize_subject("RE: Hello/World")
    assert ":" not in sanitize_subject("RE: Hello/World")


def test_sanitize_spaces_to_hyphen() -> None:
    assert sanitize_subject("EFTPOS terminal error") == "EFTPOS-terminal-error"


def test_sanitize_truncates() -> None:
    assert len(sanitize_subject("x" * 200, max_len=80)) <= 80


def test_filename_format_and_sent_suffix() -> None:
    received = build_filename(_record())
    assert received.startswith("2026-05-28_143012_")
    assert received.endswith(".md")
    assert not received.endswith("_sent.md")
    sent = build_filename(_record(direction="sent"))
    assert sent.endswith("_sent.md")


def test_unique_path_collision(tmp_path) -> None:
    (tmp_path / "a.md").write_text("x")
    taken: set[str] = set()
    p2 = unique_path(tmp_path, "a.md", taken)
    assert p2.name == "a_2.md"
    p3 = unique_path(tmp_path, "a.md", taken)
    assert p3.name == "a_3.md"


def test_generate_tags() -> None:
    tags = generate_tags(_record())
    assert tags[:2] == ["email", "received"]
    assert "eftpos" in tags
    assert "urgent" in tags
    assert "flagged" in tags
    assert len(tags) == len(set(tags))


def test_frontmatter_is_valid_yaml() -> None:
    block = build_frontmatter(_record(), ["email", "received"])
    inner = block.strip().strip("-").strip()
    data = yaml.safe_load(inner)
    assert data["entry_id"] == "0001"
    assert data["from"]["email"] == "h.tanaka@example.com"
    assert data["attachments"][0]["extension"] == "png"


def test_message_note_structure() -> None:
    note = build_message_note(_record(), "a8f3c2d1", "Body here", ["email", "received"])
    assert "# RE: EFTPOS terminal error at site 0234" in note
    assert "**From**: Tanaka Hiroshi <h.tanaka@example.com>" in note
    assert "[[thread_a8f3c2d1|" in note
    assert "Body here" in note
