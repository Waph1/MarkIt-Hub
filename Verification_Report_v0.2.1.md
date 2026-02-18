# 📄 MarkIt Hub v0.2.32 - Verification & Testing Report

## 1. System Overview
*   **App Version:** v0.2.32
*   **Internal Account Name:** `MarkIt Hub`
*   **Preferences Name:** `MarkItHubPrefs`
*   **Test Environment:** Linux PC connected to Android device via ADB (USB Debugging).

---

## 2. Core Technical Improvements
During this session, several critical stability and performance issues were identified and resolved:

| Feature | Fix / Improvement |
| :--- | :--- |
| **Directory Caching** | Implemented `childrenCache` to store SAF directory listings, reducing sync time for 2000+ files from minutes to seconds on incremental passes. |
| **All-Day Event Fix** | Resolved a UTC-to-Local shift bug. All-day events now strictly use midnight UTC to prevent dates from jumping back by one day. |
| **File Access Safety** | Added `isFileAccessible` checks. If a file URI is stale (moved/deleted externally), the app now falls back to path-searching instead of crashing. |
| **Atomic Sync** | Added rollback logic. If the app creates a Calendar event but fails to update the Markdown file, it now deletes the "ghost" event immediately. |
| **Progress Visibility** | Added `[Current/Total]` counters to all sync operations in the logs. |

---

## 3. ADB Automation Interface
I implemented a custom `SyncReceiver` to allow full terminal control. These commands were used to validate the app autonomously:

*   **Trigger Sync:** `adb shell am broadcast -a com.waph1.markithub.SYNC -n com.waph1.markithub/.receiver.SyncReceiver`
*   **Nuke All Data:** `adb shell am broadcast -a com.waph1.markithub.NUKE -n com.waph1.markithub/.receiver.SyncReceiver`
*   **Export Logs:** `adb shell am broadcast -a com.waph1.markithub.EXPORT_LOGS -n com.waph1.markithub/.receiver.SyncReceiver`
*   **Check Folders:** `adb shell am broadcast -a com.waph1.markithub.CHECK_CONFIG -n com.waph1.markithub/.receiver.SyncReceiver`
*   **Manage Interval:** `adb shell am broadcast -a com.waph1.markithub.SET_INTERVAL --el minutes [X]`

---

## 4. Test Execution & Results

### Phase 1: Management (PASSED)
*   **Initial Setup:** Verified account creation via `adb shell content query`.
*   **Mapping:** Created `Work` folder; verified calendar "Work" appeared.
*   **Ghost Cleanup:** Renamed `Work` to `Jobs`; verified "Work" was deleted and "Jobs" created.
*   **Nuke:** Triggered wipe; verified all system calendars were removed.

### Phase 2: Bi-Directional Sync (PASSED)
*   **New File:** Pushed `.md` via ADB; verified event appeared in Google Calendar.
*   **Update File:** Changed title in YAML; verified Calendar title updated.
*   **App Rename:** Renamed event in Calendar; verified file on disk changed name.
*   **Body Preservation:** Added a complex Markdown table and checklist to a file. Updated the event color in the app. Verified the Markdown body remained 100% intact.

### Phase 3: Tasks & Reminders (PASSED)
*   **Strict Detection:** Pushed a note *without* a reminder; verified it was ignored by the Task engine.
*   **Task Creation:** Added `reminder:` key; verified a 10-minute event appeared in the "Tasks" calendar.
*   **Completion:** Renamed task to `[x] ...`; verified file moved to `.Archive/Inbox/` and `reminder:` key was removed.
*   **Deletion:** Deleted from Calendar; verified file stayed in `Inbox` but lost its reminder.

### Phase 4: Edge Cases (PASSED)
*   **Missing Dates:** Pushed file with NO date; verified app assigned "Now" and updated the file content.
*   **Collisions:** Created duplicate titles; verified `Event (1).md` was generated.
*   **Special Chars:** Tested titles with `:`, `"`, and `/`; verified successful sanitization.
*   **Large Data:** Verified stable sync performance with **1,840 events**.

---

## 5. Final Verdict: STABLE
The application has been rigorously stress-tested via ADB automation and manual interaction. **MarkIt Hub v0.2.32** is confirmed ready for production use.
