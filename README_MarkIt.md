# MarkIt Notes (v0.5.2)

**MarkIt Notes** is a modern, beautiful, and privacy-focused Markdown note-taking app for Android. Designed with a clean Google Keep-inspired aesthetic, it puts your content first while giving you the power of Markdown formatting through a revolutionary **Hybrid Editing** experience.

![MarkIt Notes Icon](app/src/main/res/drawable/ic_launcher_foreground.png)

## The Hybrid Editor Experience

MarkIt Notes features a sophisticated "Live Preview" editor that bridges the gap between raw Markdown and rendered text.

*   **Syntax that Melts Away**: Markdown symbols like `#`, `**`, `_`, and `<u>` are visible and fainter when you are editing them, but disappear the moment you move your cursor away, leaving you with beautifully rendered text (Bold, Italic, Headings, etc.) directly in the editor.
*   **Smart Newlines**:
    *   **Syntax Jumps**: Pressing Enter while inside a formatted word (e.g., `**Bold|**`) automatically jumps past the closing syntax before creating the new line, keeping your formatting intact.
    *   **List & Quote Continuation**: Pressing Enter on a list item (`-`, `1.`, `- [ ]`) or a blockquote (`>`) automatically continues the prefix on the next line.
    *   **Intelligent Termination**: Pressing Enter twice on an empty list item or quote automatically clears the prefix and ends the block.
*   **Hypertext Integration**: Rendered links in the preview open directly in your default system browser for a seamless workflow.

## About
MarkIt Notes differs from other note apps by treating your notes as **real files**. There is no hidden database; every note is a plain text Markdown (`.md`) file stored in a folder of your choice on your device. This means you truly own your data—you can sync it, back it up, or open it with any other text editor.

## Features

*   **File-Based Storage**: Your notes are yours. Stored as local `.md` files.
*   **Hybrid Markdown Editor**: Live-rendering while you type with disappearing syntax.
*   **Reminders**: Set date and time alerts for your notes with system notifications.
*   **Multi-Selection**: Bulk actions for deleting, archiving, moving, pinning, and coloring multiple notes.
*   **Grid Layout**: A beautiful staggered grid view (like Google Keep) or a single-column list view.
*   **Localization**: Support for English and Italian.
*   **Organization**:
    *   **Folders as Labels**: Use folders to organize notes into categories.
    *   **Archive & Trash**: Keep your workspace clutter-free with industry-standard hidden folder structures.
    *   **Pins & Colors**: Keep important notes at the top and visually grouped.
*   **Privacy First**: No internet permissions required. No tracking. No cloud lock-in.
*   **Modern UI**: Built with Jetpack Compose and Material 3, featuring Dark Mode support.
*   **Intelligent Search**: Powerful search with "Search everywhere" fallback.

## How it works: Total Portability

MarkIt Notes uses your filesystem as the database. This ensures that your notes are manageable and readable anywhere, on any device, with any text editor.

### Human-Readable Metadata
Note-specific features (like background colors or reminders) are stored directly inside the `.md` file using **YAML Front Matter**.

```markdown
---
color: #FFFFF475
reminder: 2026-02-15 09:00
---
# Your Note Title
Your content here...
```

## Installation

1.  Download the latest APK from the [Releases](https://github.com/Waph1/MarkIt/releases) page.
2.  Install it on your Android device.
3.  On first launch, select a folder where you want your notes to live.

## Building from source

1.  Clone the repository: `git clone https://github.com/Waph1/MarkIt.git`
2.  Open in **Android Studio**.
3.  Build and Run.

## License

Licensed under the Apache License, Version 2.0.