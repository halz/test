from __future__ import annotations

from src.outlook_client import (
    LIST_SEP,
    PAIR_SEP,
    RS,
    US,
    clean_topic,
    derive_conversation_id,
    parse_messages,
)


def _record(
    *,
    direction="received",
    entry_id="0001",
    subject="EFTPOS error",
    sname="Tanaka",
    saddr="t@example.com",
    to="Yoshi" + PAIR_SEP + "yoshi@up.com.au",
    cc="",
    date="2026,5,27,9,15,0",
    is_read="false",
    cats="EFTPOS" + LIST_SEP + "Urgent",
    prio="priority high",
    flagged="true",
    atts="screenshot.png" + PAIR_SEP + PAIR_SEP + "0",
    folder="Inbox/Support",
    body="<p>hello</p>",
) -> str:
    fields = [
        direction, entry_id, subject, sname, saddr, to, cc, date,
        is_read, cats, prio, flagged, atts, folder, body,
    ]
    return US.join(fields)


def test_parse_single_message() -> None:
    raw = _record() + RS
    [msg] = parse_messages(raw)
    assert msg.entry_id == "0001"
    assert msg.sender_email == "t@example.com"
    assert msg.to[0].name == "Yoshi"
    assert msg.to[0].email == "yoshi@up.com.au"
    assert msg.date.year == 2026 and msg.date.month == 5
    assert msg.importance == "high"
    assert msg.flag == "flagged"
    assert msg.unread is True
    assert msg.body_html is not None
    assert msg.categories == ["EFTPOS", "Urgent"]
    assert msg.attachments[0].extension == "png"
    assert msg.direction == "received"


def test_parse_plain_body_sets_plain() -> None:
    raw = _record(body="just text, no tags") + RS
    [msg] = parse_messages(raw)
    assert msg.body_html is None
    assert msg.body_plain == "just text, no tags"


def test_multiple_records() -> None:
    raw = _record(entry_id="0001") + RS + _record(entry_id="0002", direction="sent") + RS
    msgs = parse_messages(raw)
    assert [m.entry_id for m in msgs] == ["0001", "0002"]
    assert msgs[1].direction == "sent"


def test_malformed_record_skipped() -> None:
    raw = "too" + US + "few" + RS + _record() + RS
    msgs = parse_messages(raw)
    assert len(msgs) == 1


def test_clean_topic_strips_prefixes() -> None:
    assert clean_topic("RE: Hello") == "Hello"
    assert clean_topic("FW: RE: Hi there") == "Hi there"


def test_conversation_id_groups_replies() -> None:
    assert derive_conversation_id("RE: Hello") == derive_conversation_id("Hello")
    assert derive_conversation_id("A") != derive_conversation_id("B")
