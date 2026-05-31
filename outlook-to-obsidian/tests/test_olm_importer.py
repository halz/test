from __future__ import annotations

import zipfile
from pathlib import Path

from src.config import Config
from src.main import main, run_sync
from src.olm_importer import (
    OlmClient,
    _folder_from_zip_path,
    _parse_email,
    iter_messages_from_olm,
)
from xml.etree import ElementTree as ET


BATCHED_XML = """<?xml version="1.0" encoding="UTF-8"?>
<emails>
  <email>
    <OPFMessageGetMessageID>&lt;olm-001@example.com&gt;</OPFMessageGetMessageID>
    <OPFMessageCopySubject>EFTPOS terminal error at site 0234</OPFMessageCopySubject>
    <OPFMessageCopyReceivedTime>2026-05-27T09:15:00</OPFMessageCopyReceivedTime>
    <OPFMessageCopyFromAddresses>
      <emailAddress>
        <OPFContactEmailAddressName>Tanaka</OPFContactEmailAddressName>
        <OPFContactEmailAddressAddress>tanaka@example.com</OPFContactEmailAddressAddress>
      </emailAddress>
    </OPFMessageCopyFromAddresses>
    <OPFMessageCopyToAddresses>
      <emailAddress>
        <OPFContactEmailAddressName>Yoshi</OPFContactEmailAddressName>
        <OPFContactEmailAddressAddress>yoshi@example.com</OPFContactEmailAddressAddress>
      </emailAddress>
    </OPFMessageCopyToAddresses>
    <OPFMessageCopyHTMLBody>&lt;p&gt;Hi Yoshi, error 51&lt;/p&gt;</OPFMessageCopyHTMLBody>
    <OPFMessageCopyPriority>high</OPFMessageCopyPriority>
  </email>
  <email>
    <OPFMessageGetMessageID>&lt;olm-002@example.com&gt;</OPFMessageGetMessageID>
    <OPFMessageCopySubject>RE: EFTPOS terminal error at site 0234</OPFMessageCopySubject>
    <OPFMessageCopyReceivedTime>2026-05-27T11:42:00</OPFMessageCopyReceivedTime>
    <OPFMessageCopyFromAddresses>
      <emailAddress>
        <OPFContactEmailAddressName>Yoshi</OPFContactEmailAddressName>
        <OPFContactEmailAddressAddress>yoshi@example.com</OPFContactEmailAddressAddress>
      </emailAddress>
    </OPFMessageCopyFromAddresses>
    <OPFMessageCopyToAddresses>
      <emailAddress>
        <OPFContactEmailAddressAddress>tanaka@example.com</OPFContactEmailAddressAddress>
      </emailAddress>
    </OPFMessageCopyToAddresses>
    <OPFMessageCopyBody>Please reboot the terminal.</OPFMessageCopyBody>
  </email>
</emails>
"""


def _make_olm(tmp_path: Path) -> Path:
    olm = tmp_path / "archive.olm"
    with zipfile.ZipFile(olm, "w") as z:
        z.writestr(
            "Accounts/abc-1234/Folders/Inbox/Messages_001.xml", BATCHED_XML
        )
        z.writestr(
            "Accounts/abc-1234/Folders/Sent Items/Messages_001.xml",
            BATCHED_XML.replace(
                "<OPFMessageGetMessageID>&lt;olm-001@example.com&gt;</OPFMessageGetMessageID>",
                "<OPFMessageGetMessageID>&lt;olm-003@example.com&gt;</OPFMessageGetMessageID>",
            ).replace(
                "<OPFMessageGetMessageID>&lt;olm-002@example.com&gt;</OPFMessageGetMessageID>",
                "<OPFMessageGetMessageID>&lt;olm-004@example.com&gt;</OPFMessageGetMessageID>",
            ),
        )
    return olm


def test_folder_from_zip_path_inbox() -> None:
    assert _folder_from_zip_path("Accounts/X/Folders/Inbox/Messages_001.xml") == "Inbox"


