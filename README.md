# Sony PMCA Film Lab

A film recipe app in development for the Sony A7R II, with English and Chinese menus, protected built-in presets and editable user recipes.

**Unreleased development snapshot.** The app is still being refined and tested. Generated APKs are local build outputs and are not committed; use the build instructions below to produce a test APK.

**0.2.3-alpha** opens Favorites first, narrows the preset list to one tab's width, removes the browsing screen's dark mask and inline details, adds Focus mode/Focus area under Camera settings, and wraps vertical menu navigation. It retains the startup fixes, English/Chinese menus, protected built-ins, editable recipes, English/Pinyin naming, **Exit app / 退出应用**, and issue reports. Sony retains camera ownership, capture, playback, flash and shooting DISP.

- [Build a local test APK — 0.2.3-alpha](docs/camera-build.md#build-commands)
- [UI changes and device checklist](docs/native-app-0.2.3.md)
- [Controls, features and update checklist](docs/native-app-0.2.md)
- [Artifact identity and verification receipt](dist/FilmLab-0.2.3-alpha.build.json)
- [Build, provenance, licensing and installation](docs/camera-build.md)
- [Native app validation results](docs/native-app-0.2.3-validation.md)

FLR00005 identified an A7R II with firmware 4.00 and exposed the inherited startup selector and launcher-label issues addressed in 0.2.2. The new 0.2.3 UI changes require their own device checks. See the [startup findings](docs/native-app-0.2.2.md) and [earlier feedback](docs/device-feedback-0.2.md). Reports are attached manually to an issue.

The UI layout and recipe flow are confirmed. Browser preview v0.4 includes the compact Favorites-first browser, wraparound navigation, simulated focus/flash settings, illustrative DISP cycling, bilingual menus, TXT reports, About and developer tools. This browser preview is a separate interaction prototype.

- [Interactive preview — portable HTML](design/ui-preview/preview.html)
- [Preview instructions and scope](design/ui-preview/README.md)
- [Physical controls and DISP](design/ui-preview/CONTROLS.md)
- [Current compact browser — English](design/ui-preview/screenshots/v04-library-en.png)
- [Current compact browser — 中文](design/ui-preview/screenshots/v04-library-zh.png)
- [Earlier screen overview](design/ui-preview/screenshots/overview.png)
- [DISP screen examples](design/ui-preview/screenshots/disp-overview.png)
- [Settings screen examples — English / 中文](design/ui-preview/screenshots/settings-overview.png)
- [Expanded menu and diagnostics specification](design/settings-and-diagnostics.md)
- [Issue-report TXT template](design/issue-report-example.txt)

To run the preview locally, from this repository:

```sh
python3 -m http.server 8080 --bind 127.0.0.1 --directory design/ui-preview
```

Open `http://localhost:8080`. The preview has no package dependencies and stores example recipe edits only in your browser.

The preview's report button requests a browser download. Use the native APK and hardware checklist to test actual camera behavior.
