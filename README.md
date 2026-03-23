# MarkIt Hub

**MarkIt Hub** is a local-first synchronization engine that turns your plain-text files into live Android system data. It serves as the "backend" for the MarkIt ecosystem, allowing you to use your favorite Android Calendar apps (like Google Calendar or Fossify Calendar) and system notifications as interfaces for your event files, notes, and contacts.

## What it does

### 1. ICS ↔ Android Calendar Sync
MarkIt Hub scans your local folders for standard `.ics` (iCalendar) files. It then:
*   **Creates Virtual Calendars:** Maps your folders to specific Android System Calendars.
*   **Two-Way Integration:** Displays your ICS events in any calendar app on your phone. If you edit or delete an event in your calendar app, MarkIt Hub updates the corresponding `.ics` file on your storage.
*   **Folder-Based Organization:** Follows a clear structure: `/{CalendarName}/{YYYY}/{MM}/{YYYYMMDD}_{EventTitle}.ics`.
*   **Recurring Events:** Supports recurring events via standard `RRULE` properties, stored in a `_Recurring/` subfolder.
*   **Standard Format:** Uses the industry-standard iCalendar (RFC 5545) format via the ical4j library, ensuring compatibility with other calendar applications.

### 2. VCF ↔ Android Contacts Sync
MarkIt Hub keeps your plain-text `.vcf` contact backup directory in perfect sync with your Android System Contacts.
*   **Safe File Deletion:** If you delete a contact from Android, the `.vcf` file is removed.
*   **Intelligent Syncing:** Uses a hashing system to verify if contacts have genuinely changed before modifying them, safely preserving your "Favorites" list and protecting against native Android contact merges.

### 3. MarkIt Notes Reminders
It integrates deeply with **MarkIt Notes** by scanning your notes for the `reminder:` YAML key.
*   **System Notifications:** Schedules exact system alarms based on note metadata.
*   **Actionable Tasks:** Displays reminders as 10-minute "Task" events in your calendar.
*   **Archiving Logic:** When you mark a task as completed in your calendar app (using the `[x]` prefix), MarkIt Hub automatically moves the source Markdown note to your `.Archive` folder.

### 4. Privacy & Freedom
*   **No Cloud Required:** Your data stays in your folders. Use Syncthing, Obsidian, or Dropbox to sync the files to other devices; MarkIt Hub handles the local Android integration.
*   **Open Format:** Everything is stored in standard ICS, Markdown/YAML, and VCF. You are never locked into a proprietary database.

## Core Features (v0.4.0)
*   **Standard ICS Format:** Calendar events are now stored as standard `.ics` (iCalendar) files instead of Markdown, powered by the ical4j library.
*   **File Health Repair Pass:** A post-sync repair pass detects and fixes misplaced, wrongly-named, or malformed ICS files without deleting data.
*   **Robust File Reading:** Uses `openFileDescriptor` instead of `openInputStream` to bypass Android SAF file-size caching bugs, preventing content truncation.
*   **Safe Body Merge:** When you update an event in your Android Calendar, MarkIt Hub **merges** the changes. It updates the metadata (time, title) but **preserves your original content** on disk.
*   **Task Normalization:** Automatically resizes calendar-created tasks to 10 minutes and ensures the `[]` checklist prefix is applied.
*   **Sync Stabilization:**
    *   **State-Based Incremental Sync:** Uses a local database to track file modifications, ensuring correct sync even with Syncthing timestamp quirks.
    *   **Duplicate ID Protection:** Detects cloned files (copy-paste) and automatically treats them as new events to prevent conflicts.
    *   **Ghost Calendar Cleanup:** Includes a "Nuke & Reset" tool to wipe stale system data.

## File Handling

### Directory Structure
*   **Calendars:** Stored in `Root/{CalendarName}/{YYYY}/{MM}/{YYYYMMDD}_{Title}.ics`.
*   **Recurring Events:** Stored in `Root/{CalendarName}/_Recurring/{Title}.ics`.
*   **Tasks:** Stored in `TaskRoot/Inbox/` (or subfolders) as `.md` files with YAML frontmatter.
*   **Contacts:** Stored as `ContactRoot/*.vcf` files.

### ICS Format
Events use the standard iCalendar format (RFC 5545):
```
BEGIN:VCALENDAR
PRODID:-//MarkIt Hub//EN
VERSION:2.0
BEGIN:VEVENT
DTSTART;VALUE=DATE-TIME:20260215T100000
DTEND;VALUE=DATE-TIME:20260215T120000
SUMMARY:My Event
DESCRIPTION:Event notes here
UID:unique-event-id
COLOR:#FF5733
END:VEVENT
END:VCALENDAR
```

## Setup
1.  **Install:** Download the `MarkIt-Hub-v0.4.0.apk` from the Releases page.
2.  **Permissions:** Follow the onboarding to grant Calendar and Notification access.
3.  **Battery:** Disable battery optimization (essential for the background sync engine to work reliably).
4.  **Folders:** Pick the folders where you store your calendar files and MarkIt Notes.

---
*Part of the [MarkIt](https://github.com/Waph1/MarkIt-Hub) productivity suite.*
