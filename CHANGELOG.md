# Changelog

## [v0.4.0] - 2026-03-23

### ‼️ Major: ICS File Format Migration
- **Calendar events now use standard `.ics` (iCalendar) format** instead of Markdown files, powered by the ical4j library.
- New `IcsConverter` class handles parsing and generating RFC 5545-compliant ICS content.
- Supports DTSTART/DTEND, SUMMARY, DESCRIPTION, LOCATION, COLOR, UID, and RRULE properties.
- All-day events, recurring events, and timezone-aware timestamps are fully supported.
- Directory structure updated: `/{CalendarName}/{YYYY}/{MM}/{YYYYMMDD}_{Title}.ics` with `/_Recurring/` for recurring events.

### Added
- **File Health Repair Pass:** Post-sync repair pass detects and fixes misplaced, wrongly-named, or malformed `.ics` files without deleting data.
- **ICS round-trip unit tests** (`IcalTest.kt`) covering all-day events, recurring events, UTC/timezone parsing, and full `toIcs`→`parseIcs` round-trips.
- **Diagnostic logging** for ICS parse failures — the actual exception message is now captured in the sync log.

### Fixed
- **Content truncation bug:** Replaced all `openInputStream` calls with `openFileDescriptor` across `SyncEngine.kt`, `YamlConverter.kt`, and `VCardParser.kt` to bypass Android SAF file-size caching bugs that caused note content to be silently truncated.
- **ical4j JCache crash on Android:** Added `MapTimeZoneCache` system property to prevent `javax.cache.CacheException: No CachingProviders have been configured` — was causing every ICS file to appear malformed on Android.
- **Spurious repair cycle:** The repair pass now checks for exact path match first, then falls back to tolerant matching for conflict suffixes (`Title (1).ics`) and ±1 month timezone drift. Previously, events were being "repaired" (rewritten) on every sync.
- **API level compatibility:** Replaced `Duration.toSeconds()` (API 31+) with `Duration.seconds` to fix NewApi lint error on devices running API 26–30.
- **Contact sync stability:** Replaced all `getColumnIndex` with `getColumnIndexOrThrow` in `ContactSyncEngine.kt` to prevent silent failures from missing cursor columns.
- **Suspicious indentation** in `saveToFile` that could cause incorrect code execution flow.

## [v0.3.1] - 2026-03-11

### Fixed
- Fixed an issue where synchronizing calendar events would inadvertently clear out the `body`, `metadata`, `tags`, `sourceUri` and `fileName` when merging dirty calendar provider changes.
- Fixed an issue where the `YamlConverter` would write only `reminder` instead of both `reminder` and `start` as standard markdown expects during `toMarkdown()`.

## [v0.3.0] - 2026-02-27

### Added
- **Contacts Sync**: Two-way synchronization between standard `.vcf` format files and Android System Contacts. 
- Deep integration with local-first file topologies for contacts. Deleting contacts natively manages the corresponding `.vcf` file securely.

### Fixed
- Stabilized Contact aggregation logic. Favouriting a contact or merging a contact inside the regular Android contact application will no longer accidentally delete its source `.vcf` file during the sync thanks to robust data hashing logic.
- Resolved an infinite spinning UI state issue where manual sync processes would get stuck permanently "pending" because of underlying Android `JobScheduler` limits. Applied `EXPEDITED` and `FORCE` flags with a strict timeout failsafe.
- General synchronization stability and crash fixes immediately after initial onboarding.
