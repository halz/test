"""py2app build configuration (macOS only).

    ./build_app.sh           # recommended
    # or, inside a venv with deps installed:
    python setup.py py2app

Produces dist/OutlookObsidianBridge.app.
"""

from __future__ import annotations

from setuptools import setup

APP = ["app_entry.py"]
DATA_FILES = ["config.example.yaml"]

OPTIONS = {
    "argv_emulation": False,
    "packages": ["src"],
    # Pull in third-party deps that py2app's static analysis can miss.
    "includes": ["markdownify", "yaml", "dateutil", "sqlite3"],
    "plist": {
        "CFBundleName": "OutlookObsidianBridge",
        "CFBundleDisplayName": "Outlook → Obsidian Bridge",
        "CFBundleIdentifier": "com.unitedpetroleum.outlook-obsidian",
        "CFBundleVersion": "0.1.0",
        "CFBundleShortVersionString": "0.1.0",
        "LSMinimumSystemVersion": "12.0",
        # Required so macOS shows the Automation prompt to control Outlook.
        "NSAppleEventsUsageDescription": (
            "Outlook → Obsidian Bridge reads your Outlook mail in order to export "
            "it as Markdown into your Obsidian vault."
        ),
    },
}

setup(
    name="OutlookObsidianBridge",
    app=APP,
    data_files=DATA_FILES,
    options={"py2app": OPTIONS},
    setup_requires=["py2app"],
)
