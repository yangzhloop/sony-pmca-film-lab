# Shooting bridge host checks

Run `python3 tools/test_engine_host.py` from the repository root after preparing
the pinned Java/API 10 toolchain. The suite compiles the **production**
`ShootingBridge`, `Recipe`, `RecipeStore`, and `RecipeMath` against the small,
test-only Android/Sony interfaces in this directory. None of these fixtures may
be packaged in an APK.

The Sony fixtures follow the signatures and relevant mutation order inspected in
the pinned Film Studio still APK. They model the maintained camera parameter
cache, the distinction between `custom3` and the raw `custom` WB mode, per-mode
WB backup storage, and the patched matrix/gamma acceptance boundary. Deliberate
failures cover malformed gamma, native WB rejection after its backup write,
and a real recipe-file write failure after the camera accepted a new look.

The assertions check preview/cancel transactions, rollback, independent color
and tone, the 100% source coefficient requirement, persisted active snapshots,
renaming/deletion during preview, keyboard event ownership, native menu routing,
and leaving the Sony idle state. Direct camera open/read/capture methods are
tripwires, because this layer must use the existing Sony controllers.

The performance regressions also assert actual call counts: initial/repeated
preview and Use reuse accepted look work, unchanged WB avoids resubmission,
external WB mode/detail changes are observed through Sony's maintained cache,
and state transitions or outside effect callbacks invalidate look reuse. The
real disk-failure rollback runs after preview promotion and checks Sony's
previous WB backup as well as the active camera look.

Startup tests use queued decor callbacks. They cover first and repeated normal
launches, the old persisted `library-seen` preference, APO/power/playback routes,
startup interrupted by playback, stale callbacks from a destroyed Activity,
and MENU opening with balanced native key ownership. A launch can only open the
library after the real bridge receives a genuine native idle callback.
An additional same-Activity reboot case checks that native resets cannot leave
the saved nondefault matrix, gamma or WB unapplied after initialization.

These tests do **not** execute Sony native code or Smali, Android rendering,
real camera buttons, JPEG processing, or filesystem power-loss behavior. The
Smali patch checks and A7R II hardware checklist remain separate validation.
