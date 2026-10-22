import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import com.filmlab.app.FilmLabUi;
import com.filmlab.app.Recipe;
import com.filmlab.app.RecipeMath;
import com.filmlab.app.RecipeStore;
import com.filmlab.app.ShootingBridge;
import com.filmlab.diagnostics.DiagnosticsBridge;
import com.sony.imaging.app.base.shooting.camera.CameraSetting;
import com.sony.imaging.app.base.shooting.camera.WhiteBalanceController;
import com.sony.imaging.app.base.shooting.camera.WhiteBalanceController.WhiteBalanceParam;
import com.yuki.imaging.app.pictureeffectplus.shooting.camera.PictureEffectPlusController;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Real bridge + real store/math, with API-shaped Sony fixtures at the camera boundary. */
public final class ShootingBridgeTest {
    private static int assertions;
    private final CameraSetting camera = CameraSetting.getInstance();
    private final WhiteBalanceController wb = WhiteBalanceController.getInstance();
    private final PictureEffectPlusController effect = PictureEffectPlusController.getInstance();
    private Activity activity;
    private File directory;
    private RecipeStore store;
    private IdleState idle;

    public static final class IdleState {
        public String state;
        public Bundle bundle;
        public void setNextState(String state, Bundle bundle) { this.state = state; this.bundle = bundle; }
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void ok(String error, String message) { check(error == null, message + ": " + error); }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private void start() throws Exception {
        directory = File.createTempFile("film-engine-", "-test");
        if (!directory.delete() || !directory.mkdir()) throw new IOException("temp");
        camera.reset(); wb.reset(); effect.reset();
        store = new RecipeStore(directory); FilmLabUi.store = store;
        FilmLabUi.open = false; FilmLabUi.closeOnKey = false;
        activity = new Activity(); idle = new IdleState();
        ShootingBridge.attach(activity); ShootingBridge.startupRoute(0, "APP_SHOOTING"); ShootingBridge.idleReady(idle);
        store.activate(store.get("pop-color"));
    }
    private void finish() { ShootingBridge.detach(activity); remove(directory); }
    private Recipe custom() {
        Recipe r = store.createDraft("fuji-chrome"); r.name = "Evening 雨";
        r.color = 20; r.tone = 70; r.shadows = 1; r.highlights = -2;
        r.wb = "kelvin"; r.kelvin = 6100; r.ab = 2; r.gm = -1;
        return r;
    }
    private void customSonyWB() {
        wb.seed("custom3", -3, 4, 6500); wb.seed("color-temp", 1, 2, 4200);
        wb.setValue("WhiteBalance", "custom3");
    }
    private void originalLook(String message) {
        check(Arrays.equals(camera.matrix, PictureEffectPlusController.baseMatrix("pop-color")), message + " matrix");
        check(Arrays.equals(camera.gamma, PictureEffectPlusController.baseGamma("pop-color")), message + " gamma");
    }
    private void originalWB(String message) {
        check("custom3".equals(camera.mode) && camera.ab == -3 && camera.gm == 4 && camera.kelvin == 6500,
            message + " custom slot and numeric options");
    }

    private void previewTransaction() throws Exception {
        start();
        try {
            originalLook("initial accepted look");
            check(effect.sourceStrength == 100, "engine forces 100% source arrays despite legacy 30% preference");
            customSonyWB();
            Recipe recipe = custom(); int detailBefore = wb.detailWrites, backupsBefore = wb.backupWrites;
            ok(ShootingBridge.preview(activity, recipe), "preview accepted");
            check(recipe.id == null && recipe.name.equals(ShootingBridge.displayName(recipe.baseId, "fallback")),
                "unsaved draft preview keeps its chosen name");
            check(Arrays.equals(camera.matrix, RecipeMath.matrix(PictureEffectPlusController.baseMatrix(recipe.baseId), 20)),
                "color uses independent requested strength");
            check(Arrays.equals(camera.gamma, RecipeMath.gamma(PictureEffectPlusController.baseGamma(recipe.baseId), 70, 1, -2)),
                "tone and curve controls use source gamma independently");
            check("color-temp".equals(camera.mode) && camera.ab == 2 && camera.gm == -1 && camera.kelvin == 6100,
                "preview applies requested WB");
            check(wb.detailWrites == detailBefore && wb.backupWrites == backupsBefore,
                "preview never persists Sony per-mode WB options");
            check("pop-color".equals(store.getActive().id) && "pop-color".equals(ShootingBridge.currentRecipe().id),
                "preview changes neither stored nor confirmed selection");
            ok(ShootingBridge.cancelPreview(), "cancel preview"); originalLook("cancel"); originalWB("cancel");
            check(wb.getWBOptionFromBackUp("color-temp").colorTemp == 4200, "target mode backup survives browsing");
        } finally { finish(); }
    }

