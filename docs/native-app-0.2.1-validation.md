# Film Lab 0.2.1-alpha validation

Historical 0.2.1 record. FLR00005 subsequently exposed remaining launcher-label
and startup-routing defects; see [0.2.2 findings and fixes](native-app-0.2.2.md).

This patch was built and checked in the cloud workspace on 2026-10-18 (Asia/Shanghai).
The user-tested 0.2.0 build and its reports are the baseline described in
[device-feedback-0.2.md](device-feedback-0.2.md). This new APK has not yet been
tested on the camera; no device-speed improvement is claimed from host tests.

## Artifact

| Property | Result |
| --- | --- |
| APK | `dist/FilmLab-0.2.1-alpha.apk` |
| Size | 4,784,993 bytes |
| SHA-256 | `bbd1dacb7978da90654c2297ca77ddae053891dd38a7652734f0c75d2babab5b` |
| Build ID | `1ed4cca1f0a69b8c3c8ac48d62ed662a38dbffe04a0c2539752cc6e4aa6ddc2d` |
| Package | `com.flab.imaging.app.pictureeffectplus` |
| Version | `0.2.1-alpha`, Android version code `3` |
| Compile target | API 10 / Java 6, single DEX035 with 17,735 method references |
| Signing | API 10 v1 verification; retained Film Lab signing identity |
| Certificate SHA-256 | `1105a7d129daf82bd7b5a6c6557ac04dacbb1006c4b7758fa1ad0b2333ab2200` |
| Startup names | 66 values across two resource IDs / 33 configurations use Film Lab |
| Icons | Seven app/launcher/EVF variants replaced, including the three 1×1 placeholders |
| Preservation | 675 unrelated ZIP entries preserved; resource IDs/configurations and unrelated values retained |

See the [build receipt](../dist/FilmLab-0.2.1-alpha.build.json) for input, source,
tool, resource and signature details. The original native shooting Activity is
the sole launcher. The diagnostic Activity remains private. APK resources are
patched without an aapt rebuild; branding tests independently decode them.

Two builds in separate empty work directories produced byte-identical signed
APKs with the retained signing key. The previous 0.2.0 APK's signature was also
verified directly and its certificate matches. All source hashes in the new
receipt match the final workspace sources.

## Executed checks

| Command | Result |
| --- | --- |
| `python3 tools/test_camera_host.py` | 421 assertions passed |
| `python3 tools/test_engine_host.py` | Six scenarios, 91 assertions passed |
| `python3 tools/test_ui_host.py` | 84 assertions passed |
| `python3 -m unittest discover -s tools/tests -v` | 21 tests passed, none skipped |
| Complete signed APK build | Passed API 10 compilation, DEX assembly, asset/resource checks, independent manifest decoding and legacy signature verification |

The 596 host assertions check behavior rather than device speed. Engine tests
count native submissions: accepted duplicate/initial previews avoid new look/WB
writes, changed built-ins skip unchanged WB, and Use promotes an accepted
preview while preserving backup options. They also exercise external WB changes,
state invalidation, custom-slot restoration and failed-save rollback.

The UI harness drives production key dispatch with queued Android fixtures. It
checks that preset arrows and preview completion create no new page/row views,
only the latest queued preview applies, editor and keyboard state remain valid,
report retries preserve snapshots, and successful subsequent saves are fresh.
Exit tests cover busy exports, unsaved edits, restore failure, bounded flush,
detached callbacks and reopening after a simulated canceled confirmation.

Diagnostics tests block private writes while submitting 2,000 events and 80
checkpoints. Event recording and report creation remain available; queues stay
bounded and checkpoints coalesce. Tests cover timeout/failure recovery, event
ordering, persisted-state provenance, immutable reports and retention of startup
and timing data outside the event tail.

The Python suite covers the real pinned native hooks, API compatibility,
tool/input integrity, font coverage and all targeted branding resources.
The full static CJK font and Pinyin dictionary remain included.

## Remaining device checks

Use the [0.2.1 checklist](native-app-0.2.1.md#install-and-device-checks). In
particular, check boot icon/text on LCD/EVF, rapid wheel navigation, preview/Use
and WB after native menus, Exit cancellation/acceptance, and two fresh reports
with retained startup/timing sections. Compare cold/warm startup against the
user's under-five-second baseline and judge actual preview responsiveness.

The reports' stage timings measure code intervals, not display photons or JPEG
correctness. Physical button timing, firmware translations, Sony confirmation
behavior and saved-image appearance remain unverified for this artifact.
