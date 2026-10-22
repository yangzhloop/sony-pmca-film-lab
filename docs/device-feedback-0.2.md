# A7R II feedback on Film Lab 0.2.0-alpha

This is a read-only diagnosis of the installed build and the uploaded
`FLR00003.TXT` / `FLR00004.TXT`. No application changes or replacement APK are
part of this analysis. The user reports slow interaction, particularly preset
selection, old startup branding and an unusable startup icon, and requests an
Exit app menu action. The user estimates startup to a responsive menu at
**under five seconds**.

## Report evidence

The files are byte-identical: 55,423 bytes each, SHA-256
`761259f17c3b7a6b5e2daa6d60eab752591e493d5f447259818fc7d575e0e2e9`.
Both contain report `S60d17adaed-R1`, generated at camera-set UTC
`2026-10-17T19:07:40Z`. Treat them as one snapshot.

The reported version is 0.2.0-alpha with build ID
`50f3e76df098c6584ee4131a12f878f660b38bfa419686e1a843da6058da8fe0`,
matching the delivered APK. Hardware is ILCE-7RM2, firmware 4.00, Sony platform
2.14, Android API 16. The bundled font loaded; the snapshot language is English.
Font loading alone cannot establish correct Chinese rendering in every screen.

The active recipe is Fuji Eterna, revision 1. The last request was accepted and
its matrix matches the observed native parameter matrix. Gamma readback and
saved-image verification remain unknown/unverified. There is no current-session
capture evidence. The report contains no recoverable Java crash or private-write
failure, and no failed operations in the retained current event window. It does
not collect all native errors or ANRs, so this does not exclude stalls.

The reported RGB/gamma support flags are `unsupported` despite accepted calls
and matching matrix readback. Preserve both observations; the flags alone do
not establish that the film effect failed, and accepted calls do not prove JPEG
appearance.

## What the timing actually measures

| Retained current-session interval | Count | Range | Median |
| --- | --- | --- | --- |
| Look request to acceptance | 66 | 79–154 ms | 93.5 ms |
| Look request to preview event | 63 | 213–328 ms | 241 ms |
| Look request to explicit apply event | 2 | 301 / 329 ms | — |
| Acceptance to preview/apply event | 65 | 132–217 ms | 145 ms |

`operationDurationMs=86` is only the last request-to-acceptance interval.
`DiagnosticsBridge.applyResult()` records it before synchronous checkpoint I/O.
White-balance changes and UI rendering occur later. Preview scheduling adds a
120 ms debounce before this measured interval. A settled preview therefore
already accumulates roughly 333–448 ms from debounce plus the logged preview
segment, before other validation, queueing and rendering costs. This is not a
measured key-to-frame latency or a guaranteed bound.

The current event tail starts at sequence 36, elapsed 22,288 ms. The first 35
events were dropped from the 256-event buffer. Startup time cannot be recovered
from this report. Earlier launch events in the previous-session section belong
to older diagnostic sessions. The user's under-five-second estimate is the
available startup baseline.

## Confirmed blocking paths and proposed changes

1. **Rebuilding the screen on navigation.** `FilmLabUi.key()` calls `render()`
   after selection movement; `render()` removes all views and constructs the
   complete layout again, including all preset rows. Preview completion triggers
   another full render. The naming keyboard follows the same pattern. Retain
   views, change only the selected rows/value/preview status, and reuse list
   items. Keep the approved layout and recipe flow.
2. **Applying previews on the UI Handler.** The delayed callback synchronously
   runs the look and WB calls, then redraws. Keep only the newest queued preview,
   skip identical processing, and avoid reapplying an already accepted preview
   on Use when the native state generation is still valid. Preserve Sony's
   controller/thread requirements; do not move native calls blindly to an
   arbitrary background thread.
3. **Redundant WB submissions.** Every preview calls native WB `setValue`, then
   submits WB options separately. This occurs even when neighboring built-ins
   share auto WB and zero shifts. Compare actual semantic WB state and submit
   only changed values, preserving custom WB slots, backup preferences and
   rollback behavior.