    private void persistenceFailureRollback() throws Exception {
        start();
        try {
            customSonyWB(); Recipe recipe = store.save(custom());
            ok(ShootingBridge.preview(activity, recipe), "preview before Use");
            int lookWrites = effect.writes, modeWrites = wb.modeWrites, detailWrites = wb.detailWrites;
            int parameterWrites = camera.parameterWrites;
            ok(ShootingBridge.apply(activity, recipe), "native Use accepted");
            check(effect.writes == lookWrites && wb.modeWrites == modeWrites
                && wb.detailWrites == detailWrites && camera.parameterWrites == parameterWrites,
                "Use promotes the accepted preview without resending look or WB parameters");
            check(wb.getWBOptionFromBackUp("color-temp").colorTemp == 6100, "Use commits target-mode WB options");
            File pending = new File(directory, "recipes.new"); check(pending.mkdir(), "block real store write");
            FileOutputStream blocker = new FileOutputStream(new File(pending, "keep")); blocker.close();
            boolean failed = false;
            try { store.activate(recipe); } catch (IOException expected) { failed = true; }
            check(failed, "active persistence failed independently of native acceptance");
            ok(ShootingBridge.rollbackLastApply(), "rollback after disk failure");
            originalLook("disk failure rollback"); originalWB("disk failure rollback");
            check("pop-color".equals(ShootingBridge.currentRecipe().id) && "pop-color".equals(store.getActive().id),
                "confirmed and persisted selection remain original");
            WhiteBalanceParam restored = wb.getWBOptionFromBackUp("color-temp");
            check(restored.lightBalance == 1 && restored.colorComp == 2 && restored.colorTemp == 4200,
                "target-mode Sony preferences rolled back too");
            remove(pending);
            ok(ShootingBridge.apply(activity, recipe), "retry native Use"); store.activate(recipe);
            check(recipe.id.equals(new RecipeStore(directory).getActive().id), "retry persists accepted recipe");
            int[] matrix = camera.matrix.clone(); byte[] gamma = camera.gamma.clone();
            ShootingBridge.detach(activity); activity = new Activity();
            ShootingBridge.attach(activity); ShootingBridge.idleReady(new IdleState());
            check(Arrays.equals(matrix, camera.matrix) && Arrays.equals(gamma, camera.gamma) && camera.kelvin == 6100,
                "restart reapplies complete accepted snapshot");
        } finally { finish(); }
    }

    private void failuresDoNotCommit() throws Exception {
        start();
        try {
            customSonyWB(); Recipe recipe = custom();
            effect.invalidGammaOnce = true;
            check("recipe_apply_failed".equals(ShootingBridge.preview(activity, recipe)), "bad gamma rejected by hook boundary");
            originalLook("bad gamma rollback"); originalWB("bad gamma rollback");
            check("pop-color".equals(ShootingBridge.currentRecipe().id), "bad gamma does not confirm recipe");
            ok(ShootingBridge.cancelPreview(), "cancel failed preview");
            wb.failDetailOnce = true;
            check("recipe_apply_failed".equals(ShootingBridge.apply(activity, recipe)), "native WB setter rejection reported");
            originalLook("WB rejection rollback"); originalWB("WB rejection rollback");
            check(wb.getWBOptionFromBackUp("color-temp").colorTemp == 4200,
                "WB rejection restores backup written before failing native setter");
            int writes = effect.writes;
            recipe.kelvin = 5555;
            check("kelvin_unsupported".equals(ShootingBridge.apply(activity, recipe)), "unsupported Kelvin blocked");
            check(effect.writes == writes, "unsupported recipe never reaches look setter");
        } finally { finish(); }
    }

