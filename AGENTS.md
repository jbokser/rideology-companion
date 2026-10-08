# Development Guidelines

## Documentation and Code Comments

- Write all project documentation and code comments in neutral, professional English, using standard software industry terminology.
- Apply this rule to Markdown files, inline comments, and API documentation such as KDoc and Javadoc.

## Project README

- Maintain a `README.md` at the repository root that explains the project, its current functionality, technology stack, and setup and build instructions.
- Keep the README aligned with the implementation. Clearly distinguish implemented functionality from planned features.
- Update the README when changes affect the project description, functionality, or setup and build instructions.

## User Interface Style

- Use a dark visual style with black backgrounds and white text.
- Use Kawasaki green as the primary accent for lines, button outlines, and panel borders. Use the project accent `#66FF00`; this is a Kawasaki-inspired choice, not a verified official brand color.
- Keep screens simple and functional, inspired by Linux terminals and minimal interfaces. Prefer flat surfaces, thin borders, restrained decoration, and clear alignment.
- Prefer monospace typography for the terminal-inspired appearance, especially metrics and tables. Keep labels, values, and units easy to scan.
- Use black button and panel backgrounds with green outlines. Avoid gradients, decorative shadows, and unnecessary animations.
- Preserve readability, accessible touch targets, and support for larger text sizes while applying the minimal style.
- Apply the palette consistently through shared theme definitions rather than independent colors in individual screens. System dynamic colors must not override the specified palette.

## Location Links

- Whenever a geographic location or coordinate is displayed, provide a clearly identifiable, accessible link to open that location in a compatible external application, such as Google Maps.
- Apply this rule throughout the application, including ride endpoints and any future location displays.
- Open the corresponding coordinates through Android's supported location-link handling, allowing compatible applications rather than requiring Google Maps specifically.
- Preserve the source coordinate precision in the link, even when the displayed coordinates are rounded or formatted differently.
- If no compatible application can open the link, show a clear message instead of failing silently or crashing.

## RIDEOLOGY CSV Import and Data Selection

- Support CSV files opened by the user and CSV files received through Android's share flow from RIDEOLOGY. Both entry points must use the same parsing and analysis logic.
- Treat the export format as evolving: columns may be added, removed, or reordered, and the number of metadata or header lines and data rows may vary.
- Identify the data header and map columns by their names, rather than relying on fixed column positions or a fixed number of preceding lines. Read relevant metadata, such as the ride title, when available.
- Extract and retain only the fields needed by the implemented analyses. Ignore unused columns; do not require an exact match to the complete sample schema.
- When adding or changing an analysis, review which source fields it requires and update the selected fields and their documented mapping accordingly. Do not assume that previously ignored fields are available in the parsed data.
- Validate the fields required by each analysis. If fields are missing or invalid, report the affected metrics as unavailable or explain why the file cannot be processed; never silently substitute zero values.
- Use recorded timestamps for time-dependent calculations. Do not assume a fixed sampling interval or infer elapsed time from row counts; the supplied example includes gaps between samples.
- Calculate average idle engine speed using only records where wheel speed equals zero, regardless of gear position. Do not add an engine RPM filter unless the requirements change.
- Calculate average and median speed using only records where wheel speed is nonzero.
- Report moving time separately from total time. Moving time considers only periods where wheel speed is nonzero and must be calculated from recorded timestamps, not sample counts.
- Keep the example report as a presentation reference, not as hard-coded results. Follow the calculation conventions documented in the README, including sample-based averages, preceding-sample interval estimates, gap reporting, wheel-speed distance integration, and cumulative duration and distance at maxima. Update the documented conventions whenever calculation behavior changes.

- Decode UTF-8 without replacing malformed bytes. Support Windows-31J/Shift-JIS exports and BOM-marked UTF-16 files, preserving Unicode titles whenever the source encoding supports them. Do not guess or reconstruct characters already replaced by the exporter. Malformed Windows-31J text must not block otherwise valid measurements: decode it with replacement markers, omit those markers from the displayed title, and retain normal validation for measurement fields.
- Silently exclude unrecognized gear values such as `Error` from per-gear grouping. Do not announce these exclusions in the interface or shared report. Preserve valid gear groups and other analyses.

- Show the Data notes section only for actual data-quality issues. Successfully decoding a supported encoding is not an issue and must not create a note.

## Telemetry and Speed Distribution Charts

- Use one cumulative GPS distance domain for speed, engine RPM, and gear panels. Chart distances are separate from the existing wheel-speed distance estimate in the summary.
- Exclude GPS distance between consecutive samples when their time interval exceeds 1.5 times the median sampling interval. Do not bridge missing coordinates.
- Assign eligible GPS interval distance to the speed of the ending sample for the speed distribution. Do not substitute duration or wheel-speed integration.
- Mark the first occurrence of maximum speed and maximum RPM. Represent neutral as N and numbered gears as 1–6; unknown gears interrupt the stepped line.
- Current chart defaults, authorized when proceeding without the reference: automatic zero-based 1/2/5 tick scales, 20 km/h bins including the lower boundary and excluding the upper boundary, and two decimal places for displayed bar distances after unrounded accumulation. Do not claim reference equivalence without checking its code.

- Chart JPG exports must include complete panels and all distribution ranges, independent of screen scroll position, and reuse the same plotting functions and data. Share files through narrowly scoped FileProvider content URIs with temporary read permission. Save gallery images in Pictures/Rideology Companion; request legacy storage permission only on Android versions that require it.
