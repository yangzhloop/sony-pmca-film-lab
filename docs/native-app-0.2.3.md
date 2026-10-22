# Film Lab 0.2.3-alpha

This update implements the requested preset browser and physical-button menu
improvements. It keeps the startup and launcher-label fixes from
[0.2.2](native-app-0.2.2.md), including saved-look restoration after native boot.

## Preset browsing

The categories are **Favorites / 收藏 → Built-in / 内置预设 → My recipes / 我的配方**.
Opening Film presets starts in Favorites. Returning from a recipe's detail page
keeps that category and selection. Empty Favorites shows a short hint to press
Right for Built-in; it does not select or save a recipe automatically.

The preset list occupies **one tab's width**, fixed to the left edge of the
three-tab strip. Its position stays fixed as Left/Right switches categories.
The library page has a transparent background, with no full-screen dark mask
and no inline selected-recipe information card. The remaining camera view is
clear. Header, tabs, list and footer retain their menu backgrounds for legibility.

Compact rows use smaller cartridge icons and single-line names with ellipsis.
The saved name is untouched. Center still opens the full name and existing Use,
Favorite, Duplicate and user-recipe actions. The detail/editor screens retain
their information panels. Preview processing and the camera's exposure do not
change as part of removing the overlay mask.

Highlight movement reuses row views and keeps the existing 120 ms latest-only
preview queue. Switching to an empty category restores the committed look. If
restoration fails, the existing category remains visible with an error. Removing
the last favorite likewise keeps the detail screen open until restoration can
succeed before returning to an empty Favorites list.
If restoration fails while opening Film presets normally or through Settings,
Film Lab shows Settings with the error, allowing the user to retry safely.

## Physical navigation

For Film Lab's vertical row menus, Up at the first row selects the last row;
Down at the last row selects the first row. The list scrolls to reveal the new
selection. This applies to the library, recipe actions, editor rows, Settings,
Camera settings, Flash, Language, report/developer menus and confirmations.
Empty lists remain safe and single-row lists cause no repeated preview request.
Visible disabled fields retain their previous focus behavior, while activation
remains disabled. Numeric setting limits and keyboard/grid movement retain
their existing rules.

The inherited Sony menus already wrap: `ItemAlignedListView` handles main-page
lists, `SpecialScreenView` handles focus/flash lists, and the submenu/vertical
views link first and last entries. Their existing behavior is retained and
covered by source-protection checks; no new native input patch was needed.

## Focus settings

**Settings → Camera settings** now includes **Focus mode / 对焦模式** and
**Focus area / 对焦区域**, following White balance. They open Sony's existing
`FocusSelection` and `FocusArea` menus through the existing shooting bridge.
Sony's controllers continue to determine supported choices and restrictions for
the attached lens, current focus mode and shooting state. Focus-position grids
and native setting limits retain their own navigation.

## Installation and device checks

Install [FilmLab-0.2.3-alpha.apk](../dist/FilmLab-0.2.3-alpha.apk) as an update
through the existing [PMCA installation workflow](camera-build.md). Version code
5 uses the retained package/signing identity, preserving stored recipes and
language data during an in-place update.

1. Confirm About shows **0.2.3-alpha**. Open Film presets and verify Favorites
   appears first and opens by default; Right reaches Built-in, then My recipes.
2. Check the fixed one-tab-wide list and long English/Chinese names on LCD and
   EVF. Confirm the camera image outside the menu panels is not darkened.
3. Press Up on the first preset and Down on the last preset. Check both jumps
   reveal the selected row; hold an arrow and confirm the final highlight wins.
4. Check wrapping in Settings, Camera settings, Language, recipe details and
   editor fields. Confirm value adjustment still stops at supported limits.
5. Test native Focus mode and Focus area with the attached lens. Verify the
   actual camera setting changes, native list wrapping and focus-position controls.
6. Preview, cancel and Use presets; remove the only favorite; switch into an
   empty category. Confirm the committed look returns on cancellation.
7. Check Center details, custom-recipe naming, native capture/DISP/playback,
   Exit app and issue-report export still behave as expected. Save a fresh
   report if any transition fails or responsiveness regresses.

The cloud tests verify state, input paths and layout parameters. Actual Android
text measurement, transparency over Sony live view, LCD/EVF rendering and focus
behavior require this APK to be tested on the A7R II. The browser preview is a
separate simulation. See the [validation record](native-app-0.2.3-validation.md).