    private void metadataAndLifecycle() throws Exception {
        start();
        try {
            Recipe saved = store.save(custom());
            ok(ShootingBridge.apply(activity, saved), "apply user recipe"); store.activate(saved);
            int[] matrix = camera.matrix.clone(); byte[] gamma = camera.gamma.clone();
            ok(ShootingBridge.preview(activity, store.get("pop-color")), "browse another profile");
            store.rename(saved.id, "New name"); ShootingBridge.syncActiveMetadata(store.getActive());
            ok(ShootingBridge.cancelPreview(), "cancel after rename");
            check("New name".equals(ShootingBridge.currentRecipe().name), "preview cancellation preserves renamed metadata");
            ok(ShootingBridge.preview(activity, store.get("pop-color")), "browse before delete");
            store.delete(saved.id); ShootingBridge.syncActiveMetadata(store.getActive());
            ok(ShootingBridge.cancelPreview(), "cancel after delete");
            check(ShootingBridge.currentRecipe().id == null && store.getActive().id == null,
                "preview cancellation never resurrects deleted recipe identity");
            check("Current look (unsaved)".equals(ShootingBridge.displayName(saved.baseId, "fallback")),
                "deleted active recipe has an explicit unsaved-look label");
            check(Arrays.equals(matrix, camera.matrix) && Arrays.equals(gamma, camera.gamma),
                "rename/delete preserve accepted color snapshot");
            int[] openingKeys = {520, 514};
            for (int openingKey : openingKeys) {
                FilmLabUi.close();
                check(!ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, openingKey, 0)),
                    "opening key down remains owned by native Sony input");
                FilmLabUi.open("library");
                check(!ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_UP, openingKey, 0)),
                    "matching opening key up reaches Sony even after overlay appears");
            }
            FilmLabUi.open("settings"); FilmLabUi.closeOnKey = true;
            check(ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, 232, 0)), "overlay key down consumed");
            check(!FilmLabUi.isOpen() && ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_UP, 232, 0)),
                "closing overlay consumes matching key up");
            check(!ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, 608, 0)), "DISP passes through while shooting");
            FilmLabUi.closeOnKey = false;
            ok(ShootingBridge.openNativeMenu("FlashMode"), "native flash route");
            check("Menu".equals(idle.state) && "FlashMode".equals(idle.bundle.getString("ItemId")), "flash route passes exact native item");
            ok(ShootingBridge.openNativeMenu("FocusSelection"), "native focus mode route");
            check("Menu".equals(idle.state) && "FocusSelection".equals(idle.bundle.getString("ItemId")), "focus mode passes Sony's actual item ID");
            ok(ShootingBridge.openNativeMenu("FocusArea"), "native focus area route");
            check("Menu".equals(idle.state) && "FocusArea".equals(idle.bundle.getString("ItemId")), "focus area passes Sony's actual item ID");
            check("menu_unsupported".equals(ShootingBridge.openNativeMenu("arbitrary")), "unknown native route blocked");
            ok(ShootingBridge.preview(activity, store.get("pop-color")), "preview before native state transition");
            ShootingBridge.idleLeft(idle);
            check(!FilmLabUi.isOpen() && "camera_not_ready".equals(ShootingBridge.preview(activity, custom())),
                "leaving native idle closes overlay and blocks writes");
            check(Arrays.equals(matrix, camera.matrix) && Arrays.equals(gamma, camera.gamma), "state transition restores preview");
            check(Camera.opens == 0 && Camera.captures == 0 && Camera.reads == 0,
                "bridge never opens, captures, or directly reads another camera");
        } finally { finish(); }
    }

    private void repeatedPreviewAndActualWB() throws Exception {
        start();
        try {
            int looks = effect.writes, modes = wb.modeWrites, details = wb.detailWrites;
            int parameters = camera.parameterWrites, backups = wb.backupWrites;
            ok(ShootingBridge.preview(activity, store.get("pop-color")), "first library preview after restore");
            check(effect.writes == looks && wb.modeWrites == modes && wb.detailWrites == details
                && camera.parameterWrites == parameters && wb.backupWrites == backups,
                "library's identical initial highlight performs no camera or backup writes");
            Recipe next = store.get("fuji-velvia");
            ok(ShootingBridge.preview(activity, next), "next built-in preview");
            check(effect.writes == looks + 1 && wb.modeWrites == modes && camera.parameterWrites == parameters,
                "switching built-in look reuses unchanged auto WB");
            looks = effect.writes;
            ok(ShootingBridge.preview(activity, next.copy()), "identical second preview");
            check(effect.writes == looks && wb.modeWrites == modes && camera.parameterWrites == parameters,
                "duplicate accepted preview causes no native submission");
            camera.ab = 3;
            ok(ShootingBridge.preview(activity, next), "preview after external same-mode WB detail change");
            check(effect.writes == looks && wb.modeWrites == modes && camera.ab == 0
                && camera.parameterWrites == parameters + 1,
                "cached actual WB details are checked even for an identical look");
            customSonyWB(); modes = wb.modeWrites; parameters = camera.parameterWrites;
            ok(ShootingBridge.preview(activity, next), "preview after external custom-slot WB change");
            check(effect.writes == looks && wb.modeWrites == modes + 1 && "auto".equals(camera.mode)
                && camera.ab == 0 && camera.gm == 0 && camera.parameterWrites == parameters,
                "different actual WB mode is corrected once and matching restored options are reused");
            wb.available = false;
            check("white_balance_unavailable".equals(ShootingBridge.preview(activity, next)),
                "cached look never bypasses current native availability validation");
            wb.available = true;
            ok(ShootingBridge.cancelPreview(), "cancel deduplicated browsing");
            originalLook("deduplicated cancel");
        } finally { finish(); }
    }

    private void nativeGenerationInvalidatesLook() throws Exception {
        start();
        try {
            Recipe same = store.get("pop-color");
            int looks = effect.writes;
            ShootingBridge.idleLeft(idle);
            camera.matrix = new int[9];
            idle = new IdleState(); ShootingBridge.idleReady(idle);
            ok(ShootingBridge.preview(activity, same), "preview after native state transition");
            check(effect.writes == looks + 1, "returning from native state invalidates look cache");
            originalLook("native generation restoration");
            effect.setValue("ApplicationTop", "fuji-chrome");
            looks = effect.writes;
            ok(ShootingBridge.preview(activity, same), "preview after external effect callback");
            check(effect.writes == looks + 1, "external effect acceptance invalidates look cache");
            originalLook("external effect restoration");
            Recipe tuned = store.createDraft(same.baseId); tuned.color = 31;
            looks = effect.writes;
            ok(ShootingBridge.preview(activity, tuned), "same profile with new strength");
            check(effect.writes == looks + 1
                && Arrays.equals(camera.matrix, RecipeMath.matrix(PictureEffectPlusController.baseMatrix(same.baseId), 31)),
                "look cache compares processing values, not only base profile ID");
            ok(ShootingBridge.cancelPreview(), "cancel after cache invalidation");
        } finally { finish(); }
    }

    private void launcherAndResumeRoutes() throws Exception {
        start();
        try {
            ShootingBridge.detach(activity);
            int[] factors = {0, 0, 1, 2, 1, 2, 0, 99, 0};
            String[] apps = {"APP_SHOOTING", "APP_SHOOTING", "APP_SHOOTING", "APP_SHOOTING",
                "APP_PLAY", "APP_PLAY", "APP_PLAY", "APP_SHOOTING", null};
            for (int index = 0; index < factors.length; index++) {
                activity = new Activity();
                // Exercise both a clean install and an upgrade with the old persisted flag.
                activity.getSharedPreferences("filmlab-ui", 0).edit().putBoolean("library-seen", index != 0).commit();
                View queue = activity.getWindow().getDecorView(); queue.deferred = true;
                int before = FilmLabUi.openCalls;
                ShootingBridge.attach(activity);
                ShootingBridge.startupRoute(factors[index], apps[index]);
                ShootingBridge.attach(activity); // synchronous onBoot/onCreate attachment is idempotent
                check(!FilmLabUi.isOpen() && !ShootingBridge.open("settings"), "boot never fabricates shooting readiness");
                idle = new IdleState(); ShootingBridge.idleReady(idle);
                check(!FilmLabUi.isOpen(), "startup overlay waits for queued genuine idle completion");
                queue.drain();
                boolean ordinaryLaunch = index < 2;
                check(FilmLabUi.isOpen() == ordinaryLaunch, "launcher opens library; power/playback/unknown routes retain shooting");
                check(FilmLabUi.openCalls == before + (ordinaryLaunch ? 1 : 0), "library opens once for each normal invocation");
                if (ordinaryLaunch) check("library".equals(FilmLabUi.page), "normal entry is Film Lab library");
                FilmLabUi.close(); ShootingBridge.idleLeft(idle);
                idle = new IdleState(); ShootingBridge.idleReady(idle); queue.drain();
                check(!FilmLabUi.isOpen(), "return from capture/menu/playback does not reopen startup library");
                check(ShootingBridge.open("settings") && "settings".equals(FilmLabUi.page), "MENU remains available after native resume");
                ShootingBridge.detach(activity);
            }
            check(DiagnosticsBridge.events.contains("lifecycle.resume phase=launch;code=1;mode=playback;screen=none"),
                "boot route diagnostics distinguish native playback from ordinary launch");
        } finally { finish(); }
    }

    private void interruptedStartup() throws Exception {
        start();
        try {
            ShootingBridge.detach(activity); activity = new Activity();
            View queue = activity.getWindow().getDecorView(); queue.deferred = true;
            ShootingBridge.attach(activity); ShootingBridge.startupRoute(0, "APP_SHOOTING");
            idle = new IdleState(); ShootingBridge.idleReady(idle);
            ShootingBridge.idleLeft(idle); queue.drain();
            check(!FilmLabUi.isOpen(), "playback interruption invalidates queued startup callback");
            int before = FilmLabUi.openCalls;
            idle = new IdleState(); ShootingBridge.idleReady(idle); queue.drain();
            check(FilmLabUi.isOpen() && FilmLabUi.openCalls == before + 1,
                "pending normal launch opens once after genuine idle becomes available again");
            ShootingBridge.detach(activity); activity = new Activity();
            View staleQueue = activity.getWindow().getDecorView(); staleQueue.deferred = true;
            ShootingBridge.attach(activity); ShootingBridge.startupRoute(0, "APP_SHOOTING");
            ShootingBridge.idleReady(new IdleState()); ShootingBridge.detach(activity);
            activity = new Activity();
            View replacementQueue = activity.getWindow().getDecorView(); replacementQueue.deferred = true;
            ShootingBridge.attach(activity); ShootingBridge.startupRoute(2, "APP_PLAY");
            ShootingBridge.idleReady(new IdleState()); staleQueue.drain(); replacementQueue.drain();
            check(!FilmLabUi.isOpen(), "destroyed launcher cannot open over replacement playback session");
            check(!ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, 514, 0)), "native MENU down passes through");
            check(ShootingBridge.open("settings"), "native idle handler opens settings");
            check(!ShootingBridge.dispatch(new KeyEvent(KeyEvent.ACTION_UP, 514, 0)), "opening MENU up remains balanced");
            check(DiagnosticsBridge.events.contains("key.event phase=down;source=native-camera;key_code=0;scan_code=514"),
                "report records pre-overlay native MENU without character data");
        } finally { finish(); }
    }

    private void sameActivityRebootRestoresSavedLook() throws Exception {
        start();
        try {
            Recipe saved = store.save(custom());
            ok(ShootingBridge.apply(activity, saved), "commit nondefault recipe before native reboot");
            store.activate(saved);
            int[] expectedMatrix = camera.matrix.clone(); byte[] expectedGamma = camera.gamma.clone();
            ShootingBridge.idleLeft(idle);
            View queue = activity.getWindow().getDecorView(); queue.deferred = true;
            // An old generation's idle callback must not run while native boot rebuilds the camera.
            ShootingBridge.idleReady(new IdleState());
            ShootingBridge.attach(activity); ShootingBridge.startupRoute(0, "APP_SHOOTING");
            effect.setValue("ApplicationTop", "pop-color"); wb.reset();
            int writes = effect.writes;
            queue.drain();
            check(effect.writes == writes && !FilmLabUi.isOpen(), "same-host reboot invalidates old queued idle work");
            check(!ShootingBridge.open("settings"), "same-host reboot clears previous native idle readiness");
            idle = new IdleState(); ShootingBridge.idleReady(idle); queue.drain();
            check(Arrays.equals(camera.matrix, expectedMatrix) && Arrays.equals(camera.gamma, expectedGamma)
                && camera.kelvin == 6100 && camera.ab == 2 && camera.gm == -1,
                "same-host launcher reboot restores saved look and WB after native default reset");
            check(FilmLabUi.isOpen() && "library".equals(FilmLabUi.page), "same-host launcher reboot opens Film Lab library");
            ShootingBridge.idleLeft(idle);
            ShootingBridge.startupRoute(2, "APP_PLAY");
            effect.setValue("ApplicationTop", "pop-color"); wb.reset(); writes = effect.writes;
            queue.drain();
            check(effect.writes == writes && !FilmLabUi.isOpen(), "playback reboot waits for native shooting idle before restoration");
            idle = new IdleState(); ShootingBridge.idleReady(idle); queue.drain();
            check(Arrays.equals(camera.matrix, expectedMatrix) && Arrays.equals(camera.gamma, expectedGamma)
                && camera.kelvin == 6100 && !FilmLabUi.isOpen(),
                "return from playback reboot restores saved recipe without reopening launch library");
        } finally { finish(); }
    }

    public static void main(String[] args) throws Exception {
        ShootingBridgeTest test = new ShootingBridgeTest();
        test.previewTransaction(); test.persistenceFailureRollback(); test.failuresDoNotCommit(); test.metadataAndLifecycle();
        test.repeatedPreviewAndActualWB(); test.nativeGenerationInvalidatesLook();
        test.launcherAndResumeRoutes(); test.interruptedStartup();
        test.sameActivityRebootRestoresSavedLook();
        System.out.println("ShootingBridgeTest: 9 scenarios / " + assertions + " assertions passed");
    }
}
