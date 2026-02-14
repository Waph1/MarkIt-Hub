# Task: MarkIt Hub Development

## v0.1.0 - Core Sync Engine Implementation (Current)
- [x] **Project Setup**
    - [x] Rename project to "MarkIt Hub"
    - [x] Update package to `com.waph1.markithub`
    - [x] Initialize Git repository
- [x] **Onboarding UI**
    - [x] Welcome Screen
    - [x] Permission Request (Calendar, Notifications)
    - [x] Battery Optimization disabling
    - [x] Folder Selection (Calendar Root & Notes Root)

- [ ] **Sync Engine Refinement**
    - [ ] **Standardize Date Formats:** Ensure `YamlConverter` and `SyncEngine` use consistent date formats (ISO vs Custom).
    - [ ] **Fix `saveToFile`:** Ensure file creation/renaming logic in `SyncEngine` handles edge cases (like existing files with different names).
    - [ ] **Unit Tests:** Verify `YamlConverter` parsing logic.
    - [ ] **Integration Test:** Verify `SyncEngine` can read/write to Android Calendar Provider (mocked if possible).

- [ ] **Feature: Markdown Calendar Sync**
    - [ ] Parse `recurrence` rules from Markdown.
    - [ ] Handle `override_id` for exceptions.
    - [ ] Two-way sync for basic event details (Title, Description, Time).

- [ ] **Feature: MarkIt Notes Integration**
    - [ ] Parse `reminder:` YAML key from Notes.
    - [ ] Sync `[x]` completion status back to Markdown file (Archive logic).

## v0.2.0 - Advanced Features & Polish
- [ ] **Conflict Resolution:** Handle simultaneous edits on file and phone.
- [ ] **UI Polish:** Better logs, history view, and manual trigger controls.
- [ ] **Notifications:** Custom notifications for reminders if not using system calendar alerts.
