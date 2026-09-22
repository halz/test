from __future__ import annotations

from pathlib import Path

from src.config import Config
from src.eml_importer import EmlClient, parse_eml, parse_eml_paths
from src.main import main, run_sync


SAMPLE_EML = b"""From: Tanaka <tanaka@example.com>
To: Yoshi <yoshi@example.com>, Alice <alice@example.com>
Cc: Bob <bob@example.com>
Subject: EFTPOS terminal error at site 0234
Date: Wed, 27 May 2026 09:15:00 +1000
Message-ID: <test-001@example.com>
Content-Type: text/plain; charset=utf-8

Hi Yoshi,

The EFTPOS terminal at site 0234 is showing error 51.

Regards,
Tanaka
"""

MULTIPART_EML = b"""From: Yoshi <yoshi@example.com>
To: Tanaka <tanaka@example.com>
Subject: RE: EFTPOS terminal error at site 0234
Date: Wed, 27 May 2026 11:42:00 +1000
Message-ID: <test-002@example.com>
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="BOUNDARY"

--BOUNDARY
Content-Type: text/plain; charset=utf-8

Hi Tanaka, please reboot the terminal.
--BOUNDARY
Content-Type: text/html; charset=utf-8

<p>Hi Tanaka, please <b>reboot</b> the terminal.</p>
--BOUNDARY
Content-Type: application/octet-stream; name="screenshot.png"
Content-Disposition: attachment; filename="screenshot.png"
Content-Transfer-Encoding: base64

aGVsbG8=
--BOUNDARY--
"""


def test_parse_eml_basic(tmp_path: Path) -> None:
    eml = tmp_path / "msg.eml"
    eml.write_bytes(SAMPLE_EML)
    record = parse_eml(eml, direction="received", folder_path="Inbox")
    assert record.entry_id == "test-001@example.com"
    assert record.subject == "EFTPOS terminal error at site 0234"
    assert record.sender_name == "Tanaka"
    assert record.sender_email == "tanaka@example.com"
    assert [r.email for r in record.to] == ["yoshi@example.com", "alice@example.com"]
    assert [r.email for r in record.cc] == ["bob@example.com"]
    assert record.folder_path == "Inbox"
    assert record.direction == "received"
    assert record.body_plain.startswith("Hi Yoshi")
    assert record.date.year == 2026 and record.date.month == 5


def test_parse_eml_multipart_with_attachment(tmp_path: Path) -> None:
    eml = tmp_path / "reply.eml"
    eml.write_bytes(MULTIPART_EML)
    record = parse_eml(eml, direction="sent", folder_path="Sent Items")
    assert record.body_html is not None
    assert "<b>reboot</b>" in record.body_html
    assert record.body_plain.startswith("Hi Tanaka")
    assert len(record.attachments) == 1
    assert record.attachments[0].name == "screenshot.png"
    assert record.attachments[0].size_bytes > 0


def test_parse_eml_paths_auto_direction(tmp_path: Path) -> None:
    inbox = tmp_path / "Inbox"
    inbox.mkdir()
    sent = tmp_path / "Sent Items"
    sent.mkdir()
    (inbox / "a.eml").write_bytes(SAMPLE_EML)
    (sent / "b.eml").write_bytes(MULTIPART_EML.replace(
        b"Message-ID: <test-002@example.com>", b"Message-ID: <test-003@example.com>"
    ))
    records = list(parse_eml_paths([tmp_path]))
    by_dir = {r.entry_id: r.direction for r in records}
    assert by_dir["test-001@example.com"] == "received"
    assert by_dir["test-003@example.com"] == "sent"


def test_parse_eml_missing_message_id_falls_back_to_hash(tmp_path: Path) -> None:
    eml = tmp_path / "no-id.eml"
    eml.write_bytes(
        b"From: a@example.com\nTo: b@example.com\nSubject: Hi\nDate: Wed, 27 May 2026 09:15:00 +1000\n\nbody\n"
    )
    record = parse_eml(eml)
    assert record.entry_id.startswith("eml-")
    assert len(record.entry_id) > 10


def test_import_eml_end_to_end(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    inbox = tmp_path / "Inbox"
    inbox.mkdir()
    (inbox / "msg.eml").write_bytes(SAMPLE_EML)
    cfg = tmp_path / "config.yaml"
    cfg.write_text(f'vault_path: "{vault}"\nlogging:\n  file: "{tmp_path / "sync.log"}"\n')

    code = main(["-c", str(cfg), "import-eml", str(inbox)])
    assert code == 0
    notes = list((vault / "Emails" / "Messages").rglob("*.md"))
    assert len(notes) == 1
    body = notes[0].read_text(encoding="utf-8")
    assert "EFTPOS terminal error" in body
    assert "Tanaka" in body


def test_eml_client_can_drive_run_sync(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    inbox = tmp_path / "Inbox"
    inbox.mkdir()
    (inbox / "a.eml").write_bytes(SAMPLE_EML)
    (inbox / "b.eml").write_bytes(MULTIPART_EML)

    config = Config(vault_path=vault)
    config.logging.file = str(tmp_path / "sync.log")
    records = list(parse_eml_paths([inbox]))
    client = EmlClient(records)
    summary = run_sync(config, client=client)
    assert summary["messages_added"] == 2
    assert summary["messages_skipped"] == 0
