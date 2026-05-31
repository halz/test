"""Import emails from a .olm archive (Outlook for Mac native export).

`.olm` is a ZIP container with one or more XML files describing messages.
Each `<email>` element uses Outlook's "OPF" tag names
(``OPFMessageCopyXXX`` / ``OPFContactEmailAddressXXX``). The schema is not
officially documented; this parser handles the layouts seen in the wild:

* Per-message XML files at ``Accounts/.../Folders/.../Messages/*_message.xml``
* Batched XMLs (``Messages_NNN.xml``) containing many ``<email>`` elements

Unknown / missing fields fall back to empty / sensible defaults.
"""

from __future__ import annotations

import hashlib
import logging
import re
import zipfile
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

from .outlook_client import (
    Attachment,
    MessageRecord,
    OutlookClientBase,
    Recipient,
)

logger = logging.getLogger(__name__)

_DATE_FORMATS = (
    "%Y-%m-%dT%H:%M:%S.%f",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%dT%H:%M:%S%z",
)


def _text(elem: ET.Element | None, *tags: str) -> str:
    if elem is None:
        return ""
    for tag in tags:
        x = elem.find(tag)
        if x is not None and x.text:
            return x.text.strip()
    return ""


def _addresses(elem: ET.Element, field: str) -> list[Recipient]:
    container = elem.find(field)
    if container is None:
        return []
    out: list[Recipient] = []
    for addr in container.findall(".//emailAddress"):
        name = _text(addr, "OPFContactEmailAddressName")
        email = _text(addr, "OPFContactEmailAddressAddress")
        if email or name:
            out.append(Recipient(name=name, email=email))
    return out


def _parse_olm_date(value: str) -> datetime:
    if not value:
        return datetime.now(timezone.utc).astimezone()
    raw = value.strip()
    # Trailing Z → +00:00 for fromisoformat
    iso = raw.replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(iso)
    except ValueError:
        dt = None
        for fmt in _DATE_FORMATS:
            try:
                dt = datetime.strptime(raw, fmt)
                break
            except ValueError:
                continue
        if dt is None:
            return datetime.now(timezone.utc).astimezone()
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone()


def _folder_from_zip_path(zip_path: str) -> str:
    """Derive a folder name from a ZIP entry path.

    Layouts seen in different Outlook versions:
    * ``.../Folders/Inbox/Messages_001.xml`` — explicit ``Folders`` segment.
    * ``.../Folders/Inbox/Sub/<id>_message.xml`` — nested under Folders.
    * ``.../Inbox/Messages_001.xml`` — folder is the parent of ``Messages*``
      with no ``Folders`` marker.
    * ``.../Inbox/<id>_message.xml`` — folder is the grandparent of per-msg
      XML (parent is ``Messages``).

    Returns the deepest folder name we can identify, or ``""`` on no match.
    """
    parts = zip_path.split("/")
    # Convention 1: explicit "Folders" marker (preferred when present).
    if "Folders" in parts:
        idx = parts.index("Folders")
        tail = parts[idx + 1 :]
        folder_parts: list[str] = []
        for part in tail:
            if part == "Messages" or part.endswith(".xml"):
                break
            folder_parts.append(part)
        if folder_parts:
            return "/".join(folder_parts)
    # Convention 2: filename is Messages*.xml → parent dir is the folder.
    if len(parts) >= 2:
        fname = parts[-1]
        if fname.lower().startswith("messages") and fname.lower().endswith(".xml"):
            return parts[-2]
    # Convention 3: per-message XML under .../<folder>/Messages/<id>.xml.
    if len(parts) >= 3 and parts[-2] == "Messages" and parts[-1].endswith(".xml"):
        return parts[-3]
    return ""


def _folder_from_xml(elem: ET.Element) -> str:
    """Best-effort: read folder name from the email XML itself.

    OPF tag names vary by Outlook version; try several common ones.
    """
    for tag in (
        "OPFMessageCopyFolderName",
        "OPFMessageCopyParentFolderName",
        "OPFMessageCopyFolder",
        "OPFFolderCopyName",
    ):
        val = _text(elem, tag)
        if val:
            return val
    return ""


def _hash_id(*parts: object) -> str:
    seed = "|".join(str(p) for p in parts if p)
    return "olm-" + hashlib.sha256(seed.encode("utf-8", "replace")).hexdigest()[:32]


