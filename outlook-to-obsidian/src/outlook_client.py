"""Outlook for Mac access layer (AppleScript) plus a mock for tests/dry-runs.

Why AppleScript instead of COM
------------------------------
The original spec targeted Windows + Outlook desktop via COM (``pywin32``).
On macOS there is no COM; the supported automation surface is AppleScript /
Apple Events. This module therefore drives Outlook for Mac with ``osascript``.

Known Mac-vs-Windows differences (see README "Deviations"):
* ``EntryID``      → message ``id`` (stable within a profile/database).
* ``ConversationID`` is **not** exposed by Outlook for Mac AppleScript, so it is
  derived from the normalised subject (RE:/FW: stripped). Threading therefore
  groups by topic rather than by Exchange conversation.
* Message ``Size``, read/unread, and flag state are not captured: ``is read`` /
  ``is flagged`` start with the AppleScript reserved word ``is`` and fail to
  compile across Outlook versions, so they are omitted for robustness. These are
  recorded as size 0, ``unread: false``, ``flag: none``.
* The Sent folder is located by name match (``mail folders whose name contains
  "Sent"``); there is no reliable ``sent mail`` keyword.

The AppleScript generation here is the single most version-sensitive part of the
project. The Python pipeline downstream is fully decoupled and unit-tested via
:class:`MockOutlookClient`, and the wire-format parser is tested directly.
"""

from __future__ import annotations

import hashlib
import logging
import re
import subprocess
from abc import ABC, abstractmethod
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import datetime

from .config import Config

logger = logging.getLogger(__name__)

# Control characters used as field/record separators in the AppleScript output.
RS = "\x1e"  # record separator (between messages)
US = "\x1f"  # unit separator (between fields)
LIST_SEP = ";;"
PAIR_SEP = "|"

# Number of fields emitted per message (body is last and may contain stray US).
_FIELD_COUNT = 15

_SUBJECT_PREFIX = re.compile(r"^\s*(re|fw|fwd|aw|wg|sv|antwort)\s*:\s*", re.IGNORECASE)


@dataclass
class Recipient:
    name: str
    email: str

    def display(self) -> str:
        if self.name and self.email:
            return f"{self.name} <{self.email}>"
        return self.email or self.name


@dataclass
class Attachment:
    name: str
    size_bytes: int = 0
    extension: str = ""


@dataclass
class MessageRecord:
    entry_id: str
    subject: str
    direction: str  # received | sent
    sender_name: str
    sender_email: str
    date: datetime
    to: list[Recipient] = field(default_factory=list)
    cc: list[Recipient] = field(default_factory=list)
    categories: list[str] = field(default_factory=list)
    flag: str = "none"  # none | flagged
    importance: str = "normal"  # low | normal | high
    unread: bool = False
    size_bytes: int = 0
    body_html: str | None = None
    body_plain: str = ""
    attachments: list[Attachment] = field(default_factory=list)
    folder_path: str = ""
    conversation_id: str = ""
    conversation_topic: str = ""

    def __post_init__(self) -> None:
        if not self.conversation_topic:
            self.conversation_topic = clean_topic(self.subject)
        if not self.conversation_id:
            self.conversation_id = derive_conversation_id(self.subject)

    def from_display(self) -> str:
        return Recipient(self.sender_name, self.sender_email).display()


def clean_topic(subject: str) -> str:
    """Strip RE:/FW: style prefixes to get the conversation topic."""
    topic = subject or ""
    while True:
        stripped = _SUBJECT_PREFIX.sub("", topic)
        if stripped == topic:
            break
        topic = stripped
    return topic.strip()


def derive_conversation_id(subject: str) -> str:
    """Derive a stable conversation id from the normalised subject.

    Outlook for Mac does not expose the Exchange ConversationID, so messages are
    grouped by topic. Empty/blank subjects hash to a shared bucket.
    """
    topic = clean_topic(subject).lower()
    return hashlib.sha256(topic.encode("utf-8")).hexdigest()


