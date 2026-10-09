# Rideology Companion

Rideology Companion is an Android application designed to analyze log files exported by Kawasaki's RIDEOLOGY app. Its goal is to provide additional data analysis and charts beyond those available in RIDEOLOGY.

The current app version is **v0.1b**, displayed beside the author link. The label uses the version name configured in the app build.

## Project Goal

The intended workflow is to:

1. Import log files exported from RIDEOLOGY.
2. Process the recorded data to produce additional insights.
3. Present the results through data views and charts.

The first stage implements CSV import and a ride summary as described below. Telemetry panels (speed, RPM, and stepped gear) and a speed-distance distribution are displayed below the ride summary. They share a GPS-distance calculation that excludes recording gaps. Axis scales and interval rules currently follow the conventions below; exact equivalence with an external reference has not been established.

## First Stage: CSV Import and Ride Summary

Users must be able to open an exported CSV file from within the application or send it directly from RIDEOLOGY through Android's share flow. The application will process the file and display its ride title and the following information:

| Metric | Expected presentation |
| --- | --- |
| Maximum engine speed | RPM, with the associated duration in seconds and distance in meters |
| Maximum wheel speed | km/h, with the associated duration and distance |
| Maximum acceleration | g, with the associated duration and distance |
| Maximum braking deceleration | g, with the associated duration and distance |
| Maximum water temperature | °C, with the associated duration and distance |
| Average idle engine speed | RPM, using only records where wheel speed is zero |
| Average speed | km/h, using only records where wheel speed is nonzero |
| Median speed | km/h, using only records where wheel speed is nonzero |
| Total time | Hours, minutes, and seconds |
| Moving time | Hours, minutes, and seconds, considering only periods where wheel speed is nonzero |
| Distance | Traveled distance and straight-line distance between the endpoints, in km |
| Course | Compass direction and bearing in degrees |
| Starting and ending points | Coordinates in degrees, minutes, and seconds, with place names when available and links to open each location in a compatible map application |
| Maximum values for each gear | A table containing gear, maximum RPM, and maximum wheel speed in km/h |

Acceleration and braking deceleration must be derived from wheel speed and elapsed time because the supplied CSV does not contain acceleration or brake sensor fields. Place names are not included in the sample either; address lookup is implemented using the public Photon reverse-geocoding service.

### Calculation Rules

- Average idle engine speed uses only records where `wheel_speed(km/h) == 0`, regardless of gear position. No additional engine RPM filter is specified.
- Average and median speed use only records where `wheel_speed(km/h) != 0`; stopped periods are excluded from both metrics.
- Moving time considers only periods where `wheel_speed(km/h) != 0` and must use recorded elapsed timestamps rather than a count of samples. It is displayed separately from total time.

The first version uses these calculation conventions:

- Average idle RPM and average moving speed are arithmetic means of eligible samples. Median moving speed is the median of nonzero speed samples. Idle RPM includes engine-off records when wheel speed is zero.
- Total time is the difference between the last and first elapsed timestamps.
- Each sample is held until the next timestamp. Moving time sums these intervals when the initial sample has nonzero wheel speed. Traveled distance integrates the initial wheel speed over each interval.
- Sampling gaps use the same preceding-sample convention and produce a visible data note. These are estimates because no intermediate measurements are available.
- Maximum values are taken from samples. Their durations and distances sum all intervals whose initial sample equals the maximum, including separate occurrences. A maximum found only in the final sample has zero measured duration because no following interval is available.
- Acceleration and braking deceleration are estimated from the wheel-speed change divided by the actual interval duration and standard gravity (9.80665 m/s²). Matching peak intervals use their initial wheel speed for distance. If no acceleration or braking occurs, the corresponding peak is zero with zero duration and distance.
- Straight-line distance uses the haversine formula with a spherical Earth radius of 6,371,000 meters. Course is the initial bearing from the first coordinate to the last; coincident endpoints have no course.
- Missing or invalid measurement fields make dependent metrics unavailable. Invalid or non-increasing timestamps prevent import. At least two samples are required.