def _parse_email(elem: ET.Element, folder_path: str) -> MessageRecord:
    msg_id = (
        _text(elem, "OPFMessageGetMessageID", "OPFMessageCopyMessageID").strip("<>")
    )
    subject = _text(elem, "OPFMessageCopySubject", "OPFMessageCopyThreadTopic")

    from_addrs = _addresses(elem, "OPFMessageCopyFromAddresses") or _addresses(
        elem, "OPFMessageCopySenderAddress"
    )
    sender_name = from_addrs[0].name if from_addrs else ""
    sender_email = from_addrs[0].email if from_addrs else ""

    to = _addresses(elem, "OPFMessageCopyToAddresses")
    cc = _addresses(elem, "OPFMessageCopyCCAddresses")

    date = _parse_olm_date(
        _text(elem, "OPFMessageCopyReceivedTime", "OPFMessageCopySentTime")
    )

    body_html = _text(elem, "OPFMessageCopyHTMLBody") or None
    body_plain = _text(elem, "OPFMessageCopyBody") if not body_html else ""

    categories: list[str] = []
    cats_container = elem.find("OPFMessageCopyCategoryList") or elem.find(
        "OPFMessageCopyCategories"
    )
    if cats_container is not None:
        for cat in cats_container.iter():
            name = _text(cat, "OPFCategoryName")
            if name:
                categories.append(name)
            elif cat is not cats_container and cat.text:
                stripped = cat.text.strip()
                if stripped and stripped not in categories:
                    categories.append(stripped)

    importance_raw = _text(elem, "OPFMessageCopyPriority").lower()
    if importance_raw in ("high", "2", "important"):
        importance = "high"
    elif importance_raw in ("low", "0"):
        importance = "low"
    else:
        importance = "normal"

    flag_raw = _text(elem, "OPFMessageCopyMessageStatus", "OPFMessageCopyFlagStatus")
    flag = "flagged" if flag_raw and flag_raw not in ("0", "false", "none") else "none"

    attachments: list[Attachment] = []
    att_container = elem.find("OPFMessageCopyAttachmentList")
    if att_container is not None:
        for att in att_container.findall(".//*"):
            name = _text(att, "OPFAttachmentName", "OPFAttachmentContentLocation")
            if not name and att.tag.endswith("Name") and att.text:
                name = att.text
            if not name:
                continue
            ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
            attachments.append(Attachment(name=name, size_bytes=0, extension=ext))

    # Folder: prefer an explicit XML tag, fall back to the ZIP-path-derived one.
    xml_folder = _folder_from_xml(elem)
    if xml_folder:
        folder_path = xml_folder

    direction = "sent" if "sent" in folder_path.lower() or "送信" in folder_path else "received"

    if not msg_id:
        msg_id = _hash_id(subject, date.isoformat(), sender_email)

    return MessageRecord(
        entry_id=msg_id,
        subject=subject,
        direction=direction,
        sender_name=sender_name,
        sender_email=sender_email,
        date=date,
        to=to,
        cc=cc,
        categories=categories,
        flag=flag,
        importance=importance,
        body_html=body_html,
        body_plain=body_plain,
        attachments=attachments,
        folder_path=folder_path,
    )


_MSG_PATH_RX = re.compile(r"Messages.*\.xml$", re.IGNORECASE)


def iter_messages_from_olm(olm_path: Path) -> Iterator[MessageRecord]:
    """Yield :class:`MessageRecord` for every parsable email in ``olm_path``."""
    seen: set[str] = set()
    with zipfile.ZipFile(olm_path) as zf:
        candidates = [
            n for n in zf.namelist() if n.lower().endswith(".xml") and "Messages" in n
        ]
        # Per-folder XMLs (those whose ZIP path lets us extract a folder name)
        # take precedence over summary / manifest XMLs that have no folder
        # context — when both list the same Message-ID, the per-folder copy
        # wins the dedup race so the recorded folder isn't lost to "".
        candidates.sort(
            key=lambda n: (0 if _folder_from_zip_path(n) else 1, n)
        )
        logger.info("OLM %s: %d candidate XML file(s)", olm_path.name, len(candidates))
        for name in candidates:
            folder = _folder_from_zip_path(name)
            try:
                data = zf.read(name)
            except (KeyError, zipfile.BadZipFile) as exc:
                logger.warning("Skipping %s: %s", name, exc)
                continue
            try:
                root = ET.fromstring(data)
            except ET.ParseError as exc:
                logger.warning("Skipping %s: not valid XML: %s", name, exc)
                continue
            emails = root.findall(".//email")
            if not emails and root.tag == "email":
                emails = [root]
            for em in emails:
                try:
                    record = _parse_email(em, folder)
                except Exception as exc:  # noqa: BLE001
                    logger.warning("Skipping email in %s: %s", name, exc)
                    continue
                if record.entry_id in seen:
                    continue
                seen.add(record.entry_id)
                yield record


class OlmClient(OutlookClientBase):
    """Outlook-shaped client backed by pre-parsed OLM records."""

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
        return f"olm client: {len(self._records)} parsed message(s)"
