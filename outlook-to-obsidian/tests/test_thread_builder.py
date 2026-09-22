from __future__ import annotations

from datetime import datetime, timedelta, timezone

import yaml

from src.note_writer import build_message_note, write_note
from src.outlook_client import MessageRecord, Recipient, derive_conversation_id
from src.thread_builder import (
    _ThreadMsg,
    build_thread_note,
    read_note,
    thread_id_from_conversation,
)

TZ = timezone(timedelta(hours=10))


def test_thread_id_is_8_hex_and_stable() -> None:
    cid = derive_conversation_id("EFTPOS error")
    tid = thread_id_from_conversation(cid)
    assert len(tid) == 8
    assert all(c in "0123456789abcdef" for c in tid)
    assert thread_id_from_conversation(cid) == tid


def test_build_thread_note_sorts_and_marks_sent() -> None:
    m1 = _ThreadMsg(
        date=datetime(2026, 5, 27, 9, 15, tzinfo=TZ),
        sender="Tanaka",
        recipients="Yoshi",
        direction="received",
        link_stem="note1",
        preview="> hi",
        topic="EFTPOS error",
        participants=["Tanaka <t@x>", "Yoshi <y@x>"],
        categories=["EFTPOS"],
    )
    m2 = _ThreadMsg(
        date=datetime(2026, 5, 27, 11, 42, tzinfo=TZ),
        sender="Yoshi",
        recipients="Tanaka",
        direction="sent",
        link_stem="note2",
        preview="> reply",
        topic="EFTPOS error",
        participants=["Yoshi <y@x>"],
        categories=[],
    )
    note = build_thread_note("a8f3c2d1", "conv", [m2, m1])  # intentionally unsorted
    assert "## メッセージ一覧（古い順）" in note
    assert "[[note1|個別ノート]]" in note
    assert "[[note2|個別ノート]]" in note
    assert "自分の返信" in note
    assert note.index("note1") < note.index("note2")

    fm = yaml.safe_load(note.split("---")[1])
    assert fm["message_count"] == 2
    assert fm["thread_id"] == "a8f3c2d1"
    assert "eftpos" in fm["tags"]
    assert "Tanaka <t@x>" in fm["participants"]


def test_read_note_round_trip(tmp_path) -> None:
    record = MessageRecord(
        entry_id="0001",
        subject="Hi",
        direction="received",
        sender_name="A",
        sender_email="a@b.c",
        date=datetime(2026, 5, 27, 9, 0, tzinfo=TZ),
        to=[Recipient("B", "b@c.d")],
    )
    note = build_message_note(record, "abcd1234", "Body line one", ["email", "received"])
    path = tmp_path / "n.md"
    write_note(path, note)

    front, body = read_note(path)
    assert front["entry_id"] == "0001"
    assert front["direction"] == "received"
    assert "Body line one" in body
