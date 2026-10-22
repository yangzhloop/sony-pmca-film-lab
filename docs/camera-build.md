# Camera app build

This target prepares a **still-photo Film Studio reference**, then adds Film Lab's native recipe UI, processing controls and diagnostics within the inherited shooting activity. It targets the Sony A7R II / ILCE-7RM2. A successful desktop build, accepted API call or decoded APK does not establish camera compatibility; record physical results using the [0.2.3 update checklist](native-app-0.2.3.md) and [A7R II checklist](a7rii-test-checklist.md).

## Scope of 0.2.3-alpha

| Component | Scope |
| --- | --- |
| Film Lab menus | English / Simplified Chinese menus with vertical wraparound; Favorites-first, one-tab-wide library with clear live view; Center opens details |
| Shooting | Inherited Sony ownership/capture; fifteen full-strength profile bases with independent custom matrix/curve controls |
| Focus | Camera settings opens native Sony Focus mode and Focus area, retaining supported-option and lens checks |
| Flash | Retain the inherited Sony menu/controller and its availability checks; actual A7R II/accessory behavior is untested |
| DISP | Retain the inherited Sony shooting display path; enabled modes, ordering and LCD/EVF behavior require device testing |
| Recipes | Protected built-ins, favorites and private user recipes; create, duplicate, edit, rename, delete, Save and Save & use |
| Language | Bundled full static CJK font; app resource locale follows language preference; camera-firmware translations still require device checks |
| Reports | Bounded background persistence, labeled historical state, retained startup/stage timings, fresh TXT snapshots and no automatic upload |
| Startup and exit | Resource-backed Film Lab app-list labels and boot name/cartridge icons; normal launch opens the Film Lab library after native idle; Settings → Exit app uses Sony's normal confirmation |
| Capture | Stills-first reference; no video-support claim and no claim of processing RAW sensor data |

The browser prototype under `design/ui-preview/` remains an interaction review. Its simulated flash, histogram, level, capture and browser downloads are separate from this camera target.

The Android package remains **`com.flab.imaging.app.pictureeffectplus`**, distinct from upstream Film Studio's `com.yuki.imaging.app.pictureeffectplus`. The launcher is now the inherited `com.yuki.imaging.app.pictureeffectplus.PictureEffectPlus` activity, with Film Lab UI attached inside it. The label is **Film Lab**, version name `0.2.3-alpha`, version code `5` (0.2.2 used code `4`; 0.2.1 used code `3`; 0.2.0 used code `2`; the diagnostic build used code `1`). The same retained private key permits an update. Original Sony/yuki class namespaces remain inside the APK.

## Input provenance

The preparation helper pins Film Studio source to `ea766496cb40ec6a21beeb88da762679cf1786c8` and its Ricoh hook source to `7c565898562c73c5073c54dfc831c8c3df9c24cf`.

| Input | SHA-256 |
| --- | --- |
| Ricoh v1.1.4 `PictureEffectPlus_Ricoh.apk` | `80cb4a541f5f3dd49e8f53ffb1905048097fec17209fc9cb595a00681e65e8ea` |
| Published `FilmStudio-0.3.0-alpha-movie.apk` | `14d8558687b62f379d60119f2b363443e6d57acdff9a1c19ecf9e64dfb7dcaf1` |
| Pinned `RicohHook.smali` | `2db88c8e42311c587ca8304c2c8ed7d70592ee988ee154327ed5503b107723ae` |

Downloads use verified HTTPS and fixed hashes. The published APK is also checked against the pinned published `SHA256SUMS.txt`. The helper rejects mismatched existing inputs rather than silently replacing them or changing an expected digest. Tool downloads have their own pins in `tools/camera_toolchain.py` and `tools/prepare_film_studio.py`.

The official Fujifilm LUT host returned proxy CONNECT 403 during environment setup. The implemented preparation route therefore **extracts the ten full-strength Fujifilm-reference matrix/curve pairs from the verified published APK**, rather than performing a fresh LUT fit. It independently verifies the five Ricoh full-strength pairs against the pinned hook, regenerates the four strengths through unchanged upstream tooling, and invokes the upstream builder without `--movie`.

