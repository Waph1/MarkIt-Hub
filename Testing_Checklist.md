# 🧪 Calendar App Testing Checklist

## Phase 1: Basic Synchronization (The "Happy Path")
- [ ] **Initial Import**: Select MarkIt folder, sync, and verify existing reminders appear with `[] ` prefix and 10min duration.
- [ ] **Creation from Calendar**: Create event "Buy Milk", sync. Verify `Buy Milk.md` is created in Inbox (or root) with correct YAML and body.
- [ ] **Creation from MarkIt**: Create a new note with reminder in MarkIt, sync. Verify it appears in the Calendar.

## Phase 2: Updates & Editing
- [ ] **Renaming**: Rename `[] Buy Milk` to `[] Buy Groceries` in Calendar. Verify file renamed to `Buy Groceries.md`.
- [ ] **Rescheduling**: Drag event to new time. Verify `reminder` timestamp in YAML updates.
- [ ] **Body Updates**: Change event description. Verify markdown body updates (YAML remains intact).
- [ ] **Color Sync**: Change event color. Verify `color: #AARRGGBB` in YAML updates.

## Phase 3: Completion & Lifecycle
- [ ] **Completing a Task**: Rename event to `[x] Buy Groceries`. Verify event deleted, `reminder` removed from file, and file moved to `.Archive` (mirroring folder structure).
- [ ] **Deleting a Task**: Delete event (trash icon). Verify file is **NOT** deleted, but `reminder` line is removed.

## Phase 4: Edge Cases
- [ ] **Filename Collisions**: Create file `Test.md`, then create event "Test". Verify `Test (1).md` is generated.
- [ ] **Special Characters**: Create event `Meeting w/ John: Project X`. Verify filename is sanitized (e.g., dashes).
- [ ] **Subfolders**: Sync a note from `Work/Projects/Q1/Design.md`. Verify completion moves it to `.Archive/Work/Projects/Q1/Design.md`.

## 📝 Information to Report (If Issues Occur)
1.  **Sync Logs**: Copy lines with `Error` or `Exception` from the Dashboard logs.
2.  **Behavior**: Description of what action you took and what happened vs. expected.
3.  **File Content**: The YAML header of the problematic file.
4.  **File Paths**: Where the file was located.