def test_folder_from_zip_path_nested() -> None:
    assert (
        _folder_from_zip_path("Accounts/X/Folders/Inbox/ABB/Messages_001.xml")
        == "Inbox/ABB"
    )


def test_folder_from_zip_path_per_message_lowercase() -> None:
    # User's OLM layout: <folder>/message_NNNNN.xml (no "Folders" segment,
    # singular "message", lowercase). The folder is the immediate parent.
    assert (
        _folder_from_zip_path("Sent Items/message_00000.xml") == "Sent Items"
    )
    assert (
        _folder_from_zip_path("Site Changeover/message_00042.xml")
        == "Site Changeover"
    )


def test_folder_from_zip_path_per_message_id_suffix() -> None:
    assert (
        _folder_from_zip_path("Folders/Inbox/abc123_message.xml") == "Inbox"
    )


def test_parse_email_fields() -> None:
    root = ET.fromstring(BATCHED_XML)
    em = root.findall(".//email")[0]
    record = _parse_email(em, folder_path="Inbox")
    assert record.entry_id == "olm-001@example.com"
    assert record.subject == "EFTPOS terminal error at site 0234"
    assert record.sender_name == "Tanaka"
    assert record.sender_email == "tanaka@example.com"
    assert [r.email for r in record.to] == ["yoshi@example.com"]
    assert record.body_html is not None and "error 51" in record.body_html
    assert record.importance == "high"
    assert record.direction == "received"
    assert record.date.year == 2026 and record.date.month == 5


def test_iter_messages_from_olm_dedupes(tmp_path: Path) -> None:
    olm = _make_olm(tmp_path)
    records = list(iter_messages_from_olm(olm))
    # 4 unique Message-IDs across Inbox + Sent
    assert len(records) == 4
    assert {r.entry_id for r in records} == {
        "olm-001@example.com",
        "olm-002@example.com",
        "olm-003@example.com",
        "olm-004@example.com",
    }
    by_id = {r.entry_id: r for r in records}
    assert by_id["olm-003@example.com"].direction == "sent"
    assert by_id["olm-003@example.com"].folder_path == "Sent Items"


def test_import_olm_end_to_end(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    olm = _make_olm(tmp_path)
    cfg = tmp_path / "config.yaml"
    cfg.write_text(
        f'vault_path: "{vault}"\nlogging:\n  file: "{tmp_path / "sync.log"}"\n'
    )
    code = main(["-c", str(cfg), "import-olm", str(olm)])
    assert code == 0
    notes = list((vault / "Emails" / "Messages").rglob("*.md"))
    assert len(notes) == 4


def test_olm_client_drives_run_sync(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    olm = _make_olm(tmp_path)
    config = Config(vault_path=vault)
    config.logging.file = str(tmp_path / "sync.log")
    records = list(iter_messages_from_olm(olm))
    client = OlmClient(records)
    summary = run_sync(config, client=client)
    assert summary["messages_added"] == 4
    assert summary["messages_skipped"] == 0


def test_olm_user_layout_lowercase_per_message_xmls(tmp_path: Path) -> None:
    """User's real OLM uses ``<folder>/message_NNNNN.xml`` (no Folders segment)."""
    olm = tmp_path / "user.olm"
    with zipfile.ZipFile(olm, "w") as z:
        z.writestr(
            "Inbox/message_00000.xml",
            BATCHED_XML.split("<email>")[0]
            + "<email>"
            + BATCHED_XML.split("<email>")[1].split("</email>")[0]
            + "</email></emails>",
        )
        z.writestr(
            "Sent Items/message_00000.xml",
            BATCHED_XML.split("<email>")[0]
            + "<email>"
            + BATCHED_XML.split("<email>")[2].split("</email>")[0]
            + "</email></emails>",
        )

    records = list(iter_messages_from_olm(olm))
    assert len(records) == 2
    by_folder = {r.folder_path: r for r in records}
    assert by_folder["Inbox"].direction == "received"
    assert by_folder["Sent Items"].direction == "sent"
