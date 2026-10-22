# Film Lab 0.2.3-alpha validation

Built and checked in the cloud workspace on 2026-10-22. The APK has not been
tested on the A7R II; [device checks](native-app-0.2.3.md#installation-and-device-checks)
remain necessary for actual rendering, transparency and focus behavior.

## Artifact

| Property | Result |
| --- | --- |
| APK | `dist/FilmLab-0.2.3-alpha.apk` |
| Size | 4,785,581 bytes |
| SHA-256 | `0f8ae8e23f0ac4371dada2539ca689c71bcb3c8f602e46ce253a5fc97498f7bb` |
| Build ID | `d595bcfe024bc2639efef6e1aaf31d62434fac2c172da1b40eac3792cd98589d` |
| Package | `com.flab.imaging.app.pictureeffectplus` |
| Version | `0.2.3-alpha`, Android version code `5` |
| Compile target | API 10 / Java 6; one DEX035, 17,738 method references |
| Signing | API 10 v1 verification; certificate matches the independently verified 0.2.2 APK |
| Certificate SHA-256 | `1105a7d129daf82bd7b5a6c6557ac04dacbb1006c4b7758fa1ad0b2333ab2200` |
| Labels | Application/launcher retain `TYPE_REFERENCE` labels, Film Lab in 33 configurations each |
| Preservation | 675 unrelated input ZIP entries preserved |

The [build receipt](../dist/FilmLab-0.2.3-alpha.build.json) matches the final APK
and current production-source hashes. A clean build completed with the retained
signing identity. Independent final-APK DEX decoding confirms Favorites-first
tab order, both focus-menu IDs and the guarded library-entry helper. Independent
manifest decoding confirms version, launcher identity and resource-backed labels.

Compared with the 0.2.2 receipt, the production input changes are limited to
`FilmLabUi.java` and `build_diagnostic.py`. Native camera/input hooks, processing
coefficients, recipe storage, fonts, Pinyin data and the shooting bridge are
unchanged. Test, documentation and browser-preview updates are separate.

## Executed checks

| Command | Result |
| --- | --- |
| `python3 tools/test_camera_host.py` | 422 assertions passed |
| `python3 tools/test_engine_host.py` | Nine scenarios, 166 assertions passed |
| `python3 tools/test_ui_host.py` | 153 assertions passed |
| `python3 -m unittest discover -s tools/tests -v` | 27 tests passed, none skipped |
| Signed APK build | Passed API 10 compilation, DEX assembly, resource/font checks, manifest decoding and legacy signature verification |
| `node --test design/ui-preview/tests/diagnostics.test.js` | Six tests passed |
| `python3 design/ui-preview/tests/verify_settings.py --url http://127.0.0.1:8765` | Twelve browser scenarios passed against the locally served prototype |
| Portable browser artifact | Rebuilt and checked with secondary resource requests blocked; no extra requests or JavaScript errors |

The **741 host assertions** include Favorites-first entry and category cycling,
empty/single-item lists, wraparound and scrolling, held arrows, latest-only
preview requests, retained row/page identity, single-line name ellipsis, and
Center opening the unchanged details/actions page. Hierarchy/weight checks
verify a one-tab-wide left column, transparent surrounding space and absence of
inline library details. English and Chinese focus entries route to the exact
native item IDs, and native unavailability leaves a visible error.

Review identified an empty-library restoration failure during normal reopening
or Settings entry. The shared entry helper now falls back to Settings with the
restore error. Regressions exercise both failure paths and a successful retry,
alongside failed tab switching and removal of the last favorite.

Native menu protection tests inspect the actual pinned Sony implementations:
main-page lists, special-screen focus/flash lists and circular submenu links
already wrap. Exact focus routes, native supported-option controllers and
focus-position editors remain intact. Numeric value boundaries and keyboard
grid behavior are preserved. These source checks do not execute Sony firmware.

The browser v0.4 screenshot of the compact English library was visually checked.
Its [validation record](../design/ui-preview/VALIDATION.md) and new bilingual
captures describe the simulated preview separately from the native APK.

## Remaining device validation

Check LCD/EVF text fit at the narrower width, real live-view transparency,
selection visibility when wrapping, available focus modes/areas for the attached
lens, return from native focus menus, and preview/Use/cancel restoration. Retest
capture, DISP, playback, Exit and fresh report export. Host fixtures record view
properties and API calls but do not reproduce Android measurement, the camera
surface compositor, physical key timing or JPEG output. `cameraTested` remains
false in the receipt.
