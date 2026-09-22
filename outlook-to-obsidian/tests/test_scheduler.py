from __future__ import annotations

import plistlib
from pathlib import Path

import pytest

from src.config import Config
from src.scheduler import build_plist, cron_to_launchd_calendar


def test_cron_step_every_15_minutes() -> None:
    assert cron_to_launchd_calendar("*/15 * * * *") == [
        {"Minute": 0},
        {"Minute": 15},
        {"Minute": 30},
        {"Minute": 45},
    ]


def test_cron_specific_time_each_day() -> None:
    assert cron_to_launchd_calendar("0 9 * * *") == [{"Minute": 0, "Hour": 9}]


def test_cron_list_two_times_per_day() -> None:
    entries = cron_to_launchd_calendar("0 9,18 * * *")
    assert {"Minute": 0, "Hour": 9} in entries
    assert {"Minute": 0, "Hour": 18} in entries
    assert len(entries) == 2


def test_cron_weekdays_only() -> None:
    entries = cron_to_launchd_calendar("0 9 * * 1,2,3,4,5")
    assert len(entries) == 5
    assert all(e["Minute"] == 0 and e["Hour"] == 9 for e in entries)
    assert sorted(e["Weekday"] for e in entries) == [1, 2, 3, 4, 5]


def test_cron_step_hours() -> None:
    entries = cron_to_launchd_calendar("0 */2 * * *")
    assert len(entries) == 12  # 0,2,4,...,22
    assert all(e["Minute"] == 0 for e in entries)
    assert sorted(e["Hour"] for e in entries) == list(range(0, 24, 2))


def test_cron_range_weekdays() -> None:
    entries = cron_to_launchd_calendar("0 9 * * 1-5")
    assert sorted(e["Weekday"] for e in entries) == [1, 2, 3, 4, 5]


def test_cron_combined_range_and_step() -> None:
    entries = cron_to_launchd_calendar("*/30 9-17 * * 1-5")
    # 2 minutes (0, 30) × 9 hours (9..17) × 5 weekdays = 90 entries
    assert len(entries) == 2 * 9 * 5
    minutes = {e["Minute"] for e in entries}
    hours = {e["Hour"] for e in entries}
    weekdays = {e["Weekday"] for e in entries}
    assert minutes == {0, 30}
    assert hours == set(range(9, 18))
    assert weekdays == {1, 2, 3, 4, 5}


def test_cron_invalid_range_rejected() -> None:
    with pytest.raises(ValueError, match="invalid range"):
        cron_to_launchd_calendar("0 17-9 * * *")


def test_cron_every_minute_rejected() -> None:
    with pytest.raises(ValueError, match="every minute"):
        cron_to_launchd_calendar("* * * * *")


def test_cron_field_count_validation() -> None:
    with pytest.raises(ValueError, match="5 fields"):
        cron_to_launchd_calendar("0 9 * *")


def test_cron_out_of_range_minute() -> None:
    with pytest.raises(ValueError, match="out of range"):
        cron_to_launchd_calendar("60 9 * * *")


def test_cron_out_of_range_hour() -> None:
    with pytest.raises(ValueError, match="out of range"):
        cron_to_launchd_calendar("0 24 * * *")


def test_build_plist_uses_start_interval_by_default(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    config.schedule.interval_minutes = 15
    config.logging.file = str(tmp_path / "sync.log")
    data = plistlib.loads(build_plist(config, str(tmp_path / "config.yaml")))
    assert data["StartInterval"] == 15 * 60
    assert "StartCalendarInterval" not in data
    assert data["RunAtLoad"] is True
    assert data["ProgramArguments"][-1] == "sync"
    assert "-c" in data["ProgramArguments"]


def test_build_plist_cron_takes_precedence(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    config.schedule.cron = "0 */2 * * *"
    config.schedule.interval_minutes = 15
    config.logging.file = str(tmp_path / "sync.log")
    data = plistlib.loads(build_plist(config, str(tmp_path / "config.yaml")))
    assert "StartInterval" not in data
    assert len(data["StartCalendarInterval"]) == 12


def test_build_plist_rejects_zero_interval(tmp_path: Path) -> None:
    config = Config(vault_path=tmp_path)
    config.schedule.interval_minutes = 0
    config.logging.file = str(tmp_path / "sync.log")
    with pytest.raises(ValueError, match=">= 1"):
        build_plist(config, str(tmp_path / "config.yaml"))
