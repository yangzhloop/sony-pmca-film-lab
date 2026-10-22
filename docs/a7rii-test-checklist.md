# A7R II diagnostic APK: first device checks

Target: Sony A7R II / ILCE-7RM2. No item below is a recorded pass from the cloud build environment. Record the actual model/firmware, artifact SHA-256/build ID, result and relevant report filename for each test. An unavailable capability or failed operation is useful evidence; do not mark it passed because the menu was visible or no exception appeared.

This first target has a bilingual diagnostic launcher and largely Chinese inherited Sony/Film Studio shooting menus. It retains fifteen fixed looks; it does not implement the browser v0.3 custom-recipe editor. See [build, provenance and installation](camera-build.md).

## Install, identity and language

- [ ] Match the APK digest to its `.build.json`; retain that sidecar and the prepared-input receipt on the computer.
- [ ] Install the local APK with PMCA GUI, or an already working explicit ADB device. Confirm the new **Film Lab Diagnostics** entry/package; upstream Film Studio remains a separate app.
- [ ] Open diagnostics. Record the bridge's actual model, firmware, platform/Android API and any unknown fields. The configured A7R II target label is not detected-device evidence.
- [ ] Switch English / 简体中文 and restart. Verify diagnostic preference persistence, missing glyphs, clipping and legibility. Record that the inherited shooting menus are still largely Chinese; this is not a complete bilingual shooting-UI test.
- [ ] Use physical directions, including Left/Right, Center and MENU without touching the display. Test a scrollable diagnostic page and returning from a child page. No directional action should export a report or invoke capture.
- [ ] In the key viewer only, observe key press/release/repeat where exposed. MENU must exit. Shutter, Fn, DISP and directions must not open shooting, fire a flash, capture or change the active recipe while being observed.

## Inherited Sony shooting and DISP

- [ ] Enter shooting from the diagnostic launcher. Verify live view on LCD, then EVF where available, without a second camera-open attempt from diagnostics.
- [ ] Test Sony menu navigation using physical Up/Down/Left/Right and Center. Confirm the preset browser and existing exposure, quality and WB entries remain reachable.
- [ ] In shooting, press the real DISP control repeatedly. Record the **actual enabled cycle and ordering**, including overlays, histogram/level if available, and clean display. Do not compare against the browser's four illustrative modes as a required native sequence.
- [ ] Repeat DISP checks on LCD and EVF, including eye-sensor transitions. Verify menu entry/exit and capture remain reachable when app information is hidden.
- [ ] Open and close a preset browser/menu, then repeat DISP. Menu directions should stay local to menus; inherited shooting DISP/native custom-key assignments should resume outside them.

Source evidence available in the decoded prepared reference:

- `assets/MenuData.xml`: `FlashMode` and `FlashCompensation` route to `com.sony.imaging.app.base.shooting.camera.FlashController`; the XML includes mode candidates, but the controller governs support and current availability.
- `smali/com/sony/imaging/app/base/shooting/camera/FlashController.smali`: supported values, scene availability and commit behavior.
- `smali/com/sony/imaging/app/base/shooting/trigger/S1OffEEStateKeyHandler.smali`: inherited `pushedDispFuncKey()` path.
- `smali/com/sony/imaging/app/base/shooting/camera/CameraSetting.smali`: display-mode notification wiring.

These files establish that the inherited mechanisms exist in the prepared APK. They do not establish correct A7R II hardware behavior.

## Focus, still capture and color evidence

- [ ] Confirm autofocus half-press and full shutter work normally before changing a look. Check capture completion, the saved file and native playback.
- [ ] Record exposure mode, shutter, aperture, ISO, WB, quality, lens and lighting for a fixed test scene. Keep these fixed across comparisons; include neutral gray, skin/colored objects and highlights where practical.
- [ ] Save a neutral comparison using Sony's ordinary shooting application, recording its Creative Style/WB and other relevant processing settings. Treat this as a comparison with recorded conditions, not proof that it is a calibrated identity reference for the app.
- [ ] In Film Lab's inherited shooting interface, test one color preset at **100% strength**, save a JPEG and inspect the actual file on a computer. Compare framing, dimensions, exposure/highlights and visible color against the recorded neutral comparison.
- [ ] Test ACROS or another monochrome preset at **100%**, save and inspect the JPEG. The inherited engine intentionally retains some color at lower strengths; do not report that as a failure of 100% monochrome.
- [ ] Browse a different preset temporarily, then cancel/half-press. Verify the confirmed look is restored before the next photograph. Confirming and taking the photo must not capture a stale highlighted preview.
- [ ] Return from playback and capture again. Test repeated preview/menu/capture cycles and note any reset, delay, error, black screen or unavailable control.
- [ ] If RAW+JPEG is supported, verify both files are saved and open correctly. Do not claim the RAW sensor data carries the film transform from a JPEG or embedded preview alone.
- [ ] Return to diagnostics and export the test evidence. Compare requested ID/strength, accepted-call status, cached parameter readback/source/age and any mismatch. Independently record the JPEG inspection result; those diagnostic fields are not image-output proof.

