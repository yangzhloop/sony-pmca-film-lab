# Preview validation

Validated in the cloud environment with Python Playwright and `/usr/bin/chromium` in headless mode. This report covers the browser prototype only.

## Version 0.4 compact preset browser and focus menus

Validated on 2026-10-22. Six diagnostic unit tests and twelve browser scenarios
passed with no JavaScript page errors. The four new browser scenarios cover:

- Favorites → Built-in → My recipes ordering and default Favorites in both
  languages; list width equals one tab and its left edge remains fixed.
- No library details card or scene shading; long names ellipsize and Center
  reveals the full name on the unchanged detail/action page.
- Empty/single Favorites, first↔last wraparound, long user lists, New recipe,
  selection visibility, and restoration of the active scene on an empty tab.
- Simulated Focus mode/area selection and parent focus restoration without
  changing recipe state; wrapping in Settings/detail/editor and bounded numeric
  adjustment.

The current LCD captures are `v04-library-en.png`, `v04-library-zh.png`,
`v04-focus-mode-zh.png` and `v04-focus-area-zh.png` under `screenshots/`.
They were captured from the browser; the compact library and focus-area page
were visually inspected. Older overview images document previous layouts.

The rebuilt portable `preview.html` also passed Favorites/wrap/focus/Chinese and
transparent-preview checks while every secondary resource request was blocked.
No secondary requests or JavaScript errors occurred.

This version does not establish native camera focus availability, lens behavior,
or live-view performance. The browser scene and focus choices are illustrative.

Passed browser interaction checks:

- Highlighting a library preset does not change the active shooting snapshot.
- Built-in details do not expose Edit, Rename or Delete. Duplication creates an independent user draft.
- English/Chinese mixed names survive save, rename, UI language changes and page reload.
- Save-only preserves the active transform and revision; Save & use / Use changes the active snapshot.
- Editing the active recipe and saving a new revision shows that the saved changes are not yet in use.
- Deleting an active user recipe retains its current look. Save current look creates a new independent draft.
- Cancelling a draft preserves the saved library; cancelling the discard dialog preserves draft data.
- Monochrome profiles hide Color and remain grayscale in the illustrative preview.
- Physical direction-pad/center controls reach detail actions and the New recipe row. Enter opens the selected recipe's details.
- Rename/delete dialogs trap Tab focus, default destructive confirmation to Cancel, and return focus to their triggering actions.
- English and Chinese translation dictionaries have matching keys, including localized direction-button labels.
- Desktop rendering at 1440×1050 and a 390 px mobile viewport have no horizontal page overflow. Representative screens were visually inspected.
- No JavaScript page errors occurred in the tested recipe flow.
- The single-file `preview.html` loaded with all other resource requests blocked, confirming embedded styles, scripts and scene assets.

The environment's managed Chromium blocks `file://` navigation, so the single-file artifact was tested via HTTP with external resource requests blocked. Direct opening in an unrestricted browser was not separately tested.

Additional state-model smoke checks covered built-in mutation rejection, Unicode name validation, snapshot persistence and storage failure fallback.

## Version 0.2 physical controls and DISP

The user's layout/flow approval is recorded in the design documents. Independent Playwright checks passed for the refined controls, with no page errors:

- Left/Right changes library tabs. Up/Down includes New recipe, and Center opens the selected entry.
- Editor focus moves by field. Left/Right changes numeric values, base film and WB without relying on a native browser dropdown. Save and Save & use are reachable through physical controls.
- Direction/Center navigation works in recipe actions, confirmation dialogs and language settings. Directions never execute Delete on their own.
- The upper-wheel control acts as Up in menus and as the DISP simulation in shooting. D does nothing in the library, editor and discard dialog.
- Each of the four illustrative display states preserves the exact active recipe snapshot and scene transform.
- Clean display contains only the scene, with no shooting HUD, recipe label, screen menu or footer. Physical Fn/MENU still reach the library/settings in English and Chinese.
- Display choice and active settings survive menu visits, language changes and reload.
- Deleting the active user recipe preserves its look, and Save current look is reachable from settings while the shooting display is clean.
- Button-only save with an automatic name and keyboard/IME rename with mixed-language text remain functional. This does not validate a camera text-input method.

The bilingual screen overview and a separate four-mode DISP overview were captured from the updated prototype. The histogram/level are static demonstrations; the inherited Sony controller remains untested.

## Version 0.3 settings and diagnostics

Validated on 2026-10-17 with Python 3.12.14, Playwright 1.62.0, system Chromium 154, and Node 24.19.0. The reproducible checks are:

```sh
node --test design/ui-preview/tests/diagnostics.test.js
python3 design/ui-preview/tests/verify_settings.py
```

Six diagnostic unit tests passed, covering redaction of names/arbitrary text and imported IDs, safe key classifications, the 128-event bound and dropped count, stable English report keys across UI languages, independent snapshots, separate active/saved/draft revisions, UTF-8 report byte bounds and session report filenames.

Eight browser scenarios passed, with no JavaScript page errors:

- All settings pages in English/Chinese, MENU/Left hierarchy and parent-row focus restoration; flash Left/Right adjusts values and MENU returns.
- Directional navigation never downloads a report; Center or an explicit click requests the download.
- Flash example values remain independent of recipe preview/application.
- A downloaded TXT contains the expected versioned browser schema, omitted names and explicitly unavailable camera fields.
- An injected download-request failure retains the generated report. Retrying after a language change downloads the exact same bytes and filename.
- Key viewer isolates physical/keyboard controls, redacts printable input and exits with MENU; developer pages preserve recipes and allow scrolling.
- Draft data and built-in mutation restrictions remain intact.
- Clean DISP still reaches settings and Save current look; all ten subpages in both languages fit the mobile page without horizontal overflow.

The existing recipe-flow regression also passed: preview versus active separation, create/save/use/edit/revisions, mixed English/Chinese rename and reload, delete-active recovery, draft discard and monochrome behavior. Its screenshot-writing section was not run.

The portable v0.3 file was loaded over HTTP with every secondary resource request blocked. It rendered without external requests or JavaScript errors; physical menu navigation, TXT download, Chinese switching and clean-display preservation were exercised. Representative settings screens were visually inspected, and 22 bilingual LCD captures plus a settings overview were generated.

Recent events is a snapshot refreshed on reentry, not a live log viewer. Browser events are memory-only; the camera's planned rotating logs, crash snapshot and card export are not implemented here. A successful browser download request does not prove that a user saved the file.

Not validated: camera hardware, Sony base APK integration, actual capture or color rendering, native flash operation, camera-card TXT export, camera language resources/fonts, on-camera Chinese input, real camera key-event mapping and apply-failure recovery. The image and exposure readouts are illustrative.
