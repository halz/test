"""py2app entry point: launch the status GUI for the .app bundle.

Looks for ``config.yaml`` next to the bundle / in the working directory / under
``~/.config/outlook-to-obsidian/``. If none is found (or it fails to load) a
native dialog tells the user how to create one instead of silently crashing.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from src import gui
from src.config import Config

_CANDIDATES = (
    os.environ.get("OTO_CONFIG"),
    "config.yaml",
    os.path.expanduser("~/.config/outlook-to-obsidian/config.yaml"),
)


def _find_config() -> str:
    for candidate in _CANDIDATES:
        if candidate and Path(candidate).exists():
            return candidate
    return "config.yaml"


def _show_error(message: str) -> None:
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Outlook → Obsidian Bridge", message)
        root.destroy()
    except Exception:
        print(message, file=sys.stderr)


def main() -> int:
    path = _find_config()
    try:
        config = Config.load(path)
    except (FileNotFoundError, ValueError) as exc:
        _show_error(
            "config.yaml を読み込めませんでした。\n\n"
            f"{exc}\n\n"
            "config.example.yaml をコピーして config.yaml を作成し、"
            "vault_path を設定してください。"
        )
        return 2
    return gui.launch(config, path)


if __name__ == "__main__":
    raise SystemExit(main())
