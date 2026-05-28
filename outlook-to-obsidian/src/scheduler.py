"""launchd LaunchAgent management (macOS).

Generates a plist from ``config.schedule`` and (un)installs it under
``~/Library/LaunchAgents``. ``cron`` expressions are translated into one or
more ``StartCalendarInterval`` dict entries; ``interval_minutes`` becomes
``StartInterval``.
"""

from __future__ import annotations

import plistlib
import subprocess
import sys
from itertools import product
from pathlib import Path
from typing import Any

from .config import Config

LABEL = "com.outlook-obsidian.sync"

_CRON_FIELDS: list[tuple[str, int, int]] = [
    ("Minute", 0, 59),
    ("Hour", 0, 23),
    ("Day", 1, 31),
    ("Month", 1, 12),
    ("Weekday", 0, 7),  # launchd accepts 0 or 7 for Sunday
]


def plist_path() -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"


def project_dir() -> Path:
    return Path(__file__).resolve().parent.parent


def cron_to_launchd_calendar(cron: str) -> list[dict[str, int]]:
    """Convert a 5-field cron expression to ``StartCalendarInterval`` entries.

    Supports ``*``, integer, comma-list (``0,30``) and step (``*/15``). Returns
    a list of dicts; each dict contains the keys with fixed values (omitted
    keys mean "any" in launchd).
    """
    fields = cron.split()
    if len(fields) != 5:
        raise ValueError(
            f"cron expression needs 5 fields (minute hour day month weekday), "
            f"got {len(fields)}: {cron!r}"
        )

    def expand(name: str, lo: int, hi: int, value: str) -> list[int] | None:
        if value == "*":
            return None
        if value.startswith("*/"):
            step = int(value[2:])
            if step < 1:
                raise ValueError(f"cron field {name}: invalid step {value!r}")
            return list(range(lo, hi + 1, step))
        values: list[int] = []
        for token in value.split(","):
            if "-" in token:
                start_s, end_s = token.split("-", 1)
                start_i, end_i = int(start_s), int(end_s)
                if start_i > end_i:
                    raise ValueError(
                        f"cron field {name}: invalid range {token!r}"
                    )
                values.extend(range(start_i, end_i + 1))
            else:
                values.append(int(token))
        for v in values:
            if not lo <= v <= hi:
                raise ValueError(
                    f"cron field {name}={v} out of range [{lo},{hi}]"
                )
        return sorted(set(values))

    expanded = [
        (name, expand(name, lo, hi, field))
        for (name, lo, hi), field in zip(_CRON_FIELDS, fields)
    ]
    if all(v is None for _, v in expanded):
        raise ValueError(
            "'* * * * *' triggers every minute — use schedule.interval_minutes instead."
        )

    keys_vals = [(n, v) for n, v in expanded if v is not None]
    keys = [k for k, _ in keys_vals]
    val_lists = [v for _, v in keys_vals]

    entries = [dict(zip(keys, combo)) for combo in product(*val_lists)]
    if len(entries) > 1000:
        raise ValueError(
            f"cron expression expands to {len(entries)} calendar entries; "
            "please simplify (e.g. drop redundant fields)"
        )
    return entries


def build_plist(config: Config, config_path: str) -> bytes:
    sched = config.schedule
    log_file = Path(config.logging.file)
    if not log_file.is_absolute():
        log_file = project_dir() / log_file
    log_dir = log_file.parent
    log_dir.mkdir(parents=True, exist_ok=True)

    plist: dict[str, Any] = {
        "Label": LABEL,
        "ProgramArguments": [
            sys.executable,
            "-m",
            "src.main",
            "-c",
            str(Path(config_path).resolve()),
            "sync",
        ],
        "WorkingDirectory": str(project_dir()),
        "RunAtLoad": sched.run_at_login,
        "StandardOutPath": str(log_dir / "launchd.out.log"),
        "StandardErrorPath": str(log_dir / "launchd.err.log"),
    }
    if sched.cron:
        plist["StartCalendarInterval"] = cron_to_launchd_calendar(sched.cron)
    else:
        if sched.interval_minutes < 1:
            raise ValueError("schedule.interval_minutes must be >= 1")
        plist["StartInterval"] = sched.interval_minutes * 60
    return plistlib.dumps(plist)


def install(config: Config, config_path: str) -> int:
    target = plist_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    content = build_plist(config, config_path)
    # Unload any prior copy quietly so `load` re-applies the new content.
    subprocess.run(
        ["launchctl", "unload", str(target)], capture_output=True
    )
    target.write_bytes(content)
    result = subprocess.run(
        ["launchctl", "load", str(target)], capture_output=True, text=True
    )
    if result.returncode != 0:
        print(
            f"launchctl load failed: {result.stderr.strip() or result.stdout.strip()}",
            file=sys.stderr,
        )
        return 1
    print(f"Installed: {target}")
    sched = config.schedule
    if sched.cron:
        entries = cron_to_launchd_calendar(sched.cron)
        print(f"  cron: {sched.cron!r} → {len(entries)} calendar entries")
    else:
        print(f"  interval: every {sched.interval_minutes} minutes")
    print(f"  run_at_login: {sched.run_at_login}")
    print(f"  command: {sys.executable} -m src.main -c {Path(config_path).resolve()} sync")
    return 0


def uninstall() -> int:
    target = plist_path()
    if not target.exists():
        print(f"No agent installed at {target}")
        return 0
    subprocess.run(["launchctl", "unload", str(target)], capture_output=True)
    target.unlink()
    print(f"Uninstalled: {target}")
    return 0


def status() -> int:
    target = plist_path()
    print(f"Plist:  {target}")
    print(f"Exists: {target.exists()}")
    if not target.exists():
        return 0
    result = subprocess.run(
        ["launchctl", "list", LABEL], capture_output=True, text=True
    )
    if result.returncode == 0:
        print("Loaded: yes")
        print(result.stdout.rstrip())
    else:
        print("Loaded: no (run `schedule install` to load)")
    return 0


def preview(config: Config, config_path: str) -> int:
    sys.stdout.write(build_plist(config, config_path).decode("utf-8"))
    return 0
