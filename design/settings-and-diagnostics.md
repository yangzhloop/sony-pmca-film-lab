# Settings, flash and diagnostics

Status: camera implementation specification, with the menu flows implemented in browser preview v0.3. The browser has simulated flash values, a browser-only TXT download, About and read-only developer tools. Native Sony flash integration, durable camera logging, crash recovery, card export and A7R II behavior remain unimplemented/unvalidated. The existing layout and recipe flow were approved by the user.

## Menu structure

Keep the compact Sony-style list and physical-button operation. Replace the language-only settings placeholder with this index, while preserving access to the Sony shooting controls already available in the base application.

| Menu entry | Purpose | Ownership |
| --- | --- | --- |
| Flash settings / 闪光灯设置 | Open supported Sony flash controls | Inherited Sony camera controller |
| Language / 语言 | English / 简体中文 | App preference |
| Save issue report / 保存问题报告 | Export one diagnostic TXT to the card | App diagnostic service |
| About / 关于 | Version, project, credits and licenses | App metadata |
| Developer tools / 开发者工具 | Inspect device, processing state and events | App diagnostics |

Keep **Save current look / 保存当前效果** as an additional row when the active recipe has been deleted. This recovery entry must remain reachable through MENU even with a clean shooting display. Fn remains the recipe-library shortcut in the prototype; final native bindings still require validation.

Use Up/Down for rows, Center to open/confirm and Left/MENU to return from subpages. Left/Right adjusts values only on pages where that meaning is clear, such as flash compensation through Sony's existing screen. Directions must not immediately export a report or execute a destructive action. Show current language and focused language separately; Center applies the choice. Localize all new labels, availability reasons, progress and errors in English and Chinese.

These five entries define the requested app-menu additions, not a replacement for every native Sony shooting setting. Do not remove existing usable exposure, quality or WB controls simply because the prototype has not illustrated them.

## Flash: inherit Sony behavior

Preferred implementation: open the original Sony flash submenu and retain its supported/available values, grayed-out states, cautions and commit path. Inspect the exact base APK first; the public Film Studio patch source does not contain the complete flash menu or establish that it is currently exposed.

Candidate controls, governed by actual controller availability:

- **Flash mode / 闪光模式:** available native synchronization modes. Fill/front sync, slow sync, rear sync and wireless are examples of API vocabulary, not a promised fixed option list.
- **Flash compensation / 闪光补偿:** use the reported value, minimum, maximum and step with the controller's EV conversion.
- **Wireless / 无线闪光** and **Red-eye reduction / 红眼减弱:** retain them only if the inherited menu, attached equipment and shooting mode support them.
- Native accessory/charging state where available, with the original reason for an unavailable setting.

The A7R II has no built-in flash. Test with the user's actual external flash or trigger. Do not invent popup controls, a universal HSS toggle or a complete radio/group configuration screen from a status-field declaration. Detection can be unknown for some accessories; avoid reporting “no flash” solely because a particular status getter is unavailable.

Flash belongs to **shooting settings**, independently of film recipes. Choosing, previewing, duplicating or restoring a recipe must not silently change flash mode, compensation or wireless behavior. An intentional change made through Sony's flash menu should follow that menu's normal persistence behavior. Restore only app-owned look settings rather than blindly replaying an old full camera-parameter snapshot over newer user changes.

Reevaluate available options after accessory attachment/removal, relevant exposure/shutter/drive changes, playback and resume. On return from a native submenu, ensure the committed film look remains applied. If the native flow reset it, restore the active snapshot before capture; report a restoration failure rather than displaying a false success. Do not reset the original pre-app baseline on each menu visit.

Validation: supported values and readback; flash readiness/error; actual firing and synchronization; compensation; unsupported shooting modes; menu return; recipe independence; capture and exit. A setter returning successfully is not proof of a flash firing or a correctly rendered saved JPEG.

## Save issue report: an ordinary support feature

Users should not need Developer tools, ADB or an account to save a report. The action creates a local UTF-8 TXT for the user to copy to a computer and attach manually to a repository issue. It never posts to GitHub or uploads media automatically.

Proposed flow:

1. **MENU → Save issue report.** Show a short summary: app version, camera state and recent app events will be included. Optional category selection can distinguish color, capture, flash, menu or crash problems; long reproduction notes are better added on a computer.
2. **Center → Save.** Snapshot the current app state and recent events. Use a camera readback only when its thread/state permits; a failed or busy camera must not prevent export of cached evidence. Label cached data with its age and source.
3. Check the card/export destination, write and close a bounded temporary file, finalize a new report filename, and verify the file/header before showing success.
4. Show the actual card-relative filename, such as **`FILMLAB/FLR00001.TXT`**. Explain that the user can copy it with a card reader, or a verified USB Mass Storage path, then attach it to an issue at `github.com/yangzhloop/sony-pmca-film-lab`. The literal mount path is determined by hardware validation.
5. If no card, full, read-only or write/finalization failure: show a specific failure and retain the diagnostic snapshot for retry. A private internal copy is not a successful card export. Do not require the user to reproduce the problem again just because export failed.

