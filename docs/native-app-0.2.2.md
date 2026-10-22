# Film Lab 0.2.2-alpha

This focused patch addresses FLR00005 and the user's report that the camera app
selector shows `com.yuki.imaging.app.…`, startup enters the inherited Film Studio
selector, and MENU remains in a waiting screen until Playback (▶) is pressed.
It retains the approved Film Lab layout, English/Chinese menus, recipe flow and
the performance, Exit app and report changes from [0.2.1](native-app-0.2.1.md).

## Findings

FLR00005 identifies the delivered 0.2.1 build
`1ed4cca1f0a69b8c3c8ac48d62ed662a38dbffe04a0c2539752cc6e4aa6ddc2d`
on ILCE-7RM2 firmware 4.00, Android API 16. This is not an old APK installation.

The manifest patch replaced Sony's application and Activity label resource
references with literal strings. Both looked correct in ordinary decoded XML,
but literal labels leave Android's `labelRes` unset. A Sony selector expecting a
resource ID can consequently fall back to the implementation class name. The
camera launcher itself is unavailable here, so that fallback mechanism remains
a strong explanation rather than a directly traced firmware result.

The startup routing defect is confirmed in the pinned native code. Normal
launcher startup sets `PictureEffectEEState.mMenuStateAdd=true`, which selects
`Menu` with `ItemId=ApplicationTop`: the retained Film Studio preset selector.
Film Lab only enables its menus after Sony enters `S1OffEE`, so this older screen
appears before Film Lab can take over idle MENU/Fn handling. These are inherited
screens inside Film Lab's APK; the legacy class names do not establish that
another installed application was launched.

Separately, Film Lab's persisted `library-seen` flag allowed the library to open
automatically only once per installation. An existing user's subsequent launches
could therefore stay in native shooting even after reaching idle.

| FLR00005 milestone | Elapsed since native creation |
| --- | ---: |
| Overlay attached, initially hidden | 190 ms |
| Native boot callback finished | 691 ms |
| First native shooting idle | 33,965 ms |
| Active recipe restoration finished | 34,851 ms |
| First Film Lab menu layout | 47,768 ms |

These are elapsed milestones, **not measured blocking durations**. They include
time spent in native states and possibly user interaction. This report has no
native state/key timeline capable of explaining the exact waiting screen. The
user confirmed the workaround button was Playback (▶), not DISP.

Recorded Film Lab navigation has a 35 ms maximum, rendering a 420 ms maximum,
and recipe application one 250 ms sample. Font loading is 346 ms and may be
nested in rendering. The current session contains no preset previews, so it
cannot establish preset-selection performance. No recorded Java crash or
diagnostic write failure explains the reported wait.

## Changes

- Keep application label `0x7f090028` and launcher label `0x7f090029` as
  `TYPE_REFERENCE`, with **Film Lab** in all 33 configurations for each resource.
  Reject unexpected/literal labels and any unbranded locale during the build.
- Change only the normal launch branch's automatic legacy menu flag to false.
  Sony's camera/executor initialization, exposure-mode handling, playback and
  power/APO routes retain their native paths.
- After Sony resolves a normal launcher shooting route, open the Film Lab
  library once after genuine shooting idle and recipe restoration. The pending
  request belongs to that launch, ignoring the old persisted flag. Returning
  from capture, playback or a Sony setting does not reopen the startup library.
- Invalidate queued callbacks when the native state or host Activity changes.
  Reapply the saved recipe after native boot even when Sony reuses the same
  Activity, since boot can reset the inherited effect preference.
  MENU and Fn retain balanced native DOWN/UP ownership when opening the overlay.
- Record fixed boot destinations, idle entry/exit, overlay opening and numeric
  pre-overlay key events. Reports can now distinguish native boot routes from
  Film Lab entry. Recording continues through the bounded background writer;
  no typed recipe names or character text are added.

## Install and device checks

Install [FilmLab-0.2.2-alpha.apk](../dist/FilmLab-0.2.2-alpha.apk) as an update
using the existing [PMCA workflow](camera-build.md). Package and signing identity
are retained; version code is 4. Updating in place preserves recipe/language
data. Avoid uninstalling for this check.

1. Return to Sony's app selector after installation. Verify **Film Lab** and the
   cartridge icon in the app list; verify About shows **0.2.2-alpha**.
2. Launch normally, without pressing Playback. Confirm the Film Lab library
   opens after initialization and the inherited preset selector does not appear.
3. Choose a preset and enter shooting. Press MENU for Film Lab Settings, and
   Fn/Center for the library. Confirm no Playback workaround is needed.
4. Exit and launch again, confirming the library opens on the second launch too.
   Verify saved recipes, favorites and language are retained.
5. From shooting, check Playback and return, native DISP, capture, and Sony flash
   settings. Also check power/sleep resume keeps its expected native behavior.
6. Save a fresh issue report soon after these checks. If waiting persists, note
   the last screen/button and approximate wait alongside the TXT report.

The cloud checks validate source routing, manifest/resource structure and host
behavior. The launcher text, spinner disappearance, physical timing and saved
JPEG output still need this exact APK to be checked on the A7R II. See the
[validation record](native-app-0.2.2-validation.md).
