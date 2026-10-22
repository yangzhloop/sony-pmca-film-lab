# Film Lab 0.2.0-alpha validation

Historical build record. See [0.2.3 validation](native-app-0.2.3-validation.md)
for the current compact-browser and menu-navigation patch.

The native APK was built and checked in this workspace. It has not yet been run
on the user's A7R II. The prior diagnostic reports are evidence for the earlier
build only; they do not validate this version's UI or saved JPEG output.

## Artifact

| Property | Result |
| --- | --- |
| APK | `dist/FilmLab-0.2.0-alpha.apk` |
| Size | 4,790,490 bytes |
| SHA-256 | `40a789a1a4cd6adfbc07c0fd2081d33de7173330ee01bca5a46d2dcae22cbcfd` |
| Build ID | `50f3e76df098c6584ee4131a12f878f660b38bfa419686e1a843da6058da8fe0` |
| Package | `com.flab.imaging.app.pictureeffectplus` |
| Version | `0.2.0-alpha`, Android version code `2` |
| Sole launcher | `com.yuki.imaging.app.pictureeffectplus.PictureEffectPlus` with Film Lab's overlay |
| Compile target | Android API 10, Java 6, single DEX035; 17,655 method references |
| Signature | v1 signature verified for API 10; matches the previous diagnostic APK |
| Signing certificate SHA-256 | `1105a7d129daf82bd7b5a6c6557ac04dacbb1006c4b7758fa1ad0b2333ab2200` |

Two builds in separate empty work directories produced byte-identical signed
APKs using the same verified inputs, source and retained local signing key.
This does not imply that a new signing identity produces the same digest.
The [machine-readable receipt](../dist/FilmLab-0.2.0-alpha.build.json) records
source/input/tool hashes, both hook stages, font provenance, independent manifest
decoding and signing results. The diagnostic Activity is private and no longer
the launcher. Original Sony/yuki class namespaces remain.

## Executed checks

| Check | Result |
| --- | --- |
| `sh tools/setup_cloud.sh` | Passed with the prepared cache; verified tool/reference/font inputs and regenerated the Pinyin dictionary |
| `python3 tools/test_camera_host.py` | 392 assertions passed |
| `python3 tools/test_engine_host.py` | Four scenarios, 65 assertions passed |
| `python3 -m unittest discover -s tools/tests -v` | 17 tests passed; none skipped |
| Complete APK build and repeat | Passed; matching signed bytes |
| Manifest and legacy signature | Independently decoded/verified; version upgrade and signer match confirmed |
| Bundled font audit | All 21,012 required UI/dictionary code points present; 34,491 mapped characters |
| Pinyin dictionary | 419 syllables, 20,894 font-covered Hanzi; pinned source and MIT notice included |
| Archive preservation | 682 original entries preserved, seven assets added; original resource entries retained |
| Reference processing profiles | 120 compiled arrays match the verified published APK; fifteen profile/icon links checked |

The core suite covers report privacy/export/persistence, checksummed recipe
storage and recovery, protected built-ins, custom-name handling, tone/matrix
bounds, camera-clock conversion and both reflected/fallback key mappings.
The shooting suite uses fake Android/Sony interfaces around the production bridge
to exercise preview restoration, WB custom-slot preservation, failed selection
rollback, renamed/deleted active metadata and key DOWN/UP ownership. It cannot
prove those interfaces behave identically on a camera.

The five native-hook tests within the Python suite operate on the pinned Smali.
They check idle shortcuts and dispatch fallback, actual matrix/curve application,
gamma-failure handling, unchanged capture/flash/DISP paths and rejection of
changed/duplicate patch targets. The build compiler also rejects references to
Android APIs newer than the selected boot classes.

## Device validation still required

Follow the [0.2 device checklist](native-app-0.2.md#device-checks-for-this-update).
Confirm the actual layout, physical key timing, Chinese glyph rendering, native
DISP/LCD/EVF behavior, flash and other Sony submenu transitions, storage paths,
and saved JPEG appearance. Native submenus return to shooting; MENU reopens Film
Lab Settings. Firmware-provided translations depend on the camera's resources.

If an active recipe cannot be restored in the current shooting mode, the bridge
records `restore_failed` and opens the library. Library preview reports its own
result; there is no separate persistent startup-error banner in this alpha.
Reports include active recipe ID/revision/base and observed matrix/curve facts;
not every custom numeric editor field is currently populated in the report.

No hardware test, app launch, JPEG comparison or storage-card mapping was
performed in the cloud. `cameraTested` remains `false` in the build receipt.