Use 8.3-compatible names and a dedicated folder outside DCIM and Sony databases. The sequence is collision-checked; do not overwrite previous user-exported reports silently. A temporary name such as `FLR00001.TMP` also fits. Keep previous exports if finalization fails. Do not promise power-loss-safe atomicity or require `fsync` based on desktop assumptions; Recipe Lab reports an older Sony filesystem where it is unsupported. Verify directory creation, file contents, card-reader visibility and USB copying on the A7R II. MTP visibility of arbitrary TXT files is not assumed.

### Report contents

Use versioned, stable English field keys regardless of the UI language. The interface remains bilingual; UTF-8 preserves any user-provided text included intentionally. Numeric fields use unambiguous units. Include camera wall time if usable plus monotonic elapsed timestamps for event ordering.

| Section | Required content |
| --- | --- |
| Identity | Report schema, app version, full build/commit identity, build variant, base-app provenance/hash, session and report IDs |
| Device | Actual camera model and firmware, camera API and Android runtime; explicit unavailable/unknown status |
| UI/session | Current screen, language, still/movie state, LCD/EVF if readable, native DISP readback or explicitly labeled cached state |
| Recipe state | Highlighted/requested ID and revision, saved revision, successfully active snapshot ID and revision, unsaved-draft flag |
| Transform request | Base ID/revision, generator version, semantic color/tone/WB controls, requested 3×3 matrix, curve size and checksum |
| Observed state | Relevant readback values, mismatches, source and observation time; unavailable fields remain unavailable |
| Capabilities | Method presence, support-query results and usable ranges; distinguish missing, unsupported, failed and untested |
| Flash/capture | Flash mode/compensation, accessory/readiness if available, relevant exposure/drive/quality settings, capture callbacks and errors |
| Operations | Last operation ID/stage, request/commit/restore outcome, durations/timeouts and sanitized exception details |
| Storage | Card accessibility, free space if available, report destination and last app save/export failure |
| Events/crash | Bounded recent lifecycle/apply/capture/save/restore events and last recoverable Java crash snapshot, with session identity |
| Reproduction | Blank fields for the user to add steps, expected result and actual result on a computer |

Record **requested values**, **accepted calls**, **readback observations** and **saved-image verification** separately. Never label a cached requested matrix as camera readback, or an accepted setter as verified JPEG output. Readback itself does not prove the final saved-image appearance. Unknown information is more useful than a false success.

Default reports omit photos, media filenames, serial numbers, network identifiers, user recipe display names/notes, unrelated app logs and secrets. Stable recipe IDs and actual parameter values provide the relevant reproduction context. Structured app-owned events and sanitized exception messages minimize accidental inclusion. A checksum identifies a curve; the report need not package original LUT files, APKs or the entire user's library.

An example schema is provided in [issue-report-example.txt](issue-report-example.txt). It contains placeholders, not camera measurements.

### Diagnostic storage and crash behavior

Proposed starting limits: two rotating app-private log files of 64 KiB each; a report cap of 256 KiB, reserving room for state and an error stack. Mark truncation and dropped-event counts explicitly. Tune these limits after measuring memory/write cost on the target.

Capture significant state transitions and failures continuously, with repeated-message suppression. Avoid per-frame logs and text-entry keystrokes. Use cached state and bounded writes for error handling. A small best-effort Java crash snapshot should delegate to the previous exception handler; it must not reopen/reconfigure the camera or try a long card export while crashing. Preserve the snapshot across restart until a successful export or explicit clearing.

Power removal, native crashes and some camera resets cannot be guaranteed to produce a Java report. Before/after operation markers and small private checkpoints can help locate the last known action. Export from the next working session should distinguish prior-session evidence from current-session state.

This logging does not depend on unrestricted `logcat`, root access or modern Android runtime-permission dialogs. The legacy target uses manifest permissions; validate the actual storage/API path rather than introducing an unsupported modern file picker.

## About

Show **Film Lab**, app version, short build ID, target camera, UI language and project/issue URL as readable text. Separate “target model” from “tested on this model/firmware.” A model label alone must not imply completed device validation.

Provide **Licenses & sources / 许可与来源** for Film Studio and other reused components/assets. Keep actual applicable notices with the artifact and identify the source revisions used; the repository's MIT license must not replace licenses on imported portions. Use complete build/provenance identity in reports and a compact version in About. Do not place a photo-gallery browser, login, automatic updater or GitHub posting workflow here in the first version.

## Developer tools

Keep a normal, clearly named advanced entry at the bottom of the menu; no touchscreen gesture or hidden multi-tap sequence. Ordinary report export remains outside this submenu.

