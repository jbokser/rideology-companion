# Changelog

Significant changes to Rideology Companion are listed here. Published releases and prereleases appear newest first, with unreleased changes at the top. Dates use the GitHub publication date in UTC.

## Unreleased

### Added

- Export or share all imported CSVs as a ZIP and import CSVs from ZIP archives through the history screen, preserving original CSV bytes. Archives contain only CSVs at the root with the same filenames as individual sharing; duplicate filenames receive numbered suffixes. Import reads root-level CSVs.

- Add theoretical per-gear speed estimates at the highest historical RPM, highlighting estimates within 1 km/h of recorded maxima in screen and JPG tables.

- Persist historical maximum RPM and wheel speed per gear for each Rider/Bike pair, with record-breaking alerts, shareable tables, gallery exports, and deletion.

- Share original CSV files or save a copy through the system file picker from import history.

- Show Rider and Bike from CSV account and vehicle metadata in ride summaries.

### Changed

- Always show the CSV history button so ZIP import is accessible before any CSV has been opened.

- Use smaller titles and tighter spacing in CSV import history while preserving accessible touch targets.

### Fixed

- Keep Rider and Bike together with their icons in one centered row in historical gear JPG exports.

- Reuse the existing application activity when opening or receiving CSV files from other apps instead of creating additional instances.

- Recover damaged CSV header titles from matching export filenames while preserving intact header text and recovered titles when reopening import history.

- Calculate average idle engine speed only from stationary samples in neutral with the engine running, excluding zero RPM and samples in other gears that could distort the result.

## [v0.1b3](https://github.com/jbokser/rideology-companion/releases/tag/v0.1b3) — 2026-16-09 — Prerelease

### Changed

- Display telemetry distance axes consistently in kilometers, with uniform 1–2–5 tick intervals and precision adapted to the interval.

### Added

- Persistent CSV import history.
- GNU General Public License version 3 (GPL-3.0-only), with the full license text and a README notice.
- This changelog and development guidelines for maintaining release history.

## [v0.1b2](https://github.com/jbokser/rideology-companion/releases/tag/v0.1b2) — 2026-10-09 — Prerelease

First published prerelease. Includes the initial application functionality and the build correction made after the `v0.1b1` source tag, which has no associated published GitHub release.

### Added

- Import RIDEOLOGY CSV files through the file picker or Android sharing, with flexible column mapping and support for UTF-8, Windows-31J/Shift-JIS, and BOM-marked UTF-16 exports.
- Ride summaries covering speed, engine RPM, acceleration, braking, coolant temperature, total and moving time, and distance, plus per-gear maxima and data-quality notes.
- Speed, engine RPM, and gear telemetry charts over cumulative GPS distance, maximum markers, speed distribution by GPS distance, and expanded speed and RPM views.
- Ride endpoint and maximum-speed location links, with address lookup when available.
- Copy and share text reports; save and share JPG images of summaries, per-gear maxima, and charts.
- A dark terminal-inspired interface with green accents and support for Android 7.0 and later.
- Automated release validation, APK signing, and GitHub publication, with local unit tests in the release workflow.

### Fixed

- Correct the Android SDK Platform 37 package name to `platforms;android-37.0` in the release workflow and setup instructions.