Validation checks all 120 generated arrays against the published APK, fifteen icon links, strength bounds/endpoints, archive/signature integrity, the eleven emitted preview-flow cases, a still-only supporting-record-mode value of `1`, and absence of the added movie-format/quality menu entries. Movie classes remaining in Sony's shared application framework do not constitute enabled video support.

The prepared reference in this workspace has SHA-256 `49f0b24de865ce7c5fa96dec79687ffb786e62a60a204016aad4c7f622bdf3e1`. It uses a local signing key. A fresh cache can produce a different APK digest; consume the verified preparation receipt, not that workspace-specific digest as a universal constant. This is **not a byte-identical rebuild of the upstream signed release**.

## Build commands

Run from the repository root. The validated environment has Python 3.12 with `numpy==2.3.5`, Java 21, `keytool`, Git and OpenSSL. Compilation uses pinned ECJ/dx and API 10 definitions; it does not require installing a system Android SDK. ADB is optional for device installation, not a build prerequisite.

For an empty cache, fetch and verify inputs/tools:

```sh
python3 tools/camera_toolchain.py
python3 tools/prepare_film_studio.py
python3 tools/prepare_camera_font.py
python3 tools/prepare_pinyin.py
```

In the configured cloud checkout, `sh tools/setup_cloud.sh` checks prerequisites,
prepares the pinned NumPy dependency when needed, and prepares the reference APK,
Chinese font and Pinyin dictionary.
See [cloud setup and start instructions](cloud-environment.md). Signing keys and
upstream cache inputs are kept outside the repository.

For the populated cloud cache:

```sh
python3 tools/camera_toolchain.py --offline
python3 tools/prepare_film_studio.py --offline
python3 tools/prepare_camera_font.py --offline
python3 tools/prepare_pinyin.py --offline
python3 tools/build_diagnostic.py --help
```

Create a fresh empty work directory for each native app build:

```sh
build_work_dir="$(mktemp -d /tmp/film-lab-build.XXXXXX)"
python3 tools/build_diagnostic.py --offline --work "$build_work_dir" --output dist/FilmLab-0.2.3-alpha.apk
```

The shared default cache is `/workspace/.cache/film-lab`. To relocate it, pass the same path to `camera_toolchain.py --cache`, `prepare_film_studio.py --cache-dir`, and `build_diagnostic.py --cache`. Omit `--offline` when missing pinned inputs must be downloaded. Do not use Python `-O` or `PYTHONOPTIMIZE`: upstream validators require assertions.

Preparation saves:

- `CACHE/upstream/film-studio-photo-receipt.json`: pinned inputs, extraction provenance, validation and locally generated photo APK digest.
- `CACHE/upstream/film-studio/output/FilmStudio-0.3.0-alpha-photo.apk`: still-only reference input.
- Decoded verification trees and generated profile data in the cache, outside repository source.

The native build validates that receipt before using its APK. It compiles Film Lab's Java additions for the legacy runtime, retains original class/resource identifiers, patches the manifest/resource package header and app-name values, replaces seven existing icon PNG variants without changing resource IDs or rebuilding with aapt, preserves and verifies selector-facing label resource references, suppresses only the automatic legacy launcher menu, applies shooting/UI and diagnostic hooks, and signs with an independent persistent local identity. Keep cache signing files private and retain them for updates; never commit or share private keys or password files.

After a successful build, inspect the actual artifact and its sidecar:

```sh
sha256sum dist/FilmLab-0.2.3-alpha.apk
python3 -m json.tool dist/FilmLab-0.2.3-alpha.build.json
```

The sidecar records the artifact digest, input receipt/provenance, source/build identity, tool hashes, manifest changes, DEX `035`, Java target `1.6`, compile API `10` and v1 signing verification for API 10. `cameraTested: false` must remain false until separately recorded hardware testing. A sidecar from another build is not evidence for the current APK.