| Tool | First-version scope |
| --- | --- |
| Device & capabilities / 设备与能力 | Read-only device/API information, support-query results and unsupported/missing/error distinctions |
| Processing status / 处理状态 | Requested/active/saved revisions, matrix/curve fingerprints, pending application, restoration and latest errors |
| Key viewer / 按键查看 | Observe down/up/repeat events and interpreted actions only on this screen; MENU always exits; no automatic shutter/flash action |
| Recent app events / 最近应用事件 | A bounded, scrollable tail with operation IDs; pause/resume view without stopping the basic diagnostic recorder |
| Detailed logging / 详细日志 | Optional second step: temporarily expand app logging, visibly show status, stop on exit or a short timeout; never enable an OS debug service |

Prioritize the first four read-only views. Detailed logging can follow when a concrete investigation needs it. A pure recipe-data validator can be added later without applying a transform. Defer camera test transforms, synthetic capture/flash tests and reset actions until a specific tested recovery flow exists. No arbitrary shell, firmware writes, ADB/Telnet switch or factory-reset button is needed for this product's developer diagnostics.

The key viewer is an isolated observation screen, not a global input hook that consumes native Sony controls. Opening any diagnostics page must not silently commit a highlighted preset, save/discard a draft, or replace the original camera baseline. Keep menus and report export available after a recoverable apply error.

## Implementation order

1. Expand the menu design and bilingual physical navigation; add About/build identity.
2. Add structured app events and requested/active/readback distinctions while implementing the recipe engine, before broad camera testing.
3. Prove TXT export to the A7R II card and retrieval on a computer. This should be ready for the first shared hardware builds.
4. Inspect and route to the original flash controller/menu; validate with actual flash equipment and preserve recipe independence.
5. Add the read-only developer screens using the same diagnostics model/report snapshot. Avoid a second inconsistent source of “current state.”

For the web preview, label any flash controls/capability values as simulated and any report download as a browser demonstration. It must not claim to have detected a flash, written to a camera card or read a camera firmware version.

## Source evidence

- Film Studio's [menu patch](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/blob/ea766496cb40ec6a21beeb88da762679cf1786c8/tools/build_apk.py#L181-L224) changes preset/menu entries but does not publish the complete Sony flash subsystem. Its [existing reporting guidance](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/blob/ea766496cb40ec6a21beeb88da762679cf1786c8/docs/INSTALL.en.md#L173) identifies model, firmware, app version, mode/format and look as useful evidence.
- OpenMemories declares [supported flash types](https://github.com/ma1co/OpenMemories-Framework/blob/f8df3504d8684433a331ee869e64f67cc8be9730/stubs/src/main/jasmin/com/sony/scalar/hardware/CameraEx%24ParametersModifier.j#L1117-L1118), [compensation/step](https://github.com/ma1co/OpenMemories-Framework/blob/f8df3504d8684433a331ee869e64f67cc8be9730/stubs/src/main/jasmin/com/sony/scalar/hardware/CameraEx%24ParametersModifier.j#L650-L656), and [external flash status](https://github.com/ma1co/OpenMemories-Framework/blob/f8df3504d8684433a331ee869e64f67cc8be9730/stubs/src/main/jasmin/com/sony/scalar/hardware/CameraEx%24ExternalFlashInfo.j#L5-L11). These are interfaces, not target-hardware validation.
- Recipe Lab's [CardLog report builder](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/src/com/voxivoid/recipelab/CardLog.java#L183-L206) and [menu export](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/src/com/voxivoid/recipelab/MainActivity.java#L216-L235) are direct evidence of the TXT report pattern. This is a design reference, not an instruction to copy its separately licensed source into Film Studio.
- Recipe Lab [storage findings](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/docs/DEVELOPMENT.md#L263-L270) report 8.3 constraints; [remaining assumptions](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/docs/DEVELOPMENT.md#L344-L347) include cross-body card mapping/visibility. [PMCADemo Logger](https://github.com/ma1co/PMCADemo/blob/12f34e49d48a1d9496ea3c2792911867ea4c985b/app/src/main/java/com/github/ma1co/pmcademo/app/Logger.java#L7-L19) independently writes to external storage.
- Recipe Lab's [parameter readback](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/src/com/voxivoid/recipelab/MainActivity.java#L1091-L1107), [crash handler](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/src/com/voxivoid/recipelab/MainActivity.java#L171-L176), and [pre-capture marker](https://github.com/voxivoid/recipe-lab-sony-pmca/blob/fc746fb286f98cab96ba2a2cb04187747fc763ae/src/com/voxivoid/recipelab/MainActivity.java#L823-L830) demonstrate useful diagnostic distinctions. Camera identity should use Sony properties rather than assuming Android Build.MODEL is the consumer model.
