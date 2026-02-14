# MarkIt Hub

A calendar app where every event is a Markdown file with YAML frontmatter.

## Features
- **Local Storage**: Files are stored in a folder of your choice (e.g., `/Documents/MyCalendar/`).
- **Markdown & YAML**: Metadata is stored in YAML frontmatter; notes are in the Markdown body.
- **Overlay Recurrence**: 
    - Master rules are in `/_Recurring/`.
    - Overrides/Exceptions are created as physical files in `/{YYYY}/{MM}/` with an `override_id`.
- **Privacy**: No cloud sync required; use your own folder sync tools (Syncthing, Dropbox, etc.).

## File Structure
- `/{CalendarName}/{YYYY}/{MM}/{YYYY-MM-DD}_{EventTitle}.md` - Standard events and overrides.
- `/{CalendarName}/_Recurring/{EventTitle}.md` - Recurring master files.

## Metadata Fields
- `title`: Event title
- `date`: ISO date (YYYY-MM-DD)
- `time`: ISO time (HH:MM:SS)
- `duration`: Duration (e.g., 1h, 30m)
- `tags`: List of tags
- `recurrence`: Master rule (e.g., Daily, Weekly)
- `override_id`: Link to the master event title (for exceptions)

## How to use
1. Launch the app.
2. Grant permission to a folder (e.g., create a "MyCalendar" folder in Documents).
3. Use the "+" button to add events.
4. Edit the files manually in any text editor to see changes in the app.
