# Film Lab 0.2.2-alpha validation

The focused patch was compiled, packaged, signed and checked in the cloud
workspace. FLR00005 validates the identity of the earlier 0.2.1 installation;
this new artifact has not yet been tested on the A7R II. See the
[findings and device checklist](native-app-0.2.2.md).

## Artifact

| Property | Result |
| --- | --- |
| APK | `dist/FilmLab-0.2.2-alpha.apk` |
| Size | 4,785,116 bytes |
| SHA-256 | `1677166319a57e05145163340601e18c99b8f76a880bd686903123607427ea83` |
| Build ID | `61fa7f49ccd76c9656aa28b51e23364c1b49b8a2acfe9e0d40b37e3e70dedab6` |
| Package | `com.flab.imaging.app.pictureeffectplus` |
| Version | `0.2.2-alpha`, Android version code `4` |
| Compile target | API 10 / Java 6, single DEX035 with 17,736 method references |
| Signing | API 10 v1 verification; certificate matches the actual 0.2.1 APK |
| Certificate SHA-256 | `1105a7d129daf82bd7b5a6c6557ac04dacbb1006c4b7758fa1ad0b2333ab2200` |
| Application label | `TYPE_REFERENCE` → `0x7f090028`, Film Lab in all 33 configurations |
| Launcher label | `TYPE_REFERENCE` → `0x7f090029`, Film Lab in all 33 configurations |
| Preservation | 675 unrelated input ZIP entries preserved; original resource IDs and native class namespaces retained |

One clean build produced this artifact. The [receipt](../dist/FilmLab-0.2.2-alpha.build.json)
matches the APK hash and final source hashes. Independent final-APK DEX decoding
confirms the launcher branch clears the automatic legacy menu flag, calls
`startupRoute(int, String)` after native boot selection, and retains Activity
attachment and the 0.2.2 BuildInfo values. Independent manifest decoding and
direct binary/resource checks agree on the label references and resolved names.
The original native Activity is the sole launcher; DiagnosticActivity is private.

Compared with the 0.2.1 receipt, production changes are limited to
`ShootingBridge.java`, `ReportStore.java`, `build_diagnostic.py` and
`recipe_hooks.py`. Existing fonts, Pinyin data, recipe math, UI layout, icons and
upstream processing coefficients are retained.

## Executed checks

| Command | Result |
| --- | --- |
| `python3 tools/test_camera_host.py` | 422 assertions passed |
| `python3 tools/test_engine_host.py` | Nine scenarios, 162 assertions passed |
| `python3 tools/test_ui_host.py` | 84 assertions passed |
| `python3 -m unittest discover -s tools/tests -v` | 25 tests passed, none skipped |
| Complete signed APK build | Passed API 10 compilation, DEX assembly, branding/assets, independent manifest decoding and legacy signature verification |

The 668 host assertions include normal startup after both fresh installation and
an existing `library-seen=true` preference; repeat launches; APO/power/playback
and unknown boot routes; genuine idle gating; interrupted/stale callbacks; and
balanced MENU DOWN/UP handling. A same-Activity native reboot resets the simulated
native effect, then verifies restoration of a saved nondefault matrix, gamma and
WB before library entry. Playback boots defer camera writes until shooting idle
and do not automatically open the library.

The Smali suite follows the generated method's actual switch/goto/if paths for
24 combinations of boot factor, playback key and housing state. It compares
native setup calls and destinations with the unmodified input, allowing only the
launcher legacy-menu flag to change. Native exposure-state, capture, flash and
DISP implementations remain protected. This is a bounded host interpreter of
the relevant boot method, not execution on Sony hardware.

Manifest regressions reject literal or wrong-ID labels and even a single
unbranded locale. Report tests verify the fixed playback route survives privacy
filtering. Existing recipe/preview/rollback, report persistence, physical-input
mapping and production UI fixture suites remain passing. Independent review
found and resolved the same-Activity reboot restoration case before the build;
no blocking source-review findings remain.

## Limits

The source route into the inherited selector was reproduced by inspection and
control-flow tests. Sony's exact app-selector fallback and the reported waiting
screen have not been instrumented on firmware. FLR00005's 34–48 second milestones
are elapsed times, not proof of a blocked CPU interval or deadlock. New route and
pre-overlay key records make the next report more informative.

The [device checklist](native-app-0.2.2.md#install-and-device-checks) must confirm
the app-list name, direct Film Lab entry without a Playback workaround, MENU
responsiveness, repeated launch, native playback/power resume and saved recipe
restoration. Rendering, physical timing and JPEG correctness remain unverified
for this artifact; `cameraTested` stays false in its receipt.