The supplied report illustrates the intended output; its numerical values are not hard-coded requirements.

### CSV Input

The supplied export begins with ride metadata, followed by a column header and data rows:

```csv
Title,From gas station to next gas station
elapsed_msec,gps_latitude,gps_longitude,water_temperature(℃),engine_RPM,wheel_speed(km/h),gear_position
940,-34.5082,-58.47964,65.0,1104,0.0,N
1940,-34.5082,-58.47964,65.0,1104,0.0,N
```

The source fields needed for the first-stage summary are:

| Source field | Use |
| --- | --- |
| `Title` metadata | Ride title |
| `elapsed_msec` | Timing and time-dependent calculations |
| `gps_latitude`, `gps_longitude` | Endpoints, straight-line distance, and course |
| `water_temperature(℃)` | Maximum water temperature |
| `engine_RPM` | Maximum engine speed, average idle speed, and RPM maxima by gear |
| `wheel_speed(km/h)` | Idle record selection, moving record selection, speed statistics, moving time, acceleration, braking deceleration, and speed maxima by gear, and distance integration |
| `gear_position` | Per-gear statistics; `N` represents neutral in the supplied example |

Future exports may contain more or fewer columns, metadata lines, or data rows. The parser must locate the data header, select fields by name, and ignore unused columns. Missing or invalid required data must be reported explicitly. Calculations must use actual timestamps, including gaps between samples. New analyses must trigger a review of the fields selected during import, as described in [AGENTS.md](AGENTS.md).

## Copying and Sharing a Summary

The share icon beside the **Ride summary** heading offers four actions:

- **Copy text** copies the report to the clipboard as plain text, containing only the metrics displayed in this section.
- **Share as message** opens the Android Sharesheet with bold headings and metric labels using WhatsApp-style asterisks, containing only summary metrics. Formatting depends on support in the receiving application. The user chooses the recipient and sends the message in that application.

- **Share JPG** shares a complete image of the report as an attachment through the Android Sharesheet.
- **Save to gallery** saves the summary JPG in the **Pictures / Rideology Companion** album.

The summary JPG contains only the summary metrics and ride title, with white metric icons and the black, green, and white interface style. Metric values and details align with the start of their labels, after the icon, both on screen and in JPG exports. Text exports, copied text, and JPG exports exclude locations, per-gear maxima, and data notes.

The **Locations** share icon offers **Copy text** and **Share as message**, including the ride title, the three location labels, coordinates at source precision in map URLs, and available address components. The **Max for each gear** share icon offers **Share JPG** and **Save to gallery** for the complete table, whose columns are centered and numeric values are right-aligned within each column. The highest available RPM value is highlighted in amber (`#FFB300`) on screen and in the JPG only when the last displayed gear row does not contain that maximum. If the last row contains the maximum, including a tie, no RPM value is highlighted. Otherwise, earlier ties share the highlight; unavailable values remain white. This section appears immediately after **Telemetry**.

### Location Details Lookup