4. **Diagnostic disk I/O in the interaction path.** Every event opens, appends
   and closes a private log file. Every accepted preview checkpoints camera
   facts by writing, verifying and rotating files synchronously. Use a bounded
   in-memory event buffer and serialized background persistence; coalesce
   preview checkpoints and retain explicit commit/lifecycle/error snapshots.
   Preserve report privacy and ordering. Crash capture and final exit flushing
   need their own bounded handling.
5. **Startup repeats look work.** Inherited force-setting/EE states already
   apply a look; Film Lab then restores its recipe at first idle. On first use,
   opening the library also previews the first row. Instrument these stages
   before deduplicating them: native reapplication after a camera reset may be
   required. Previous-log parsing and the first font load also run during
   startup, with no measured individual durations yet.

The 3.8 MB CJK font is loaded once per process and cached. The Pinyin dictionary
loads on first name-keyboard use and is cached; it is not part of ordinary
startup or preset browsing. Removing Chinese support is not the proposed fix.

## Startup text and icon

The inherited `PictureEffectPlus` constructor calls
`setBootLogo(0x7f02005d, 0x7f090028)`. Sony `BaseApp.onSystemReady()` displays
those resources before normal boot continues. The string is
`STRID_FUNC_EFFECT_MASTER`, still **胶片工坊** in the final APK. The current hook
changes the later shooting label, and the manifest label is Film Lab; neither
changes this separate boot screen. This is a missed branding path.

Inspection of the final APK's decoded images found that
`drawable-notlong/p_16_dd_parts_pe_launchericon_s.png` is the inherited Sony
paintbrush icon (54×72), while the `drawable-long` alternative is a black 1×1
PNG. The long variants of the full launcher icon and app icon have the same
placeholder. These provide a concrete explanation for a blank icon if that
configuration is selected. The report does not identify the selected qualifier.

Replace startup and application name resources and every corresponding PNG
variant, including EVF variants, with consistent Film Lab branding. Keep resource
IDs intact. Use a real bitmap icon rather than a font symbol; use “Film Lab” for
boot text so it remains readable before the app's Chinese font is available.

## Exit app behavior

Add **Exit app / 退出应用** as the last Settings action, alongside the existing
Return to shooting action. Before exiting, cancel queued previews, handle
unsaved recipe/name edits, restore the committed look, and resolve any in-flight
report export. Close the overlay so it cannot intercept Sony's confirmation.

Invoke the Activity's normal `finish()` method. Dynamic dispatch reaches Sony
`BaseApp.finish()`, the same route used by native `ExitApplication`, and opens
the native exit confirmation. On acceptance Sony finishes its connection and
Activity lifecycle. Do not add an independent force-kill or camera close.

## Report UX and measurements for the next patch

Repeated Save report actions currently export the retained snapshot under new
filenames. This explains the duplicate uploads and should be clearer. After a
successful export, the next Save should capture a fresh snapshot; only a failed
export retry should reuse the unchanged snapshot.

Retain startup milestones separately from the rolling event tail. Add cheap
in-memory durations for navigation handling, preview queue wait, look apply,
WB apply, diagnostics persistence and layout completion. Measure cold/warm
startup and rapid wheel input on the A7R II after the patch; cloud host tests
cannot establish responsiveness. Verify preview cancellation, Use, native DISP,
flash, exit cancellation and re-entry alongside the timing checks.

Recommended order: remove menu/preview blocking work, fix startup assets and add
Exit, then verify an instrumented APK on the camera. Source locations include
`FilmLabUi.java` (render/preview/settings/report), `ShootingBridge.java`
(apply/WB/idle restore), `DiagnosticsBridge.java` and `ReportStore.java`
(checkpoint/logging), and `tools/recipe_hooks.py` (native lifecycle integration).
