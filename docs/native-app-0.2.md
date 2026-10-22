# Film Lab 0.2.0-alpha

This describes the original base recipe flow. See [0.2.3-alpha](native-app-0.2.3.md)
for compact Favorites-first browsing, focus settings and menu wrapping, and [0.2.1](native-app-0.2.1.md)
for interaction, reports and Exit app. Current normal launches open the library
every time; the once-per-installation behavior described below is historical.

This version ports the approved recipe flow into native camera UI. Film Lab's
menus overlay Sony's live shooting activity; the diagnostic launcher is no longer
the entry point. Sony retains camera ownership, capture, playback, flash and DISP.

## Upgrade and first launch

Use `dist/FilmLab-0.2.0-alpha.apk` with the existing PMCA installation workflow in
[camera-build.md](camera-build.md). It uses the same Film Lab package and local
signing identity as the first diagnostic build, with a higher Android version
code. Install it as an update to retain application data. There is no need to
uninstall the earlier Film Lab build or install upstream Film Studio separately.

The application entry is **Film Lab**. On first use of this version, the library
opens over live view. Afterward, the saved active look is restored when shooting
starts. The previous diagnostic language preference is migrated where available.

| Context | Controls |
| --- | --- |
| Shooting | Fn or Center opens recipes; MENU opens Film Lab Settings; DISP remains Sony's native control |
| Library | Left/Right changes Built-in / Favorites / My recipes; Up/Down moves; Center opens details |
| Editor | Up/Down selects a field; Left/Right changes its value; Center enters the name keyboard or executes an action |
| Name keyboard | Directions move between keys/candidates; Center enters; Done accepts; MENU cancels text entry |
| Menus | MENU/Left goes back where applicable; half-shutter returns to shooting after protecting an unsaved draft |
| Key viewer | All keys are observed locally; MENU exits |

A shutter press used to leave a Film Lab menu is consumed. Press again from
shooting to capture. Capture/playback states keep their native key paths.

## Recipes and processing

The library exposes the fifteen inherited Film Studio/Ricoh profiles. Built-ins
can be used, favorited and duplicated. Only user recipes can be edited, renamed
or deleted. New film-cartridge icons are drawn by Film Lab.

Highlighting previews a recipe after a short debounce; a queued highlight cannot
apply after leaving the menu. Cancel restores the previous look and white
balance. **Save** stores the edited recipe and restores the shooting look;
**Save & use** also applies and selects it. A failed application does not claim
success. If persisting an accepted selection fails, the app attempts to restore
the previous look and reports a restoration failure separately.

Deleting an active user recipe preserves the current processing snapshot. Its
saved entry disappears; **Save current look** remains available in Settings.
Renaming changes its display name without changing processing. Recipe files use
bounded, checksummed storage with a previous-good backup. Closed-file validation
does not promise durability through sudden power loss.

| Recipe field | Meaning |
| --- | --- |
| Base film | One of the fifteen inherited full-strength profiles |
| Color, 0–100% | Blend the base 3×3 color matrix toward identity; independent of tone |
| Tone, 0–100% | Blend the base tone curve toward identity |
| Shadows / Highlights, −3…+3 | Film Lab's bounded curve adjustments; positive values lift that region |
| White balance | Auto or color temperature through Sony's controller |
| Temperature, 2500–10000 K | Editor range; actual advertised camera support is checked before application |
| A/B and G/M shifts, −7…+7 | Native WB shifts; positive A/B means amber and positive G/M means magenta |

Monochrome bases keep their color matrix at full strength. These are app-defined
controls, not calibrated equivalents of Fujifilm's numbered JPEG settings. The
engine uses Sony's matrix/curve processing, not a full arbitrary 3D LUT renderer.
Unsupported WB settings produce an error; they are not silently accepted.

## English, Chinese and naming

Film Lab's interface is bilingual. A complete static AOSP Chinese font is bundled
and applied to app-owned text, including **语言**, headings and recipe names. Its
glyph coverage is checked during the build. Sony's native icon fonts are left
alone. Developer tools includes a glyph screen for hardware checking.

The wheel-operated keyboard supports Latin letters, numbers and mixed names.
Choose **Pinyin / 拼音**, enter one syllable without a tone, choose a character,
then continue. Enter `v` for `ü`. The dictionary includes 20,894 characters covered
by the bundled font. It is a character picker, not predictive sentence input.
Names are limited to 32 Unicode characters.

Native camera submenus still use Sony's controllers and firmware resources. The
app requests English or Simplified Chinese for its own resource contexts;
available translations in camera-supplied strings must be checked on the body.

## Settings and reports

MENU provides recipes, native exposure/ISO/WB/image-quality controls, flash,
language, TXT report export, About and read-only developer pages. Flash choices
remain governed by the camera, attached accessory and shooting mode. The A7R II
has no built-in flash.

Reports now retain a separate last-observed camera-state snapshot across process
restarts. Historical values show their session/source and do not masquerade as a
fresh readback. Camera-set time comes from Sony TimeUtil when valid; unavailable
time is shown as unknown. Later reports include prior export outcomes. User
recipe names and photos remain excluded.

## Device checks for this update

The previous diagnostic reports identified an ILCE-7RM2 with firmware **4.00**,
Sony platform **2.14** and Android API **16**. They recorded accepted look calls
and a shutter callback. Those observations apply to the previous build, not this
new UI and recipe implementation.

- [ ] Upgrade and confirm **Film Lab**, its new library and integrated Settings.
- [ ] Check `语`, `言`, `语言` and other labels on the glyph screen and in menus.
- [ ] Switch languages, restart, and check that the preference is retained.
- [ ] Browse rapidly, cancel and half-press; confirm the previous look returns.
- [ ] Duplicate a built-in, name it in English/Chinese, adjust fields and Save.
      Confirm that Save alone has not changed the shooting look.
- [ ] Use the custom recipe, save a JPEG, and check actual image appearance.
- [ ] Rename/delete only user entries; test deleting an active recipe and saving
      the preserved current look as a new recipe.
- [ ] Verify Fn, Center, MENU, native DISP, LCD/EVF switching and playback return.
- [ ] Visit flash/exposure/quality menus and return; check that the film look and
      intentionally changed native settings survive.
- [ ] Export a report, restart, and export another; inspect the historical-state
      section and retrieve both files from `FILMLAB`.

Desktop tests cannot validate camera rendering, real menu transitions, physical
button timing or saved-image appearance. Continue with the detailed
[A7R II checklist](a7rii-test-checklist.md) where relevant.
