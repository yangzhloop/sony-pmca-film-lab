# Film Lab 0.2.1-alpha

Historical 0.2.1 record. FLR00005 subsequently exposed remaining launcher-label
and startup-routing defects; see [0.2.2 findings and fixes](native-app-0.2.2.md).

This patch addresses the A7R II feedback on 0.2.0: sluggish menu movement and
preset selection, inherited startup text/icon, a missing Exit app action and
duplicate report snapshots. The approved layout, bilingual interface and recipe
management flow remain the basis of the UI.

## Interaction and preview work

Menu movement updates the existing focused rows instead of reconstructing the
whole screen. Recipe values and the preview card update in place. Keyboard
navigation repaints the selected keys; changing keyboard content rebuilds only
the necessary grid content. Screen and tab changes still construct their views.

The preview queue keeps the newest pending selection. The shooting bridge reuses
an accepted look only while its Sony state generation remains valid. Moving
between presets with unchanged white balance avoids repeated WB submissions.
Use can commit an already accepted preview without resending the same color and
tone processing, while preserving saved WB options and rollback on storage
failure. Native transitions and unsolicited effect applications invalidate reuse.

Sony still owns the camera and its controller thread. The patch reduces work on
that path; it does not make the native camera calls asynchronous on an arbitrary
worker. Capture, playback, flash and shooting DISP retain their native paths.

Routine diagnostics now queue bounded events in memory and batch private-file
writes on a dedicated worker. Repeated checkpoint requests are coalesced. The
report distinguishes queued work, persistence failures and completed checkpoints.
Historical log loading is deferred from startup. The Chinese font remains
bundled and cached once per process; Pinyin still loads only when naming begins.

## Startup branding and Exit

The native Sony boot screen has its own app-name and icon resources. This patch
updates those separately from the launcher and live-view labels. All app-name
configurations use **Film Lab**, and the launcher/shooting icon variants use the
same film-cartridge design as Film Lab's menus. The old 1×1 placeholder variants
are replaced while retaining Sony's existing resource identifiers.

**Settings → Exit app / 退出应用** uses Sony's existing exit confirmation and
cleanup. Film Lab first cancels pending preview work and restores the committed
look. Unsaved recipe/name changes are protected; an active report export must
finish before exit. A bounded worker flush gives diagnostics time to persist,
then the main thread invokes Sony's normal Activity finish path. Cancelling
Sony's confirmation returns to shooting; Fn or MENU can reopen Film Lab.

**Return to shooting / 返回拍摄** remains a separate menu action.

## Reports and timing

After a successful report export, the next Save creates a fresh snapshot. A
failed-export retry retains exactly the original snapshot. Export remains a
manual TXT workflow with no automatic upload.

Startup milestones and timing aggregates live outside the rolling event tail,
so browsing many presets no longer removes them from that session's report.
Measurements cover native startup, menu construction/navigation, preview queue
delay, look/WB application, font loading and diagnostics flushing. They describe
code intervals and layout milestones, not photographed display latency or a
verified JPEG result.

Routine camera checkpoints do not call Sony's clock. Their wall time is marked
unknown rather than reusing a stale clock reading; monotonic checkpoint age is
retained. Explicit reports still sample valid Sony camera-set time.

The previous reported preview segments were 213–328 ms plus a 120 ms debounce
and unmeasured work. The user reported startup under five seconds. These are
0.2.0 baselines, not performance claims for 0.2.1. Actual improvement must be
measured on the camera.

## Install and device checks

Install `dist/FilmLab-0.2.1-alpha.apk` as an update through the existing PMCA
workflow. The package remains `com.flab.imaging.app.pictureeffectplus`, with
Android version code 3 and the retained local signing key. Do not uninstall to
perform this normal update. See [build/install instructions](camera-build.md).

- [ ] Confirm Film Lab text and cartridge icon during startup, in the app list
      and while shooting; check LCD and EVF where applicable.
- [ ] Compare cold and warm startup with the previous under-five-second estimate.
- [ ] Hold Up/Down and change tabs; confirm the highlight remains responsive and
      the final selected recipe is the one previewed.
- [ ] Preview, cancel and Use; verify look and WB restoration, including after
      visiting Sony settings or playback.
- [ ] Adjust editor fields repeatedly and enter an English/Chinese name; confirm
      values, disabled fields and keyboard focus remain correct.
- [ ] Exit from Settings, cancel Sony's confirmation, reopen menus, then exit
      again and accept. Relaunch and verify the saved recipe and language.
- [ ] Save a report, change presets, and save another. Confirm report IDs differ
      and startup/timing sections remain present after extensive browsing.
- [ ] Confirm native DISP, flash and capture behavior, and compare saved JPEGs.

Cloud host tests exercise control flow and avoided work. They cannot establish
camera rendering, physical key timing or the user's actual speed improvement.
