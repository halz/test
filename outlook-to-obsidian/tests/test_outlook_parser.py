from __future__ import annotations

from pathlib import Path

from src.config import Config
from src.outlook_client import (
    LIST_SEP,
    PAIR_SEP,
    RS,
    US,
    MockOutlookClient,
    build_applescript,
    clean_topic,
    derive_conversation_id,
    parse_messages,
    sample_records,
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


def test_mock_client_diagnose_lists_folders() -> None:
    report = MockOutlookClient(sample_records()).diagnose()
    assert "mock client: 2 sample messages" in report
    assert "Inbox/Support: 1" in report
    assert "Sent Items: 1" in report


def test_build_applescript_uses_localized_sent_patterns(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    script = build_applescript(config, since=None)
    # default patterns: English + Japanese
    assert 'mail folders whose name contains "Sent"' in script
    assert 'mail folders whose name contains "送信済み"' in script


def test_build_applescript_respects_custom_sent_patterns(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    config.folders.sent_name_patterns = ["Gesendet", "Enviado"]
    script = build_applescript(config, since=None)
    assert 'mail folders whose name contains "Gesendet"' in script
    assert 'mail folders whose name contains "Enviado"' in script
    assert 'mail folders whose name contains "Sent"' not in script


def test_build_applescript_passes_since_as_seconds(tmp_path: Path) -> None:
    from datetime import datetime

    config = Config(vault_path=tmp_path)
    now = datetime(2026, 5, 29, 10, 0, 0).astimezone()
    since = datetime(2026, 5, 1, 10, 0, 0).astimezone()  # exactly 28 days before
    script = build_applescript(config, since=since, now=now)
    # 28 days = 2419200 seconds. The script must pass the integer in and
    # construct the date inside the Outlook tell block.
    assert "set sinceSeconds to 2419200" in script
    assert "(current date) - (sinceSeconds * seconds)" in script
    assert "makeDate" not in script


def test_build_applescript_future_until_uses_negative_seconds(tmp_path: Path) -> None:
    from datetime import datetime

    config = Config(vault_path=tmp_path)
    now = datetime(2026, 5, 29, 10, 0, 0).astimezone()
    until = datetime(2026, 5, 30, 10, 0, 0).astimezone()  # 1 day in the future
    script = build_applescript(config, since=None, until=until, now=now)
    # 1 day in the future from `now` = -86400 seconds offset.
    assert "set untilSeconds to -86400" in script


def test_build_applescript_no_filter_passes_minus_one(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    script = build_applescript(config, since=None, until=None)
    assert "set sinceSeconds to -1" in script
    assert "set untilSeconds to -1" in script