## External flash

The A7R II has no built-in flash. Use the actual flash or trigger intended for testing, and record its model and settings in the manually supplied reproduction notes.

- [ ] Open the inherited Sony Flash submenu. Check supported modes and disabled-state explanations with no accessory, then with the actual accessory attached and powered. Unknown detection must remain unknown rather than becoming a false “no flash.”
- [ ] Where available, test flash mode, readiness/charging indications and compensation. Confirm the controller's supported range/step and actual firing/exposure behavior.
- [ ] Test synchronization, wireless or red-eye options only where the native controller and accessory expose them. Do not infer a universal HSS switch or radio/group support from a status field.
- [ ] Change a flash setting intentionally, then browse/apply/cancel film looks and visit diagnostics. Flash mode/compensation must not silently change with recipe preview or restoration.
- [ ] Return from native flash settings and capture. Verify the committed film look survived or was restored before capture. Record unsupported exposure/shutter/drive combinations instead of forcing them.

## TXT report, card retrieval and privacy

- [ ] Create one report in diagnostics and save it. Record the actual discovered card-relative location, expected in `FILMLAB/FLR00001.TXT` form. A success message must correspond to the closed file passing immediate header/content readback.
- [ ] Copy the TXT to a computer through a card reader or a verified USB Mass Storage route. Confirm UTF-8 text, English field keys, artifact/build/provenance identity and the correct model/firmware or explicit unknown values. Do not assume MTP lists arbitrary TXT files.
- [ ] Check requested/accepted/readback/source/age labels, still mode, active/highlighted state and recent operation/error events. Cached values must not be labeled fresh camera readback; accepted parameters must not be labeled verified saved-JPEG output.
- [ ] Save another report. Confirm 8.3-compatible names and collision handling. Existing reports must not be overwritten; verify an existing `FLR00001.TXT` remains unchanged when a new export is made.
- [ ] With the camera powered off, remove the test card, restart and attempt report export. Check a specific failure and retained snapshot for retry. Reinsert only according to the camera's normal handling instructions; retry without reproducing the original problem.
- [ ] Where practical with a disposable test card, exercise a read-only/full/unwritable destination. Export failure must not be presented as success or silently redirected to an inaccessible private file. Restore ordinary card access and confirm retry retains the original report snapshot.
- [ ] Inspect the copied TXT for privacy: no custom recipe display names/notes, media, media filenames, serials, network identifiers, credentials or unrelated logs. Raw text-entry keystrokes and exception messages must not appear.
- [ ] Add reproduction steps, expected result and actual result on the computer; attach only the selected TXT manually to `github.com/yangzhloop/sony-pmca-film-lab` when reporting an issue.

Filesystem success does not establish power-loss durability or computer visibility. Do not simulate a native crash or remove power merely to check this build. Best-effort Java crash snapshots cannot cover every native failure or power loss.

## Restart, exit and restoration

- [ ] Exit inherited shooting normally, reopen diagnostics and inspect recent events. Confirm diagnostics does not open/reconfigure the camera just to obtain a report.
- [ ] Restart the app and check the inherited confirmed preset/strength preference. Distinguish what persists in application preferences from custom transform state installed in the camera for the active session.
- [ ] Exit the app and return to Sony's ordinary shooting application. Compare relevant look/WB settings and save a disposable neutral scene. Record whether custom matrix/gamma state was removed and which native settings were restored or reset.
- [ ] Do not assume upstream's cleanup restores every pre-app setting exactly. A restart, absence of an exception or a neutral-looking LCD alone is not sufficient restoration evidence.
- [ ] Keep the original installed app/card data and the build/report records. An uninstall, factory reset or firmware modification is not a normal recovery test for this checklist.

## Result record

For each failure, capture: test item, model/firmware, APK SHA-256/build ID, app state, preset/strength, exposure/quality, accessory if relevant, exact observed behavior and exported report filename. Keep accepted calls/readback and inspected JPEG evidence separate. Update hardware-support claims only from these recorded results.
