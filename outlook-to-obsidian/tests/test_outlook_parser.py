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


def test_build_applescript_emits_days_units_for_whole_day_offset(tmp_path: Path) -> None:
    from datetime import datetime

    config = Config(vault_path=tmp_path)
    now = datetime(2026, 5, 29, 10, 0, 0).astimezone()
    since = datetime(2026, 5, 1, 10, 0, 0).astimezone()  # exactly 28 days before
    script = build_applescript(config, since=since, now=now)
    # Must use `(N * days)` — Outlook for Mac rejects `(N * seconds)` in
    # this context (variant probe V4/V6/V7 = ERROR).
    assert "set sinceCut to ((current date) - (28 * days))" in script
    # No `(N * seconds)` multiplier (Outlook for Mac rejects it). Exclude
    # comment lines from the check — the comment about the bug mentions it.
    code_only = "\n".join(
        ln for ln in script.splitlines() if not ln.lstrip().startswith("--")
    )
    assert "* seconds" not in code_only
    assert "makeDate" not in script


def test_build_applescript_mixed_units_for_partial_day(tmp_path: Path) -> None:
    from datetime import datetime, timedelta

    config = Config(vault_path=tmp_path)
    now = datetime(2026, 5, 29, 10, 0, 0).astimezone()
    since = now - timedelta(days=2, hours=3, minutes=15)
    script = build_applescript(config, since=since, now=now)
    assert "(2 * days)" in script
    assert "(3 * hours)" in script
    assert "(15 * minutes)" in script


def test_build_applescript_future_until_uses_plus(tmp_path: Path) -> None:
    from datetime import datetime, timedelta

    config = Config(vault_path=tmp_path)
    now = datetime(2026, 5, 29, 10, 0, 0).astimezone()
    until = now + timedelta(days=1)
    script = build_applescript(config, since=None, until=until, now=now)
    assert "set untilCut to ((current date) + (1 * days))" in script


def test_build_applescript_no_filter_passes_missing_value(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    script = build_applescript(config, since=None, until=None)
    assert "set sinceCut to missing value" in script
    assert "set untilCut to missing value" in script