class OutlookClientBase(ABC):
    """Interface implemented by the real and mock clients."""

    @abstractmethod
    def is_available(self) -> bool:
        """Return True if the backend can be reached."""

    @abstractmethod
    def iter_messages(
        self,
        since: datetime | None = None,
        until: datetime | None = None,
    ) -> Iterator[MessageRecord]:
        """Yield messages received in the half-open window ``[since, until)``.

        Either bound may be ``None`` to disable that side.
        """

    @abstractmethod
    def diagnose(self) -> str:
        """Return a human-readable probe of what the backend can see."""


class MockOutlookClient(OutlookClientBase):
    """In-memory client used for tests and ``--mock`` dry-runs."""

    def __init__(self, records: list[MessageRecord]):
        self._records = records

    def is_available(self) -> bool:
        return True

    def iter_messages(
        self,
        since: datetime | None = None,
        until: datetime | None = None,
    ) -> Iterator[MessageRecord]:
        for record in self._records:
            if since is not None and record.date < since:
                continue
            if until is not None and record.date >= until:
                continue
            yield record

    def diagnose(self) -> str:
        folders: dict[str, int] = {}
        for r in self._records:
            folders[r.folder_path or "(none)"] = folders.get(r.folder_path or "(none)", 0) + 1
        lines = [f"mock client: {len(self._records)} sample messages"]
        for name, n in sorted(folders.items()):
            lines.append(f"  {name}: {n}")
        return "\n".join(lines)


