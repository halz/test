"""Minimal native status window (Tkinter/ttk).

Deliberately small: it shows sync state and offers "Sync now", "Open vault" and
"Open log". The CLI (driven by launchd) remains the primary interface; this is
the lightweight status screen requested for the .app bundle.
"""

from __future__ import annotations

import logging
import subprocess
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText

from .config import Config
from .main import run_sync
from .sync_state import SyncState

logger = logging.getLogger(__name__)

PAD = 12


class StatusApp:
    """The status window controller."""

    def __init__(self, root: tk.Tk, config: Config, config_path: str):
        self.root = root
        self.config = config
        self.config_path = config_path
        self._syncing = False

        root.title("Outlook → Obsidian Bridge")
        root.minsize(560, 420)

        try:
            ttk.Style().theme_use("aqua")  # native macOS look when available
        except tk.TclError:
            pass

        container = ttk.Frame(root, padding=PAD)
        container.pack(fill="both", expand=True)

        ttk.Label(
            container, text="Outlook → Obsidian Bridge", font=("Helvetica", 16, "bold")
        ).pack(anchor="w")

        self.info = ttk.Label(container, text="", justify="left")
        self.info.pack(anchor="w", pady=(PAD, PAD))

        buttons = ttk.Frame(container)
        buttons.pack(anchor="w", pady=(0, PAD))
        self.sync_btn = ttk.Button(buttons, text="今すぐ同期", command=self.on_sync)
        self.sync_btn.grid(row=0, column=0, padx=(0, 8))
        ttk.Button(buttons, text="Vault を開く", command=self.open_vault).grid(
            row=0, column=1, padx=8
        )
        ttk.Button(buttons, text="ログを開く", command=self.open_log).grid(
            row=0, column=2, padx=8
        )
        ttk.Button(buttons, text="更新", command=self.refresh).grid(
            row=0, column=3, padx=8
        )

        ttk.Label(container, text="状態 / Log", font=("Helvetica", 11, "bold")).pack(
            anchor="w"
        )
        self.output = ScrolledText(container, height=10, wrap="word", state="disabled")
        self.output.pack(fill="both", expand=True, pady=(4, 0))

        self.refresh()

    # -- helpers ------------------------------------------------------------
    def _log(self, message: str) -> None:
        self.output.configure(state="normal")
        self.output.insert("end", message + "\n")
        self.output.see("end")
        self.output.configure(state="disabled")

    def refresh(self) -> None:
        lines = [f"Vault:    {self.config.vault_path}", f"Config:   {self.config_path}"]
        db_path = self.config.db_path
        if db_path.exists():
            with SyncState(db_path) as state:
                lines.append(f"Messages: {state.message_count()}")
                lines.append(f"Last OK:  {state.last_successful_sync() or '(never)'}")
                runs = state.recent_runs(1)
                if runs:
                    run = runs[0]
                    lines.append(
                        f"Last run: {run['status']} "
                        f"(added={run['messages_added']}, skipped={run['messages_skipped']})"
                    )
        else:
            lines.append("Messages: 0 (no sync yet)")
        self.info.configure(text="\n".join(lines))

    # -- actions ------------------------------------------------------------
    def on_sync(self) -> None:
        if self._syncing:
            return
        self._syncing = True
        self.sync_btn.configure(state="disabled", text="同期中...")
        self._log("同期を開始しました...")
        threading.Thread(target=self._sync_worker, daemon=True).start()

    def _sync_worker(self) -> None:
        try:
            summary = run_sync(self.config)
            message = (
                f"完了: added={summary['messages_added']} "
                f"skipped={summary['messages_skipped']} "
                f"threads={summary['threads_rebuilt']}"
            )
        except Exception as exc:  # surfaced to the user in the log pane
            message = f"エラー: {exc}"
        self.root.after(0, lambda: self._sync_done(message))

    def _sync_done(self, message: str) -> None:
        self._syncing = False
        self.sync_btn.configure(state="normal", text="今すぐ同期")
        self._log(message)
        self.refresh()

    def open_vault(self) -> None:
        self._open(self.config.vault_path)

    def open_log(self) -> None:
        self._open(Path(self.config.logging.file))

    def _open(self, path: Path) -> None:
        try:
            subprocess.run(["open", str(path)], check=False)
        except FileNotFoundError:
            self._log(f"開けません (macOS の `open` が必要): {path}")


def launch(config: Config, config_path: str = "config.yaml") -> int:
    """Open the status window. Returns a process exit code."""
    root = tk.Tk()
    StatusApp(root, config, config_path)
    root.mainloop()
    return 0
