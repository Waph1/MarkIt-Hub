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

## Core Features (v0.1.0)
*   **Onboarding Flow:** Easy setup for permissions (Calendar, Notifications) and Battery Optimization.
*   **Folder Mapping:** Select your Calendar root and your MarkIt Notes root.
*   **Background Sync:** Uses WorkManager to keep files and system data in sync even when the app is closed.

## Setup
1.  **Install:** Download the `MarkIt-Hub-v0.1.0.apk` from the Releases page.
2.  **Permissions:** Follow the onboarding to grant Calendar and Notification access.
3.  **Battery:** Disable battery optimization (essential for the background sync engine to work reliably).
4.  **Folders:** Pick the folders where you store your Markdown calendars and MarkIt Notes.

## Roadmap
- [x] **v0.1.0**: Initial Release. Project scaffolding, Onboarding flow, Placeholder Branding.
- [ ] **v0.2.0**: Enhanced Sync. Optimized diffing logic and conflict resolution.
- [ ] **v0.3.0**: Recurring Events. Support for `_Recurring/` master rules and overrides.
- [ ] **v0.4.0**: Contacts Integration. Parsing vCard/Markdown for system-wide contact syncing.

---
*Part of the [MarkIt](https://github.com/Waph1/MarkIt-Hub) productivity suite.*
