# Film Lab UI / UX preview

Interactive design proposal for the Sony A7R II film-recipe app. This is a browser prototype, not an installable camera application. It contains original UI code and artwork; no upstream APK code or color assets are included.

**Layout and recipe flow confirmed by the user.** Version 0.4 makes Favorites the first/default category, uses a fixed left preset list one tab wide, removes the browsing details card and scene shading, and adds simulated Focus mode / Focus area pages. Physical Up/Down wraps vertical menu selections. See the [control map](CONTROLS.md).

The settings preview includes Flash settings, Camera settings, Language, Save issue report, About and Developer tools. Camera settings contains Focus mode / 对焦模式 and Focus area / 对焦区域. Focus and flash options here are demonstrations, independent of recipe data; actual available camera controls belong to Sony's native menus. This browser cannot perform camera-card export.

Review the current compact browser in [English](screenshots/v04-library-en.png) and [中文](screenshots/v04-library-zh.png), plus [Focus mode](screenshots/v04-focus-mode-zh.png) and [Focus area](screenshots/v04-focus-area-zh.png). The earlier [screen overview](screenshots/overview.png), [settings overview](screenshots/settings-overview.png), and [DISP illustration](screenshots/disp-overview.png) remain historical examples.

Open `preview.html` for a portable, self-contained version, or serve this directory:

```sh
python3 -m http.server 8080 --bind 0.0.0.0 --directory design/ui-preview
```

Then visit `http://localhost:8080`. The served entry is `index.html`. No package installation or external network requests are needed to use the preview.

## Review these flows

1. Start in Favorites, then use Right for Built-in and Right again for My recipes. Highlighting is a temporary preview; Center opens Details and Use applies it to the simulated shooting screen. The one-tab-wide list stays on the left, and only its panel covers the scene. MENU / Escape backs out; AF / HALF returns to shooting.
2. Duplicate & customize a built-in. Enter an English, Chinese or mixed-language name and adjust its settings. Save preserves the current shooting look; Save & use changes it.
3. Open My recipes. Only user recipes offer Edit, Rename and Delete. Favoriting a built-in does not change these restrictions. Delete asks for confirmation.
4. Change English / 简体中文 in the page header or the in-camera Settings view. User names and draft values remain unchanged.
5. Edit an active user recipe and Save only. Its saved revision is separate from the shooting snapshot. Deleting an active recipe retains its current look, with an option to save that look as a new recipe.
6. From shooting, press MENU to open settings. Enter Flash settings to adjust example mode/compensation values independently of your recipe. These values are simulations, not detected camera capabilities.
7. Open Save issue report and explicitly request a TXT download. Read the file and attach it manually if reporting a preview issue. The browser cannot verify that you saved the file; its status reports a download request, never camera-card success.
8. Inspect About, sources/licenses and the four read-only developer views. In Key viewer, try directions, Fn, DISP and shutter: they are observations only. MENU returns to Developer tools. Recent app events shows a bounded snapshot, newest first; leave and reenter to refresh it.
9. Open Camera settings, then Focus mode or Focus area. Up/Down moves through illustrative choices; Center selects one and returns to Camera settings. MENU cancels browsing. These simulated settings do not alter a recipe or prove lens/camera support.

Use the simulated physical controls or arrow keys and Enter. In the library, Left/Right changes tabs and Up/Down changes rows. Vertical selection wraps from the first row to the last and vice versa, including New recipe. Empty Favorites remains inert with a Right-to-Built-in hint. Long names use ellipsis in the narrow list and appear in full on the detail page. In the editor, Up/Down selects a setting and Left/Right changes its value or choice; numeric limits remain bounded. Center enters a name field or confirms the focused action. Escape/MENU returns; H simulates half-shutter. Clicking the screen remains a review convenience, not a camera touch requirement.

In shooting, the wheel's upper **DISP** position or the desktop **D** key cycles the illustrative display modes. MENU opens settings and Fn opens recipes, including when the clean display hides screen controls. Shooting Left/Right remains reserved for Sony's native/user-assigned functions; this browser prototype does not emulate those functions. In menus, Up is navigation and D does not switch the shooting display. On the shooting screen, Space or the shutter button displays a simulated capture; it does not produce a photo. From menus the prototype shutter first returns to shooting; from a dirty editor it protects the draft.

Text entry uses the computer's keyboard/IME. Center enters the field and Escape returns to row navigation while retaining the draft. A wheel-operated camera keyboard and Chinese input/candidate selection still require design/integration validation; custom text entry has not been demonstrated entirely with the simulated D-pad.

## Review scope

- Sony-style dark menus, compact rows, amber focus proposal, film-cartridge icons and user-recipe pencil markers.
- English and Simplified Chinese menu layouts, recipe naming and recipe management.
- Eight illustrative built-in profiles and two explicitly seeded example user recipes (`Weekend Gold` and `雨后街角`). Final preset names and calibrated values remain open.
- Recipe data and language choice persist in this browser's local storage. They are never sent to a server. The portable and hosted versions may have separate browser storage.
- Flash examples, recent diagnostic events and report retry state last only for the current page session. Reports use stable English field keys in either UI language; names, notes, photos and printable key input are omitted. User recipe IDs become session aliases. Camera firmware, readback, matrix/curve and card status are explicitly unavailable. The browser report contains a versioned JSON object inside a TXT file; the separate camera report template describes future camera diagnostics.
- The generated sample scene and CSS color changes are illustrative. They do not reproduce Sony matrix/curve processing, evaluate film accuracy, or validate white-balance scales.
- The logical review screen is 640×480, scaled to fit the page. Exact Sony resource geometry, focus colors, fonts, camera key events, Chinese on-camera input, capture behavior and A7R II hardware compatibility still need validation.
- This prototype does not simulate camera application failures, storage/media errors or RAW/video capture. Browser storage unavailability is handled in memory with a notice. A browser download-request failure retains its generated report for retry; this does not validate recovery from a camera-card write failure.

The surrounding light page and walkthrough are review aids. Only the dark camera display represents the proposed application UI. Layout/flow approval is recorded from the user's conversation, not from any automatic browser action.

## Files

- `model.js`: recipe ownership, drafts, preview/active state, saved revisions and local persistence.
- `diagnostics.js`: redacted snapshots, bounded session events and browser-only TXT report generation.
- `i18n.js`: English and Chinese base strings; presentation-specific strings live in `ui.js`.
- `ui.js`, `styles.css`: view rendering, interactions and original SVG icons.
- `scene.png`: generated demonstration scene.
- `preview.html`: generated portable version of the above files.
- `screenshots/`: representative layouts captured from the actual prototype.
- `tests/`: diagnostic invariants and browser settings/navigation checks.

To rebuild the portable page after edits, run `python3 design/ui-preview/bundle.py` from the repository root. Review artifacts and code are a proposal; no firmware or camera APK is modified.

To validate the new diagnostics and settings, with the local server running:

```sh
node --test design/ui-preview/tests/diagnostics.test.js
python3 design/ui-preview/tests/verify_settings.py
```

The browser check uses Python Playwright and the system Chromium. These are test-only dependencies; the preview itself runs without packages. See [validation evidence and limits](VALIDATION.md).