Run the [host tests](../camera/tests/README.md) with:

```sh
python3 tools/test_camera_host.py
python3 tools/test_engine_host.py
python3 tools/test_ui_host.py
python3 -m unittest discover -s tools/tests -v
```

## Install on the camera

Use a computer connected to the user's camera. The cloud build environment does not itself establish a USB or ADB connection to it. Use a backed-up card and a disposable test scene for the first checks.

**USB / PMCA GUI:** in Sony-PMCA-RE's `pmca-gui`, select the local `dist/FilmLab-0.2.3-alpha.apk` through the local APK installation option, then install that selected file. Follow the installer's normal connection instructions. Firmware-update/service modes, region changes, factory resets and flashing firmware are not part of this workflow.

**Existing ADB connection:** if the camera already exposes a usable ADB transport, inspect `adb devices` and install to that explicit device. For an already configured Wi-Fi ADB connection:

```sh
adb connect CAMERA_IP:5555
adb -s CAMERA_IP:5555 install -r dist/FilmLab-0.2.3-alpha.apk
```

For a later update of this same Film Lab package signed with the same retained local key, use `install -r`. Do not uninstall or overwrite upstream Film Studio to make an incompatible signature update succeed. The package identities are intentionally separate. This guide does not require enabling ADB/Telnet, installing Tweak or modifying firmware when USB installation is already sufficient.

Open **Film Lab** from the camera's application list. The first-use library overlays native live view. Fn/Center opens recipes and MENU opens Film Lab Settings; there is no “Open Sony shooting” launcher. Use Settings → Save issue report for evidence without exiting and restarting the app. Check fonts and native menu transitions on the camera.

## What diagnostics establish

The bridge records requests and calls accepted by the inherited application, plus labeled cached native parameter readback. Diagnostics must not open another camera or issue extra camera RPCs to manufacture a fresher-looking report. Model/firmware are collected through the platform bridge where available; missing values remain unknown rather than substituting the configured target name.

Requested values, accepted calls, cached/readback observations and saved-JPEG verification remain separate. A matching matrix readback does not prove the intended JPEG appearance. Reports omit private recipe names, media and network identifiers. The key viewer observes controls only on its own screen and always allows MENU to exit; shooting events continue through Sony's inherited converter/controller.

TXT export targets a dedicated `FILMLAB` folder under Android's reported external-storage root, using names such as `FLR00001.TXT`. Its mapping to the removable card on the A7R II remains to be tested. Immediate readback is an export check, not proof of power-loss durability or computer visibility. Verify retrieval through a card reader or working USB Mass Storage path. MTP enumeration of arbitrary TXT files is not assumed. Attach the selected TXT manually to a repository issue; the app does not post it automatically.

## License and source scope

Personal/noncommercial use is the selected scope. Original Film Lab additions and the pypinyin-derived character dictionary are MIT; Film Studio's original additions retain **PolyForm Noncommercial 1.0.0**, and its adapted signer/upstream Ricoh material and AOSP DroidSansFallback font retain **Apache-2.0**. Sony base-application material and Fujifilm-derived material have separate rights. Upstream states that independent third-party adaptation/redistribution permission has not been established; the complete APK is not relicensed MIT by this repository.

Keep the applicable licenses, notices, source revisions and provenance with builds. Downloaded APKs, extracted coefficients, generated LUT exports and private signing material stay in the local cache. No publication or camera validation is implied by preparing these inputs.

References: [upstream input/build contract](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/blob/ea766496cb40ec6a21beeb88da762679cf1786c8/docs/INSTALL.en.md#L49-L102), [license scope](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/blob/ea766496cb40ec6a21beeb88da762679cf1786c8/LICENSING.md#L30-L49), and [upstream camera evidence](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/blob/ea766496cb40ec6a21beeb88da762679cf1786c8/docs/VALIDATION.md#L5-L26).
