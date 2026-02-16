# MarkIt Hub

**MarkIt Hub** is a local-first synchronization engine that turns your Markdown files into live Android system data. It serves as the "backend" for the MarkIt ecosystem, allowing you to use your favorite Android Calendar apps (like Google Calendar or Etar) and system notifications as interfaces for your plain-text notes and events.

## What it does

### 1. Markdown ↔ Android Calendar Sync
MarkIt Hub scans your local folders for Markdown files containing event metadata (YAML frontmatter). It then:
*   **Creates Virtual Calendars:** Maps your folders to specific Android System Calendars.
*   **Two-Way Integration:** Displays your Markdown events in any calendar app on your phone. If you edit or delete an event in your calendar app, MarkIt Hub updates the corresponding `.md` file on your storage.
*   **Folder-Based Organization:** Follows a clear structure: `/{CalendarName}/{YYYY}/{MM}/{YYYY-MM-DD}_{EventTitle}.md`.

### 2. MarkIt Notes Reminders
It integrates deeply with **MarkIt Notes** by scanning your notes for the `reminder:` YAML key.
*   **System Notifications:** Schedules exact system alarms based on note metadata.
*   **Actionable Tasks:** Displays reminders as 10-minute "Task" events in your calendar.
*   **Archiving Logic:** When you mark a task as completed in your calendar app (using the `[x]` prefix), MarkIt Hub automatically moves the source Markdown note to your `.Archive` folder.

### 3. Privacy & Freedom
*   **No Cloud Required:** Your data stays in your folders. Use Syncthing, Obsidian, or Dropbox to sync the files to other devices; MarkIt Hub handles the local Android integration.
*   **Open Format:** Everything is stored in standard Markdown and YAML. You are never locked into a proprietary database.

## Core Features (v0.2.0)
*   **Universal YAML Standard:** Implements a robust, quote-aware YAML structure compatible across the ecosystem.
    *   **Double-Entry Dates:** Automatically writes both `reminder:` (for MarkIt) and `start:` (for Standard) to ensure compatibility.
    *   **Smart Quoting:** All strings are safely quoted to handle special characters (e.g., `title: "Project: Kickoff"`).
    *   **Selective Metadata:** Preserves unknown keys, allowing you to edit notes in other apps without losing calendar links.
*   **Safe Body Merge:** When you update an event in your Android Calendar, MarkIt Hub **merges** the changes. It updates the metadata (time, title) but **preserves your original Markdown body** on disk, preventing data loss from plain-text calendar apps.
*   **Task Normalization:** Automatically resizes calendar-created tasks to 10 minutes and ensures the `[]` checklist prefix is applied.
*   **Sync Stabilization:**
    *   **State-Based Incremental Sync:** Uses a local database to track file modifications, ensuring correct sync even with Syncthing timestamp quirks.
    *   **Duplicate ID Protection:** Detects cloned files (copy-paste) and automatically treats them as new events to prevent conflicts.
    *   **Ghost Calendar Cleanup:** Includes a "Nuke & Reset" tool to wipe stale system data.

## File & YAML Handling
MarkIt Hub treats your filesystem as the "Source of Truth" for content, and the Android Provider as the "Interface" for scheduling.

### Directory Structure
*   **Calendars:** Stored in `Root/{CalendarName}/{YYYY}/{MM}/{YYYY-MM-DD}_{Title}.md`.
*   **Tasks:** Stored in `TaskRoot/Inbox/` (or subfolders). Moving a file to `.Archive` completes the task.

### Universal YAML Format
We use a flat, human-readable YAML frontmatter.
```yaml
---
color: "#FF5733"
reminder: "2026-02-15 10:00"  # Authoritative Time
start: "2026-02-15 10:00"     # Compatibility Mirror
title: "My Event"
all_day: false
system_id: 12345              # Link to Android DB
---

Markdown content remains untouched...
```

## Setup
1.  **Install:** Download the `MarkIt-Hub-v0.2.0.apk` from the Releases page.
2.  **Permissions:** Follow the onboarding to grant Calendar and Notification access.
3.  **Battery:** Disable battery optimization (essential for the background sync engine to work reliably).
4.  **Folders:** Pick the folders where you store your Markdown calendars and MarkIt Notes.

---
*Part of the [MarkIt](https://github.com/Waph1/MarkIt-Hub) productivity suite.*
