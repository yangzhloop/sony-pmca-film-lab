# Cloud environment setup

The setup script was exercised successfully in this workspace. The configuration draft uses its complete contents as the installation script and the start instructions below. Review/save the draft in Environment settings and publish to apply it; saving a draft does not publish or prove restoration in a new task.

Network policy, repository membership and credential requirements are preserved. No new secrets are required. Caches and signing files stay outside this checkout.

## Installation script

See [tools/setup_cloud.sh](../tools/setup_cloud.sh).

## Saved start instructions

# Film Lab cloud development

Use the existing checkout at /workspace/sony-pmca-film-lab. Cloud tasks are already isolated; do not create a worktree unless the user explicitly requests one. Preserve existing changes and inspect any AGENTS.md instructions before editing.

The browser target is design/ui-preview, an original interaction prototype, separate from the native camera APK. Python's standard library serves/builds it; no app packages, secrets, or external network are required. Python 3.12, Node 24, Python Playwright 1.62, and /usr/bin/chromium were available and exercised in this prepared instance. Live processes do not survive publication/restoration; check them again in each task. The native build is prepared as described below; A7R II hardware validation remains pending.

From /workspace/sony-pmca-film-lab, regenerate the portable artifact after edits:
python3 design/ui-preview/bundle.py

Start the local preview server if no existing server is serving this exact directory:
python3 -m http.server 8080 --bind 0.0.0.0 --directory design/ui-preview

Keep the server in a managed terminal session. Verify an HTTP GET to http://127.0.0.1:8080/ returns the Film Lab entry point and serves diagnostics.js. Inspect startup failures; do not treat a PID/open port alone as readiness. Reuse a verified existing preview server rather than starting another on the same port.

Run relevant checks from the repository root:
node --test design/ui-preview/tests/diagnostics.test.js
python3 design/ui-preview/tests/verify_settings.py

The browser test uses /usr/bin/chromium with Playwright; use the installed system browser rather than downloading a bundled browser. Recheck availability with python3 -c 'import playwright' and chromium --version if restoration changed the runtime. These checks validated six diagnostic tests and twelve browser scenarios in this instance, including English/Chinese navigation, TXT download/retry, recipe preservation and non-touch controls. Do not claim fresh-task validation until rerun.

For portable preview review use design/ui-preview/preview.html. Managed Chromium blocks file URLs, so validate the artifact by serving /preview.html and blocking all secondary resource requests. Do not present loopback addresses as onboarding publication links. Read design/ui-preview/README.md and VALIDATION.md for scope and remaining hardware limitations.

## Native Film Lab 0.2.3-alpha APK

Read docs/camera-build.md, docs/native-app-0.2.3.md and docs/native-app-0.2.3-validation.md before changing the camera target. Film Lab's bilingual library, editor and Settings overlay the inherited Sony live shooting activity. Version 0.2.3 adds Favorites-first entry, a fixed one-tab-wide preset column, transparent space around the library, vertical menu wrapping and native Focus mode/Focus area entries. Full names/actions remain on Center details; empty-library restoration failures stay visible and retryable. Sony's existing native menus already wrap, and their input paths remain unchanged. The app retains resource-backed app-list labels and routes normal launches to Film Lab's library after genuine native idle and saved-recipe restoration. It suppresses only the inherited automatic ApplicationTop startup menu, handles same-Activity native reboots, and preserves playback/APO/power boot routes. It retains 0.2.1's view reuse, reduced preview/WB work, background diagnostics, cartridge icons, Exit app and fresh report snapshots. FLR00005 exposed the prior startup route and literal-label defects; see the current findings. Preserve Sony's camera ownership, capture, DISP, flash and playback. The previous diagnostic reports identified an ILCE-7RM2 firmware 4.00 / Android API 16; this new version requires its own hardware validation.

The tested setup script is tools/setup_cloud.sh. It uses Python 3.12, Java 21/keytool, Git, OpenSSL and NumPy 2.3.5. It verifies/downloads pinned tools, upstream inputs, the full static Chinese font and Pinyin source over HTTPS with SHA-256 checks into /workspace/.cache/film-lab. It regenerates the font-covered Pinyin dictionary and notices deterministically. Sources compile against Android API 10 boot classes with pinned ECJ/Java 6 and dx/DEX035. No system Android SDK or javac is needed. Keep upstream cache checkouts separate from this project.

When building/testing after restoration, check prerequisites, then run from /workspace/sony-pmca-film-lab:
export PYTHONPATH="/workspace/.cache/film-lab/python${PYTHONPATH:+:$PYTHONPATH}"
python3 tools/camera_toolchain.py --offline
python3 tools/prepare_film_studio.py --offline
python3 tools/prepare_camera_font.py --offline
python3 tools/prepare_pinyin.py --offline
python3 tools/test_camera_host.py
python3 tools/test_engine_host.py
python3 tools/test_ui_host.py
python3 -m unittest discover -s tools/tests -v

Use sh tools/setup_cloud.sh if pinned inputs or NumPy are missing. A hash mismatch is a failure: do not disable TLS, relax hashes, or silently replace mismatched cached files. Upstream validators require assertions, so never use Python -O/PYTHONOPTIMIZE. Fresh Fujifilm LUT downloading was blocked; the implemented route extracts coefficients from the verified published APK, not a fresh LUT fit.

Build in an empty work directory:
film_lab_build_work="$(mktemp -d /tmp/film-lab-build.XXXXXX)"
python3 tools/build_diagnostic.py --offline --work "$film_lab_build_work" --output dist/FilmLab-0.2.3-alpha.apk

Read dist/FilmLab-0.2.3-alpha.build.json and match its SHA-256/source hashes to the actual artifact and current files. Package com.flab.imaging.app.pictureeffectplus retains original Sony/yuki class namespaces. The launcher is the inherited PictureEffectPlus Activity with Film Lab UI; DiagnosticActivity is no longer the launcher. Version code 5 and the retained signing identity allow an update from earlier Film Lab builds. APK binaries are ignored by Git; JSON receipts are reviewable. Keep /workspace/.cache/film-lab/private signing files private and outside Git, preserve them for updates, and never print password contents.

The core host suite passed 422 assertions, the shooting-bridge fixture suite passed 166 assertions across nine scenarios, the production UI fixture suite passed 153 assertions, and 27 Python build/font/branding/hook tests passed in this instance. Boot-route tests walk the generated Smali across 24 launch/playback/power/housing combinations. These use host fixtures, not a camera. The completed API10/v1-signed APK must pass independent manifest decoding, reference-label resolution, source-identity checks and legacy signature verification. Verify the current artifact receipt before distribution. Fresh-task restoration remains unverified. No camera is attached: physical button timing, actual rendering, Sony-menu transitions and saved JPEG appearance require the device checklist. The user installs the local APK on their computer through the documented PMCA route. No firmware modification is required by this build.
