# Camera host tests

The current core suite passes 422 assertions; the shooting-bridge suite passes
166 assertions in nine scenarios; the UI suite passes 153 assertions. These run
production logic against host or fake Android/Sony interfaces, not a camera.

`ReportStoreTest.java` exercises report privacy, export and persistent camera
checkpoints. `RecipeStoreTest.java` checks recipe protection, persistence,
recovery and processing math. `PinyinDictionaryTest.java` and
`NameKeyboardTest.java` cover character lookup and physical name entry.
`DiagnosticsBridgeTimeTest.java` checks missing/invalid and valid Sony clock
data. `engine/` exercises preview/Use/rollback, native WB backup preservation,
active metadata and balanced key ownership across overlay transitions. Startup
cases cover first/repeated launches, old preferences, power/playback routes,
interrupted idle callbacks, a replaced Activity, and native camera restart on
the same Activity with nondefault saved recipe restoration.
`ui/` drives the production menu and naming code with queued handlers and view
fixtures, checking retained views, preview cancellation, report freshness and
Sony-exit sequencing. It also checks Favorites-first ordering, one-tab column
weights, transparent preview space, long names, menu wrapping, empty-category
restore failure and native focus routing in English/Chinese. The report-store suite also blocks private I/O to verify
background batching, bounded queues, coalescing and flush failure behavior.
`CameraKeysTest.java` checks 20 assertions in each of two fresh JVMs: documented
fallback scan codes, Android key codes, precedence, unknown keys, and mapping
provenance. Its reflected Sony fixture uses deliberately different numbers to
prove the runtime fields are read. These tests do not verify actual camera key
events, Android view behavior, Chinese font rendering, or native DISP handling.

All classes under `fixtures/`, `engine/` and `ui/` are test-only. Never include them in an APK; the
production builder compiles `camera/java/`, not `camera/tests/`.

Run from the repository root with the prepared cache:

```sh
python3 tools/test_camera_host.py
python3 tools/test_engine_host.py
python3 tools/test_ui_host.py
python3 -m unittest discover -s tools/tests -v
```

The host runner uses temporary class directories and requires no system Java
compiler. It never includes test fixtures in the production build.

To rerun the key tests from the repository root after preparing the pinned
toolchain (a Java runtime is sufficient; `javac` is not needed):

```sh
FILM_LAB_TEST_TOOLS=/workspace/.cache/film-lab/tools
FILM_LAB_KEY_TEST_DIR=$(mktemp -d /tmp/film-lab-key-tests.XXXXXX)
mkdir -p "$FILM_LAB_KEY_TEST_DIR/main" "$FILM_LAB_KEY_TEST_DIR/sony"

java -jar "$FILM_LAB_TEST_TOOLS/ecj-4.6.1.jar" -1.6 -proc:none -encoding UTF-8 \
  -bootclasspath "$FILM_LAB_TEST_TOOLS/android-api10.jar" \
  -d "$FILM_LAB_KEY_TEST_DIR/main" \
  camera/java/com/filmlab/diagnostics/CameraKeys.java \
  camera/tests/CameraKeysTest.java \
  camera/tests/fixtures/android/view/KeyEvent.java

java -jar "$FILM_LAB_TEST_TOOLS/ecj-4.6.1.jar" -1.6 -proc:none -encoding UTF-8 \
  -bootclasspath "$FILM_LAB_TEST_TOOLS/android-api10.jar" \
  -d "$FILM_LAB_KEY_TEST_DIR/sony" \
  camera/tests/fixtures/sony/com/sony/scalar/sysutil/ScalarInput.java

java -cp "$FILM_LAB_KEY_TEST_DIR/main" \
  com.filmlab.diagnostics.CameraKeysTest fallback
java -cp "$FILM_LAB_KEY_TEST_DIR/main:$FILM_LAB_KEY_TEST_DIR/sony" \
  com.filmlab.diagnostics.CameraKeysTest reflected
```

Expected output: `20 assertions passed` for each mode, 40 total. A different
cache location can be supplied by changing `FILM_LAB_TEST_TOOLS`.
