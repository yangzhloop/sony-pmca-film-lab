# Film Lab UI host regression harness

Runs the production FilmLabUi, NameKeyboard, PinyinDictionary, CameraKeys and recipe
store with API-shaped Android fixtures. A deterministic queued Handler exercises
actual physical-key navigation and preview timing without an emulator. Views keep
their hierarchy, text, selection colors and creation counters, so tests can
distinguish incremental updates from repeated page construction.

The 153 assertions include Favorites-first entry/order, empty/single-item menus,
wraparound with scrolling and held keys, one-tab column weights, transparent
library backgrounds, absence of inline detail views, single-line name ellipsis,
focus routing and failed empty-category restoration. Layout parameters and view
properties are recorded by the fixtures; these checks do not perform actual
Android text measurement or rendering over a camera surface.

ShootingBridge and DiagnosticsBridge are controlled boundaries: camera/diagnostic
internals have separate suites. Report workers use real Java threads with a gate
and bounded waits, including failure/retry and Activity replacement. Finish
ordering is observed through the fake Activity.

Run `python3 tools/test_ui_host.py` after cloud setup. This tests UI state and
transactions, not Android rendering, Sony hardware, actual button scan timing or
camera processing speed.
