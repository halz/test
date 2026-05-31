from __future__ import annotations

from src.sync_state import SyncState


def _msg(state: SyncState, entry_id: str, conv: str, body_hash: str, received: str) -> None:
    state.upsert_message(
        entry_id=entry_id,
        conversation_id=conv,
        subject="s",
        sender_email="a@b.c",
        direction="received",
        received_at=received,
        note_path=f"Emails/{entry_id}.md",
        folder_path="Inbox",
        body_hash=body_hash,
    )


def test_upsert_and_get() -> None:
    state = SyncState(":memory:")
    assert not state.is_synced("0001")
    _msg(state, "0001", "conv", "hash1", "2026-05-27T09:00:00+10:00")
    assert state.is_synced("0001")
    assert state.get_message("0001")["body_hash"] == "hash1"
    assert state.message_count() == 1


def test_edit_detection_updates_hash() -> None:
    state = SyncState(":memory:")
    _msg(state, "0001", "conv", "hash1", "2026-05-27T09:00:00+10:00")
    _msg(state, "0001", "conv", "hash2", "2026-05-27T09:00:00+10:00")
    assert state.get_message("0001")["body_hash"] == "hash2"
    assert state.message_count() == 1


def test_messages_for_conversation_sorted() -> None:
    state = SyncState(":memory:")
    _msg(state, "b", "conv", "h", "2026-05-27T11:00:00+10:00")
    _msg(state, "a", "conv", "h", "2026-05-27T09:00:00+10:00")
    rows = state.messages_for_conversation("conv")
    assert [r["entry_id"] for r in rows] == ["a", "b"]
    assert state.all_conversation_ids() == ["conv"]


def test_run_lifecycle_and_last_success() -> None:
    state = SyncState(":memory:")
    assert state.last_successful_sync() is None
    run_id = state.start_run()
    state.finish_run(
        run_id,
        messages_added=3,
        messages_skipped=1,
        threads_rebuilt=2,
        status="success",
    )
    assert state.last_successful_sync() is not None
    assert state.recent_runs()[0]["messages_added"] == 3


def test_reset_clears_messages() -> None:
    state = SyncState(":memory:")
    _msg(state, "0001", "conv", "h", "2026-05-27T09:00:00+10:00")
    state.reset()
    assert state.message_count() == 0
