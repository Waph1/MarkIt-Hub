# MarkIt Hub - Sync Stabilization Test Checklist

## 1. Core Performance & Optimization
- [ ] **Direct SAF Speed**: Verify that folder scanning is significantly faster compared to previous versions (uses direct `DocumentsContract` queries).
- [ ] **Batch Processing**: Observe that multiple events added/deleted at once sync in a single rapid burst (uses `applyBatch`).
- [ ] **Thread Safety**: Trigger a manual sync while a background sync is likely running; verify no crashes or "database locked" errors occur (uses `Mutex`).

## 2. Incremental Sync (Syncthing Support)
- [ ] **Timestamp Preservation**: Edit a file on PC, sync via Syncthing (ensuring phone file has an older timestamp than last sync), then run phone sync. Verify the change is detected.
- [ ] **Unchanged Skip**: Run sync twice with no changes. The second sync should finish almost instantly and show "0 processed" in logs.
- [ ] **Metadata Persistence**: Restart the app and verify that it doesn't re-parse every file on the first sync (verifies Room database persistence).

## 3. Edge Case Handling
- [ ] **Syncthing Conflicts**: Manually create a file named `event.sync-conflict-20260215.md`. Verify the app ignores it and doesn't create a duplicate calendar event.
- [ ] **Manual Deletion Cleanup**: Delete a `.md` file using a third-party File Manager. Run sync and verify the "Orphaned Metadata" is cleaned from the internal DB.
- [ ] **Stub Robustness**: Clear the App's storage (simulating lost cache) but keep the files. Verify the app re-links `system_id` correctly on the next sync.
- [ ] **Calendar Cleanup**: Rename a folder in your root directory. Verify the old calendar name is deleted from the Android Calendar app and a new one is created.

## 4. UI & Responsiveness
- [ ] **Log Fluidity**: Open the Sync Dashboard and verify there is no "jank" or freezing when the log list is long (verifies Async log loading).
- [ ] **Background Stability**: Ensure background sync works without the app being open in the foreground.

## 5. MarkIt Integration (Tasks)
- [ ] **Task Completion**: Change a task title to `[x] My Task` in the Calendar app. Verify the file is moved to `.Archive/` and the `reminder:` key is removed.
- [ ] **Task Pinning**: Place a file in a `Pinned` subfolder. Verify the sync still finds it and links it to the "Tasks" calendar correctly.