class AppleScriptOutlookClient(OutlookClientBase):
    """Drives Outlook for Mac through ``osascript``."""

    def __init__(self, config: Config):
        self.config = config

    def is_available(self) -> bool:
        script = 'tell application "System Events" to (name of processes) contains "Microsoft Outlook"'
        try:
            out = self._run(script)
        except OutlookClientError:
            return False
        return out.strip().lower() == "true"

    def iter_messages(
        self,
        since: datetime | None = None,
        until: datetime | None = None,
    ) -> Iterator[MessageRecord]:
        script = build_applescript(self.config, since, until)
        raw = self._run(script)
        logger.info(
            "AppleScript output: %d bytes, %d record-separator(s)",
            len(raw),
            raw.count(RS),
        )
        records = parse_messages(raw)
        logger.info("Parsed %d MessageRecord(s) from AppleScript output", len(records))
        yield from records

    def diagnose(self) -> str:
        lines: list[str] = []
        try:
            proc = self._run(
                'tell application "System Events" to (name of processes) contains "Microsoft Outlook"'
            ).strip().lower()
        except OutlookClientError as exc:
            return f"System Events probe failed: {exc}"
        lines.append(f"Outlook process running: {proc}")
        if proc != "true":
            lines.append("→ Microsoft Outlook を起動してから再実行してください。")
            return "\n".join(lines)
        script = _build_diagnose_script(self.config.folders.sent_name_patterns)
        try:
            raw = self._run(script)
        except OutlookClientError as exc:
            lines.append(f"Outlook scripting failed: {exc}")
            lines.append(
                "→ システム設定 > プライバシーとセキュリティ > オートメーション で、"
                "実行プロセス（Python / Terminal / .app）に Microsoft Outlook の許可を与えてください。"
            )
            return "\n".join(lines)
        lines.append("Outlook scripting access: OK")
        lines.append(
            f"Sent name patterns:  {self.config.folders.sent_name_patterns}"
        )
        lines.append("--- top-level mail folders (name | direct message count) ---")
        lines.append(raw.rstrip("\n") or "(empty)")

        # Date-filter probe: does `whose time received ≥ <date>` actually work
        # on this Outlook? Compares total inbox count against filtered counts
        # for 30/365 days ago. If totals are large but filtered are 0, the
        # whose-clause date filter is broken on this version.
        try:
            probe = self._run(_DATE_FILTER_PROBE)
            lines.append("--- date-filter probe (inbox) ---")
            lines.append(probe.rstrip("\n") or "(empty)")
        except OutlookClientError as exc:
            lines.append(f"date-filter probe failed: {exc}")
        return "\n".join(lines)

    def _run(self, script: str) -> str:
        timeout = self.config.sync.applescript_timeout_seconds
        try:
            proc = subprocess.run(
                ["osascript", "-e", script],
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except FileNotFoundError as exc:  # not on macOS
            raise OutlookClientError("osascript not found (macOS required)") from exc
        except subprocess.TimeoutExpired as exc:
            raise OutlookClientError(
                f"Outlook AppleScript timed out after {timeout}s — try --since to "
                "chunk the import, or raise sync.applescript_timeout_seconds"
            ) from exc
        if proc.returncode != 0:
            raise OutlookClientError(f"osascript failed: {proc.stderr.strip()}")
        return proc.stdout


class OutlookClientError(RuntimeError):
    """Raised when the Outlook backend cannot be reached or scripted."""


# Probes whether `whose time received ≥ <date>` filtering works at all.
# Builds the cutoff dates with the same `current date` minus N days approach,
# which sidesteps any makeDate construction issue and isolates the whose clause.
_DATE_FILTER_PROBE = """
tell application "Microsoft Outlook"
    set out to ""
    set total to -1
    try
        set total to count of messages of inbox
    end try
    set out to out & "inbox total: " & (total as text) & linefeed

    set cut30 to (current date) - (30 * days)
    set cut365 to (current date) - (365 * days)

    set c30 to -1
    try
        set c30 to count of (messages of inbox whose time received ≥ cut30)
    on error errMsg
        set out to out & "whose ≥ 30d ERROR: " & errMsg & linefeed
    end try
    set out to out & "whose time received ≥ 30 days ago: " & (c30 as text) & linefeed

    set c365 to -1
    try
        set c365 to count of (messages of inbox whose time received ≥ cut365)
    on error errMsg
        set out to out & "whose ≥ 365d ERROR: " & errMsg & linefeed
    end try
    set out to out & "whose time received ≥ 365 days ago: " & (c365 as text) & linefeed

    -- Newest message's received date, as a sanity check on what dates exist.
    try
        set msgs to messages of inbox
        if (count of msgs) > 0 then
            set newest to time received of (item 1 of msgs)
            set out to out & "first message time received: " & (newest as text) & linefeed
        end if
    on error errMsg
        set out to out & "newest probe ERROR: " & errMsg & linefeed
    end try
    return out
end tell
"""


def _build_diagnose_script(sent_patterns: list[str]) -> str:
    sent_blocks: list[str] = []
    for pattern in sent_patterns:
        safe = pattern.replace('"', '\\"')
        sent_blocks.append(
            f"""    try
        set sentList to (mail folders whose name contains "{safe}")
        set out to out & "SENT  | pattern=\\"{safe}\\" matches=" & ((count of sentList) as text) & linefeed
        repeat with sf in sentList
            set sname to "?"
            try
                set sname to name of sf
            end try
            set scnt to -1
            try
                set scnt to count of messages of sf
            end try
            set out to out & "SENT  | " & sname & " | " & (scnt as text) & linefeed
        end repeat
    on error errMsg
        set out to out & "SENT  | ERROR pattern=\\"{safe}\\" | " & errMsg & linefeed
    end try"""
        )
    sent_section = "\n".join(sent_blocks)
    return f"""
tell application "Microsoft Outlook"
    set out to ""
    try
        set inboxName to name of inbox
        set inboxCount to count of messages of inbox
        set out to out & "INBOX | " & inboxName & " | " & (inboxCount as text) & linefeed
    on error errMsg
        set out to out & "INBOX | ERROR | " & errMsg & linefeed
    end try
    try
        repeat with f in mail folders
            set fname to "?"
            try
                set fname to name of f
            end try
            set cnt to -1
            try
                set cnt to count of messages of f
            end try
            set out to out & "TOP   | " & fname & " | " & (cnt as text) & linefeed
        end repeat
    on error errMsg
        set out to out & "TOP   | ERROR | " & errMsg & linefeed
    end try
{sent_section}
    return out
end tell
"""


def build_client(config: Config, *, use_mock: bool = False) -> OutlookClientBase:
    """Return the appropriate client. ``use_mock`` yields a tiny sample set."""
    if use_mock:
        return MockOutlookClient(sample_records())
    return AppleScriptOutlookClient(config)


# --------------------------------------------------------------------------- #
# AppleScript generation                                                      #
# --------------------------------------------------------------------------- #
def _applescript_date(dt: datetime, now: datetime) -> str:
    """Render an AppleScript date expression relative to ``current date``.

    Building dates by mutating ``current date`` field-by-field (the old
    ``makeDate`` handler) silently produced values that broke the ``whose
    time received ≥ X`` clause on some Outlook for Mac versions. Offsetting
    from ``current date`` by a fixed number of seconds is the form proven to
    work by the doctor date-filter probe.
    """
    delta = int((now - dt).total_seconds())
    if delta >= 0:
        return f"((current date) - {delta})"
    return f"((current date) + {-delta})"


def build_applescript(
    config: Config,
    since: datetime | None,
    until: datetime | None = None,
    now: datetime | None = None,
) -> str:
    """Generate the AppleScript that dumps messages as a delimited stream.

    The script targets the Inbox (received) and Sent (sent) top-level folders,
    recursing into subfolders when configured, and emits one US-delimited record
    per message terminated by RS. Messages are filtered to the half-open window
    ``[since, until)`` if either bound is supplied.
    """
    if now is None:
        now = datetime.now().astimezone()
    recurse = "true" if config.folders.include_subfolders else "false"
    excluded = ", ".join(f'"{e}"' for e in config.excluded_folders)
    since_expr = _applescript_date(since, now) if since else "missing value"
    until_expr = _applescript_date(until, now) if until else "missing value"

    # Build the run section. The Inbox is reached via the well-known `inbox`
    # property; the Sent folder is found by name match (Outlook for Mac has no
    # reliable `sent mail` keyword). Both are wrapped in `try` so an unsupported
    # term degrades gracefully (that folder is skipped) instead of crashing.
    run_lines = [
        f"set sinceDate to {since_expr}",
        f"set untilDate to {until_expr}",
        'tell application "Microsoft Outlook"',
    ]
    if config.folders.inbox:
        run_lines += [
            "  try",
            f'    my collectFolder(inbox, "received", sinceDate, untilDate, {recurse})',
            "  end try",
        ]
    if config.folders.sent:
        for pattern in config.folders.sent_name_patterns:
            safe = pattern.replace('"', '\\"')
            run_lines += [
                "  try",
                f'    repeat with sf in (mail folders whose name contains "{safe}")',
                f'      my collectFolder(sf, "sent", sinceDate, untilDate, {recurse})',
                "    end repeat",
                "  end try",
            ]
    run_lines += ["end tell", "return outText"]
    run_section = "\n".join(run_lines)

    return f"""
property RS : (ASCII character 30)
property US : (ASCII character 31)
property excluded : {{{excluded}}}
property outText : ""

on isExcluded(folderName)
  repeat with e in excluded
    if folderName contains (e as text) then return true
  end repeat
  return false
end isExcluded

on emit(theMsg, direction, folderName)
  tell application "Microsoft Outlook"
    set theId to (id of theMsg) as text
    set theSubject to (subject of theMsg)
    if theSubject is missing value then set theSubject to ""
    set theSender to sender of theMsg
    set sName to ""
    set sAddr to ""
    if theSender is not missing value then
      set sName to (name of theSender)
      set sAddr to (address of theSender)
    end if
    set toStr to my recipients(to recipients of theMsg)
    set ccStr to my recipients(cc recipients of theMsg)
    set d to (time received of theMsg)
    if d is missing value then set d to (time sent of theMsg)
    set dStr to ((year of d) as text) & "," & (((month of d) as integer) as text) & "," & ((day of d) as text) & "," & ((hours of d) as text) & "," & ((minutes of d) as text) & "," & ((seconds of d) as text)
    set isRead to "false"
    set cats to ""
    try
      set cats to my joinCategories(category of theMsg)
    end try
    set prio to "normal"
    try
      set prio to (priority of theMsg) as text
    end try
    set flagged to "false"
    set atts to my joinAttachments(attachments of theMsg)
    set bodyText to ""
    try
      set bodyText to (content of theMsg)
    end try
    if bodyText is missing value then set bodyText to ""
    set rec to direction & US & theId & US & theSubject & US & sName & US & sAddr & US & toStr & US & ccStr & US & dStr & US & isRead & US & cats & US & prio & US & flagged & US & atts & US & folderName & US & bodyText
    set outText to outText & rec & RS
  end tell
end emit

on recipients(theList)
  set parts to {{}}
  tell application "Microsoft Outlook"
    repeat with r in theList
      set rName to ""
      set rAddr to ""
      try
        set ea to email address of r
        set rName to (name of ea)
        set rAddr to (address of ea)
      end try
      set end of parts to (rName & "{PAIR_SEP}" & rAddr)
    end repeat
  end tell
  set AppleScript's text item delimiters to "{LIST_SEP}"
  set s to parts as text
  set AppleScript's text item delimiters to ""
  return s
end recipients

on joinCategories(theCats)
  set parts to {{}}
  tell application "Microsoft Outlook"
    repeat with c in theCats
      try
        set end of parts to (name of c)
      end try
    end repeat
  end tell
  set AppleScript's text item delimiters to "{LIST_SEP}"
  set s to parts as text
  set AppleScript's text item delimiters to ""
  return s
end joinCategories

on joinAttachments(theAtts)
  set parts to {{}}
  tell application "Microsoft Outlook"
    repeat with a in theAtts
      set aName to ""
      try
        set aName to (name of a)
      end try
      set end of parts to (aName & "{PAIR_SEP}{PAIR_SEP}0")
    end repeat
  end tell
  set AppleScript's text item delimiters to "{LIST_SEP}"
  set s to parts as text
  set AppleScript's text item delimiters to ""
  return s
end joinAttachments

on collectFolder(theFolder, direction, sinceDate, untilDate, recurse)
  tell application "Microsoft Outlook"
    set folderName to ""
    try
      set folderName to name of theFolder
    end try
    if my isExcluded(folderName) then return
    set msgs to {{}}
    -- Lower bound is applied via the well-supported `whose time received ≥ X`.
    -- The upper bound is enforced per-message below because Outlook for Mac's
    -- AppleScript dictionary does not reliably parse compound `whose` clauses
    -- (the previous compound form silently returned an empty set).
    try
      if sinceDate is missing value then
        set msgs to (messages of theFolder)
      else
        set msgs to (messages of theFolder whose time received ≥ sinceDate)
      end if
    end try
    repeat with m in msgs
      try
        set inWindow to true
        if untilDate is not missing value then
          set d to (time received of m)
          if d is not missing value and d ≥ untilDate then set inWindow to false
        end if
        if inWindow then my emit(m, direction, folderName)
      end try
    end repeat
    if recurse then
      try
        repeat with sub in (mail folders of theFolder)
          my collectFolder(sub, direction, sinceDate, untilDate, recurse)
        end repeat
      end try
    end if
  end tell
end collectFolder

{run_section}
""".strip()


# --------------------------------------------------------------------------- #
# Output parsing                                                              #
# --------------------------------------------------------------------------- #
def _parse_recipients(raw: str) -> list[Recipient]:
    recipients: list[Recipient] = []
    for chunk in raw.split(LIST_SEP):
        chunk = chunk.strip()
        if not chunk:
            continue
        name, _, email = chunk.partition(PAIR_SEP)
        recipients.append(Recipient(name.strip(), email.strip()))
    return recipients


def _parse_attachments(raw: str) -> list[Attachment]:
    attachments: list[Attachment] = []
    for chunk in raw.split(LIST_SEP):
        chunk = chunk.strip()
        if not chunk:
            continue
        parts = chunk.split(PAIR_SEP)
        name = parts[0].strip()
        if not name:
            continue
        size = 0
        if len(parts) >= 3 and parts[2].strip().isdigit():
            size = int(parts[2].strip())
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        attachments.append(Attachment(name=name, size_bytes=size, extension=ext))
    return attachments


def _parse_date(raw: str) -> datetime:
    parts = [int(p) for p in raw.split(",")]
    while len(parts) < 6:
        parts.append(0)
    y, mo, d, h, mi, s = parts[:6]
    return datetime(y, mo, d, h, mi, s).astimezone()


def _parse_importance(raw: str) -> str:
    value = raw.lower()
    if "high" in value:
        return "high"
    if "low" in value:
        return "low"
    return "normal"


def _looks_like_html(text: str) -> bool:
    sample = text[:2000].lower()
    return any(tag in sample for tag in ("<html", "<body", "<div", "<p>", "<table", "<br"))


def parse_messages(raw: str) -> list[MessageRecord]:
    """Parse the AppleScript output stream into :class:`MessageRecord` objects."""
    records: list[MessageRecord] = []
    for block in raw.split(RS):
        if not block.strip():
            continue
        fields = block.split(US, _FIELD_COUNT - 1)
        if len(fields) < _FIELD_COUNT:
            logger.warning("Skipping malformed message record (%d fields)", len(fields))
            continue
        (
            direction,
            entry_id,
            subject,
            sender_name,
            sender_email,
            to_raw,
            cc_raw,
            date_raw,
            is_read_raw,
            categories_raw,
            priority_raw,
            flagged_raw,
            attachments_raw,
            folder_path,
            body,
        ) = fields

        try:
            date = _parse_date(date_raw)
        except (ValueError, IndexError):
            logger.warning("Skipping message %s: bad date %r", entry_id, date_raw)
            continue

        body = body.strip("\r\n")
        is_html = _looks_like_html(body)
        categories = [c.strip() for c in categories_raw.split(LIST_SEP) if c.strip()]

        records.append(
            MessageRecord(
                entry_id=entry_id.strip(),
                subject=subject.strip(),
                direction=direction.strip() or "received",
                sender_name=sender_name.strip(),
                sender_email=sender_email.strip(),
                date=date,
                to=_parse_recipients(to_raw),
                cc=_parse_recipients(cc_raw),
                categories=categories,
                flag="flagged" if flagged_raw.strip().lower() == "true" else "none",
                importance=_parse_importance(priority_raw),
                unread=is_read_raw.strip().lower() != "true",
                body_html=body if is_html else None,
                body_plain="" if is_html else body,
                attachments=_parse_attachments(attachments_raw),
                folder_path=folder_path.strip(),
            )
        )
    return records


def sample_records() -> list[MessageRecord]:
    """A small, deterministic sample conversation for --mock / demos."""
    from datetime import timezone, timedelta

    tz = timezone(timedelta(hours=10))
    return [
        MessageRecord(
            entry_id="0001",
            subject="EFTPOS terminal error at site 0234",
            direction="received",
            sender_name="Tanaka Hiroshi",
            sender_email="h.tanaka@example.com",
            date=datetime(2026, 5, 27, 9, 15, 0, tzinfo=tz),
            to=[Recipient("Yoshi", "yoshi@unitedpetroleum.com.au")],
            categories=["EFTPOS", "Urgent"],
            flag="flagged",
            importance="high",
            unread=True,
            body_html="<p>Hi Yoshi,</p><p>The EFTPOS terminal at site 0234 is showing error 51.</p>",
            attachments=[Attachment("screenshot.png", 18234, "png")],
            folder_path="Inbox/Support",
        ),
        MessageRecord(
            entry_id="0002",
            subject="RE: EFTPOS terminal error at site 0234",
            direction="sent",
            sender_name="Yoshi",
            sender_email="yoshi@unitedpetroleum.com.au",
            date=datetime(2026, 5, 27, 11, 42, 0, tzinfo=tz),
            to=[Recipient("Tanaka Hiroshi", "h.tanaka@example.com")],
            body_plain="Hi Tanaka,\n\nPlease reboot the terminal and retry.\n\n-- \nYoshi\nUnited Petroleum",
            folder_path="Sent Items",
        ),
    ]