After a ride is imported, the app queries [Photon's public demo service](https://github.com/komoot/photon) for the starting point, ending point, and first maximum-speed location, deduplicating identical coordinates. Available components are displayed under their coordinates and included in copied/shared location text: street and house number (`street`, `housenumber`), neighborhood (`district`), city (`city`), and state/province (`state`). Missing components are omitted; other address levels are never substituted for a neighborhood or street. Missing results, network failures, and offline use omit address details without a data note. Coordinates remain visible with their map controls.

Requests run in the background, sequentially and at least 1.1 seconds apart, with five-second connection/read timeouts. Only selected address components are retained. A new cache schema refreshes previous neighborhood-only results. Positive results are cached on-device for 30 days; empty or failed lookups are cached for 10 minutes. Only endpoint/maximum-speed coordinates are sent to `photon.komoot.io`; the CSV, ride title, and remaining track are not uploaded. Reverse-geocoded addresses are approximate and depend on OpenStreetMap coverage. Credits: location data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/), served by [Photon](https://github.com/komoot/photon). Credits are documented here; they are omitted from Locations and shared text. The public service permits reasonable request volumes, may throttle extensive use, and has no availability guarantee; larger deployments should use their own Photon instance.

Telemetry canvas heights are shared by screen and JPG: speed 242 dp (+10%), RPM 198 dp (-10%), and gear 80⅔ dp (10% taller than the previous 73⅓ dp). Title spacing and top plot margins are reduced; the compact gear panel uses smaller axis labels. Export includes every complete panel regardless of screen scrolling. Telemetry distance axes use at least four integer ticks, spaced by 5 or 10 times powers of ten; the axis rounds up to a whole tick. Rides below 15 km display meters to preserve useful detail without decimal ticks; other rides display kilometers. Tick density adapts to available width and text size. All three panels use the same GPS distance domain. Speed and Engine RPM have compact 20 dp green zoom icons with 48 dp touch targets that open a landscape screen with a larger single chart, a green back-arrow icon, a title centered between the back and share controls, a full-width green divider below the header, and JPG sharing/gallery actions. Zoom exports use the same plotting functions and axis rules in a landscape image at least 1920 × 1080 pixels. Rotation and activity recreation preserve the selected zoom chart.

## Location Links

Every geographic location or coordinate displayed in the application must include an accessible link to open that location in a compatible external application, such as Google Maps. This applies to ride endpoints and all future location displays.

Links must target the corresponding coordinates at their source precision, regardless of display formatting, and allow Android to handle compatible applications. If no compatible application is available, the application must show a clear message. This behavior is implemented using Android location intents.

The interface omits the Calculation notes panel; calculation conventions remain documented in this README.

## Visual Style

The interface uses a simple dark style inspired by Linux terminals and minimal interfaces:

- Black backgrounds.
- White text.
- Kawasaki green accents for lines, button outlines, and panel borders. The project uses `#66FF00` as a Kawasaki-inspired green accent; it is not presented as an official brand specification.
- Monospace typography, particularly for metrics and tables, with clearly aligned labels, values, and units.
- Flat buttons and panels with thin green borders and black backgrounds.
- Minimal decoration, with readable spacing and accessible controls.
- A green scrollbar on the right, with a 12 dp visible track and a 48 dp touch area. A compact gutter lets panels extend closer to the track; the touch area overlaps the panel edge and its empty inset. Drag the thumb to scroll, or tap the track to jump to a position. Accessibility services can also adjust its scroll position.
- Smaller monospace text for peak duration and distance details beneath each primary metric value.

The palette will remain consistent across screens rather than adapting to system dynamic colors. This visual style is implemented in the shared Compose theme.

## Current Status

The first version implements:

- CSV selection through the Android document picker, file opening through compatible content intents, and reception through both Android `ACTION_SEND` and `ACTION_SEND_MULTIPLE` sharing. When several files are shared, a dialog lets the user choose a log to analyze.
- Unicode ride titles with system font fallback and emoji support; metrics and tables retain monospace typography.
- An `Open Rideology app` button launches the installed RIDEOLOGY application, or explains when it is unavailable.
- `Open CSV` opens an Android chooser for compatible installed file browsers using `ACTION_GET_CONTENT`. Browsers must support returning an openable file URI to appear in this chooser.
- A parser that selects columns by name, supports variable metadata before the data header, ignores unused columns, and reads quoted CSV fields, UTF-8 byte order marks, and common line endings.
- Ride statistics, per-gear maxima, endpoint coordinates and the maximum-speed location with green map-pin buttons, and visible data-quality notes. The maximum-speed location uses the first sample attaining the maximum wheel speed. If that sample lacks valid coordinates, the location is unavailable; coordinates from a different sample are never substituted. Location text exports include this location and its map link.
- Plain-text report copying and sharing through the summary header menu.
- A black, white, and green terminal-inspired interface, with the app launcher icon beside the title. `Data notes` appears only when import finds data-quality issues; using a supported encoding does not create a note.
- Background processing and reloading of the selected file after activity recreation.

Import supports up to 500,000 samples per file. Files must use comma-separated fields. Encoding detection tries strict UTF-8 first, then Windows-31J (Shift-JIS); BOM-marked UTF-16 is also supported. Characters already replaced or damaged by the exporter cannot be recovered. Malformed Windows-31J characters are omitted from the title so they do not prevent importing valid measurements; malformed measurement values remain subject to normal validation. Unrecognized gear values are silently excluded from the per-gear table; valid gears and other statistics remain available. Saved ride history and persistent imported ride data are not implemented; address lookup results are cached separately. A device export confirmed that RIDEOLOGY uses `ACTION_SEND_MULTIPLE` with `text/plain`, including when sharing one log. End-to-end file access from a real export still needs device verification.

## Technology Stack

- Kotlin
- Jetpack Compose and Material 3
- Gradle with Kotlin DSL
- AndroidX

The minimum supported Android version is Android 7.0 (API 24). The project uses compile SDK and target SDK 37.

## Getting Started

1. Open the repository in Android Studio.
2. Install Android SDK Platform 37 through the SDK Manager.
3. Allow Gradle to synchronize and download the required dependencies.
4. Select an emulator or device running Android 7.0 or later and run the `app` configuration.

## Build and Test

From the repository root, with a compatible JDK and the Android SDK configured:

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Run local unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run instrumented tests with a connected device or running emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Unit tests cover parsing, missing and invalid fields, timing gaps, stopped rides, maxima, and coordinate formatting. Instrumented tests cover CSV attachment reception, summary presentation, activity recreation, missing attachments, clipboard copying, the plain-text payload passed to the Android Sharesheet, and scrollbar touch target size, dragging, track taps, and accessibility adjustment. A synthetic CSV fixture is included only in debug builds at `app/src/debug/res/raw/sample_ride.csv`.

## GitHub Releases

The [release workflow](.github/workflows/release.yml) runs when a tag matching `v*` is pushed. It installs JDK 25 and Android SDK 37, runs local unit tests, builds the release APK, validates the tag against the generated APK metadata, then aligns, signs, and verifies the APK before creating a GitHub release with automatically generated notes and the signed APK attached. Signing is performed by the workflow; local `assembleRelease` builds remain unsigned.

The tag must be exactly `v` followed by `versionName` from `app/build.gradle.kts`. Numeric versions such as `0.2.0` create normal releases. Versions ending in `b`, `b` plus a number, `-beta`, or `-beta.` plus a number create prereleases, for example `v0.1b` or `v0.2.0-beta.1`. Other suffixes are rejected rather than published as stable releases. Prereleases are not marked as the latest release.

Configure these repository secrets under **Settings → Secrets and variables → Actions** before pushing a release tag:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore file. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Alias of the release signing key. |
| `ANDROID_KEY_PASSWORD` | Password of that key; use the keystore password if they are the same. |

Use an existing release signing key if the app has already been distributed. Otherwise create a key once with Android Studio's **Generate Signed Bundle / APK** wizard and keep a secure backup. Future APK updates must use the same signing key. Never commit the keystore or passwords. To encode the keystore on Linux, run `base64 -w 0 /path/to/release.jks` and store the output directly in the secret. The workflow uses GitHub's automatic `GITHUB_TOKEN`; no personal access token is required. Repository or organization policies must allow the workflow's `contents: write` permission to create releases.

For each release:

1. Update `versionName` and increment `versionCode` in `app/build.gradle.kts`.
2. Commit and push the changes, including the workflow for the first release.
3. Create and push the matching tag, for example:

```bash
git tag -a v0.2.0-beta.1 -m "Release v0.2.0-beta.1"
git push origin v0.2.0-beta.1
```

The example assumes `versionName = "0.2.0-beta.1"`. Track the run in GitHub's **Actions** tab and download `rideology-companion-v0.2.0-beta.1.apk` from **Releases** after success. A missing secret, mismatched tag, failing test, or invalid signature prevents publication. Existing releases are not overwritten; reruns cannot replace a published release. Instrumented tests still require a device or emulator and are not run by this workflow.

Run the release metadata checks locally with `python3 -m unittest discover -s scripts -p 'test_*.py'`.

## Project Structure

- `app/src/main/java/com/jbokser/rideology_companion/MainActivity.kt`: application entry point and current screen.
- `app/src/main/java/com/jbokser/rideology_companion/data/`: CSV parsing, ride data models, and analysis.
- `app/src/main/java/com/jbokser/rideology_companion/ui/theme/`: Compose theme definitions.
- `app/src/main/res/`: Android resources.
- `app/src/test/`: local unit tests.
- `app/src/androidTest/`: instrumented tests.
- `gradle/libs.versions.toml`: dependency and plugin versions.

## Development Guidelines

See [AGENTS.md](AGENTS.md) for project development guidelines. All project documentation and code comments must be written in neutral, professional English.

## Telemetry and Speed Distribution

The telemetry section stacks speed (blue), engine RPM (purple), and gear (green) panels. All use the same cumulative GPS-distance domain in kilometers. Speed and RPM show labeled red markers (`#FF4444`) at the first occurrence of their respective maxima, including the zoom views and JPG exports. Duplicate maximum-description footers and the GPS-distance footer are omitted; the engine panel title is `Engine RPM`. Gear uses horizontal steps followed by vertical transitions, with neutral at N and numbered gears at 1–6. Unknown gears interrupt the line. Missing measurements and recording gaps also interrupt their corresponding traces.

GPS interval distance uses consecutive valid coordinates and the existing haversine calculation with an Earth radius of 6,371,000 meters. Intervals longer than 1.5 times the median sampling interval add no distance. Missing coordinates are not bridged. These chart distances differ from the summary's wheel-speed distance estimate.

The speed distribution uses the same eligible GPS intervals, assigning each interval's distance to the ending sample's speed. Fixed 20 km/h bins include the lower boundary and exclude the upper boundary: [0,20), [20,40), and so on. Empty bins between occupied bins remain visible. Missing speeds are excluded; zero speeds can receive GPS distance. Distances are summed without intermediate rounding and displayed above the blue bars with two decimal places. The bar chart uses compact bars and speed-range tick labels rotated 45 degrees. It scrolls horizontally when required.

Speed and RPM scales start at zero and round upward using tick steps based on 1, 2, or 5 times a power of ten. Telemetry distance scales use integer 5/10 steps and meters for rides below 15 km, as described above. An external reference implementation was not supplied; these defaults do not claim to reproduce its original scales, boundary inclusion, or rounding. Chart geometry and GPS statistics are prepared outside the main UI thread.

### Exporting Chart Images

The share icon beside each chart section offers **Share JPG** and **Save to gallery**. Telemetry exports one complete image containing the three panels; the speed distribution exports all ranges, including bars outside the visible horizontal viewport. Both exports include the ride title and use the same scales, drawing functions, data, colors, and peak markers as the on-screen charts.

JPGs use a black background, JPEG quality 95, and a width of at least 1,080 pixels. Export rendering and file writing run off the main thread. Sharing uses a temporary read-only content URI and Android's chooser so messaging applications can receive the JPG as an attachment. Saving publishes the image to the `Pictures/Rideology Companion` album. Android 10 and newer use MediaStore without storage permission; Android 7–9 request storage permission only when saving to the gallery.

The subtitle reads `Ride log analysis by @jbokser`; the green, underlined author link opens https://github.com/jbokser.
