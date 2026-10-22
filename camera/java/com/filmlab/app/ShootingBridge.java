// SPDX-License-Identifier: MIT
package com.filmlab.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;
import com.filmlab.diagnostics.DiagnosticsBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Film Lab owns its overlay and recipe values; Sony retains the camera and state
 * machine. Reflection targets below are verified against the pinned still APK.
 * This class never opens a camera or replaces its capture/DISP/flash handlers.
 */
public final class ShootingBridge {
    private static final String EFFECT = "com.yuki.imaging.app.pictureeffectplus.shooting.camera.PictureEffectPlusController";
    private static final String WB = "com.sony.imaging.app.base.shooting.camera.WhiteBalanceController";
    private static final Class[] NONE = new Class[0];
    private static final Class[] STRING = new Class[] { String.class };
    private static final Class[] STRINGS = new Class[] { String.class, String.class };
    private static WeakReference<Activity> activity = new WeakReference<Activity>(null);
    private static Object idleState;
    private static Recipe current, committed, previewOriginal, rollbackRecipe;
    private static WhiteBalance previewWhiteBalance, rollbackWhiteBalance;
    private static String rollbackBackupMode;
    private static Object rollbackBackup;
    private static boolean previewing, awaitingRestore, launchLibraryPending;
    private static boolean requestSeen, requestAccepted;
    private static String requestBase;
    private static int epoch;
    // A successful look is reusable only inside one uninterrupted Sony idle
    // generation. The controller's own force-setting paths must still run.
    private static Recipe appliedLook;
    private static int appliedEpoch = -1;
    private static final Set<Integer> consumedKeys = new HashSet<Integer>();
    private static final Set<Integer> nativeKeys = new HashSet<Integer>();
    private static final List<WeakReference<TextView>> appNames = new ArrayList<WeakReference<TextView>>();

    private ShootingBridge() { }

    public static void attach(Activity host) {
        try {
            if (activity.get() == host) return;
            activity = new WeakReference<Activity>(host);
            idleState = null;
            epoch++;
            invalidateLook();
            consumedKeys.clear();
            nativeKeys.clear();
            previewing = false;
            previewOriginal = null;
            previewWhiteBalance = null;
            FilmLabUi.attach(host);
            RecipeStore store = FilmLabUi.getStore();
            current = store == null ? null : store.getActive();
            if (current == null) current = RecipeStore.builtins().get(0);
            committed = copy(current);
            awaitingRestore = current != null;
            launchLibraryPending = false;
        } catch (Throwable failure) { event("attach_failed"); }
    }

    /** Sony resolves playback/power/housing routes before calling this hook. */
    public static void startupRoute(int bootFactor, String bootApp) {
        // Sony can shut down/rebuild its RootContainer on the same Activity.
        // Boot resets native effect preferences, so restore again at genuine
        // idle, and reject callbacks queued by the previous native generation.
        epoch++;
        idleState = null;
        invalidateLook();
        awaitingRestore = committed != null;
        // This is per launch, not per installation. An upgrade must not retain
        // the old library-seen preference and strand users in native shooting.
        launchLibraryPending = bootFactor == 0 && "APP_SHOOTING".equals(bootApp);
        String mode = "APP_SHOOTING".equals(bootApp) ? "shoot"
            : "APP_PLAY".equals(bootApp) ? "playback" : "other";
        DiagnosticsBridge.record("lifecycle.resume", "phase=launch;code="
            + (bootFactor >= 0 && bootFactor <= 2 ? bootFactor : -1) + ";mode=" + mode
            + ";screen=" + (launchLibraryPending ? "library" : "none"));
    }

    public static void detach(Activity host) {
        if (activity.get() != host) return;
        epoch++;
        invalidateLook();
        idleState = null;
        FilmLabUi.detach(host);
        activity.clear();
        consumedKeys.clear();
        nativeKeys.clear();
        appNames.clear();
        current = committed = previewOriginal = rollbackRecipe = null;
        previewWhiteBalance = rollbackWhiteBalance = null;
        rollbackBackup = null;
        previewing = false;
        awaitingRestore = launchLibraryPending = false;
    }

    /** Called only after the inherited S1OffEE state's normal resume completes. */
    public static void idleReady(Object state) {
        idleState = state;
        final int generation = ++epoch;
        invalidateLook();
        DiagnosticsBridge.milestone("first_idle");
        DiagnosticsBridge.record("lifecycle.resume", "stage=ready;mode=shoot;source=native-camera");
        final Activity host = activity.get();
        if (host == null) return;
        host.getWindow().getDecorView().post(new Runnable() {
            public void run() {
                if (generation != epoch || idleState == null || host.isFinishing()) return;
                if (awaitingRestore) {
                    awaitingRestore = false;
                    String error = apply(host, committed);
                    DiagnosticsBridge.milestone("active_restore_done");
                    if (error != null) { event("restore_failed"); launchLibraryPending = true; }
                }
                if (launchLibraryPending) {
                    launchLibraryPending = false;
                    DiagnosticsBridge.record("ui.action", "stage=open;screen=library;phase=launch");
                    FilmLabUi.open("library");
                }
            }
        });
    }

    /** State transitions, including capture/playback/native menus, remove the overlay. */
    public static void idleLeft(Object state) {
        if (state != idleState) return;
        DiagnosticsBridge.record("lifecycle.pause", "stage=ready;mode=shoot;source=native-camera");
        // Ordinarily every user exit already cancels or confirms. This also handles
        // external state changes while a preview is open, before Sony pauses EE.
        FilmLabUi.cancelPendingPreview();
        if (previewing) cancelPreview();
        idleState = null;
        epoch++;
        invalidateLook();
        FilmLabUi.close();
    }

    public static boolean open(String page) {
        String screen = "library".equals(page) ? "library" : "settings".equals(page) ? "settings" : "other";
        if (idleState == null || activity.get() == null) {
            DiagnosticsBridge.record("ui.action", "stage=open;screen=" + screen + ";status=busy");
            return false;
        }
        if (FilmLabUi.isOpen()) return true;
        DiagnosticsBridge.record("ui.action", "stage=open;screen=" + screen + ";status=ready");
        FilmLabUi.open(page);
        return true;
    }

    /** Consume both halves of overlay key events, even when DOWN closes the UI. */
    public static boolean dispatch(KeyEvent event) {
        int token = event.getScanCode() != 0 ? event.getScanCode() : -event.getKeyCode();
        Integer key = Integer.valueOf(token);
        if (event.getAction() == KeyEvent.ACTION_UP && nativeKeys.remove(key)) {
            consumedKeys.remove(key);
            // Fn/MENU's opening DOWN was delivered to Sony before the overlay
            // existed. Its UP must reach Sony too, keeping P1Key state balanced.
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && consumedKeys.remove(key)) return true;
        if (!FilmLabUi.isOpen()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                consumedKeys.remove(key);
                if (nativeKeys.add(key)) DiagnosticsBridge.record("key.event", "phase=down;source=native-camera;key_code="
                    + event.getKeyCode() + ";scan_code=" + event.getScanCode());
            }
            return false;
        }
        boolean handled = FilmLabUi.dispatch(event);
        if (handled && event.getAction() == KeyEvent.ACTION_DOWN) consumedKeys.add(key);
        return handled;
    }

    /** Library highlighting does not save a recipe or active selection. */
    public static String preview(Activity host, Recipe recipe) {
        long started = System.nanoTime();
        try { return previewInternal(host, recipe); }
        finally { timing("preview_apply", started); }
    }

    private static String previewInternal(Activity host, Recipe recipe) {
        if (idleState == null) return "camera_not_ready";
        if (!previewing) {
            try { previewWhiteBalance = readWhiteBalance(); }
            catch (Throwable failure) { return "white_balance_unavailable"; }
            previewOriginal = copy(committed);
            previewing = true;
        }
        return applyInternal(recipe, false);
    }

    /** Restore the look and controller WB snapshot from before browsing. */
    public static String cancelPreview() {
        long started = System.nanoTime();
        try { return cancelPreviewInternal(); }
        finally { timing("restore_apply", started); }
    }

    private static String cancelPreviewInternal() {
        if (!previewing) return null;
        if (idleState == null) return "camera_not_ready";
        Recipe original = copy(previewOriginal);
        WhiteBalance wb = previewWhiteBalance;
        String error = applyInternal(original, false, wb);
        if (error == null) {
            committed = copy(original);
            previewing = false;
            previewOriginal = null;
            previewWhiteBalance = null;
            publishActive();
            refreshNames();
        }
        return error;
    }

    /** Return null only after the inherited look hook accepted and WB calls completed. */
    public static String apply(Activity host, Recipe recipe) {
        long started = System.nanoTime();
        try { return applyCommitted(host, recipe); }
        finally { timing("recipe_apply", started); }
    }

    private static String applyCommitted(Activity host, Recipe recipe) {
        try { recipe = RecipeStore.validated(recipe); }
        catch (IllegalArgumentException invalid) { return "recipe_invalid"; }
        Recipe previous = copy(previewing ? previewOriginal : committed);
        WhiteBalance previousWB;
        Object backup;
        try {
            previousWB = previewing ? previewWhiteBalance : readWhiteBalance();
            backup = wbBackup(wbMode(recipe));
        } catch (Throwable unavailable) { return "white_balance_unavailable"; }
        String error = applyInternal(recipe, true);
        if (error == null) {
            rollbackRecipe = previous;
            rollbackWhiteBalance = previousWB;
            rollbackBackupMode = wbMode(recipe);
            rollbackBackup = backup;
            committed = copy(recipe);
            previewing = false;
            previewOriginal = null;
            previewWhiteBalance = null;
            publishActive();
            refreshNames();
        } else {
            try { restoreWBBackup(wbMode(recipe), backup); }
            catch (Throwable restoreFailure) { return "recipe_restore_failed"; }
        }
        return error;
    }

    /** Used only if persisting an accepted selection fails. No store writes here. */
    public static String rollbackLastApply() {
        long started = System.nanoTime();
        try { return rollbackInternal(); }
        finally { timing("restore_apply", started); }
    }

    private static String rollbackInternal() {
        if (rollbackRecipe == null || rollbackWhiteBalance == null) return "restore_unavailable";
        String error = applyInternal(rollbackRecipe, false, rollbackWhiteBalance);
        if (error != null) return error;
        try {
            if (rollbackBackup != null) restoreWBBackup(rollbackBackupMode, rollbackBackup);
            committed = copy(rollbackRecipe);
            rollbackRecipe = null;
            rollbackWhiteBalance = null;
            rollbackBackup = null;
            publishActive();
            refreshNames();
            return null;
        } catch (Throwable failure) { return "recipe_restore_failed"; }
    }

    /** Rename/delete metadata does not change the already applied color snapshot. */
    public static void syncActiveMetadata(Recipe recipe) {
        if (committed == null || recipe == null) return;
        committed.id = recipe.id;
        committed.name = recipe.name;
        committed.revision = recipe.revision;
        if (previewOriginal != null) {
            previewOriginal.id = recipe.id; previewOriginal.name = recipe.name; previewOriginal.revision = recipe.revision;
        }
        if (!previewing && current != null) {
            current.id = recipe.id; current.name = recipe.name; current.revision = recipe.revision;
        }
        publishActive();
        refreshNames();
    }

    public static Recipe currentRecipe() { return copy(committed); }

    public static String validate(Recipe recipe) {
        if (recipe == null) return "recipe_missing";
        if (idleState == null) return "camera_not_ready";
        try { RecipeStore.validated(recipe); }
        catch (IllegalArgumentException invalid) { return "recipe_invalid"; }
        try {
            Object controller = singleton(WB);
            if (!Boolean.TRUE.equals(call(controller, "isAvailable", STRING, new Object[] { "WhiteBalance" })))
                return "white_balance_unavailable";
            if (!supported(controller, "WhiteBalance", wbMode(recipe))) return "white_balance_unsupported";
            if (!supported(controller, "light-balance", String.valueOf(recipe.ab))) return "ab_unsupported";
            if (!supported(controller, "color-compensation", String.valueOf(recipe.gm))) return "gm_unsupported";
            if ("kelvin".equals(recipe.wb) && !supported(controller, "color-temperature", String.valueOf(recipe.kelvin)))
                return "kelvin_unsupported";
            return null;
        } catch (Throwable failure) { return "white_balance_unavailable"; }
    }

    private static String applyInternal(Recipe recipe, boolean commit) {
        return applyInternal(recipe, commit, null);
    }

    private static String applyInternal(Recipe recipe, boolean commit, WhiteBalance restoreWB) {
        String validation = validate(recipe);
        if (validation != null) return validation;
        recipe = RecipeStore.validated(recipe);
        Recipe previous = copy(current);
        WhiteBalance previousWB;
        try { previousWB = readWhiteBalance(); }
        catch (Throwable failure) { return "white_balance_unavailable"; }
        current = recipe.copy();
        try {
            if (!sameAppliedLook(recipe)) applyLook(recipe);
            writeWhiteBalance(restoreWB == null
                ? new WhiteBalance(wbMode(recipe), recipe.ab, recipe.gm, recipe.kelvin) : restoreWB,
                commit, previousWB);
            appliedLook = copy(recipe);
            appliedEpoch = epoch;
            event(commit ? "recipe_applied" : "recipe_previewed");
            refreshNames();
            return null;
        } catch (Throwable failure) {
            current = previous;
            invalidateLook();
            boolean restored = true;
            try {
                if (previous != null) {
                    applyLook(previous);
                }
            }
            catch (Throwable rollbackFailure) { restored = false; }
            try { writeWhiteBalance(previousWB, false, readWhiteBalance()); }
            catch (Throwable rollbackFailure) { restored = false; }
            if (restored && previous != null) {
                appliedLook = copy(previous);
                appliedEpoch = epoch;
            }
            event(restored ? "recipe_apply_failed" : "recipe_restore_failed");
            refreshNames();
            return restored ? "recipe_apply_failed" : "recipe_restore_failed";
        } finally { requestBase = null; }
    }

    private static void applyLook(Recipe recipe) throws Exception {
        long started = System.nanoTime();
        try {
            requestBase = recipe.baseId;
            requestSeen = false;
            requestAccepted = false;
            setLook(recipe.baseId);
            if (!requestSeen || !requestAccepted) throw new IllegalStateException("look_rejected");
        } finally { requestBase = null; timing("look_apply", started); }
    }

    private static boolean sameAppliedLook(Recipe recipe) {
        return appliedEpoch == epoch && appliedLook != null
            && appliedLook.baseId.equals(recipe.baseId) && appliedLook.color == recipe.color
            && appliedLook.tone == recipe.tone && appliedLook.shadows == recipe.shadows
            && appliedLook.highlights == recipe.highlights;
    }

    private static void invalidateLook() { appliedLook = null; appliedEpoch = -1; }

    private static void setLook(String baseId) throws Exception {
        call(singleton(EFFECT), "setValue", STRINGS, new Object[] { "ApplicationTop", baseId });
    }

    /** Hook used before the inherited diagnostic observer, with 100% base coefficients. */
    public static int[] matrix(String baseId, int[] base) {
        Recipe recipe = current;
        if (recipe == null || !recipe.baseId.equals(baseId)) return base;
        try { return RecipeMath.matrix(base, recipe.isMonochrome() ? 100 : recipe.color); }
        catch (Throwable failure) { event("matrix_failed"); return null; }
    }

    public static byte[] gamma(String baseId, byte[] base) {
        Recipe recipe = current;
        if (recipe == null || !recipe.baseId.equals(baseId)) return base;
        try { return RecipeMath.gamma(base, recipe.tone, recipe.shadows, recipe.highlights); }
        catch (Throwable failure) { event("gamma_failed"); return null; }
    }

    /** The custom color/tone strengths are independent; source arrays must be 100%. */
    public static int strength(int inherited) { return current == null ? inherited : 100; }

    /** Localize app-owned recipe names; unrelated Sony menu labels keep their native path. */
    public static String displayName(String baseId, String fallback) {
        Recipe recipe = current;
        if (recipe != null && recipe.baseId.equals(baseId)) return currentName();
        String code = RecipeStore.baseCode(baseId);
        return "--".equals(code) ? fallback : RecipeStore.baseName(baseId, language());
    }

    /** Style only the app-name label, never Sony glyph/icon TextViews. */
    public static void styleAppName(Object container) {
        try {
            Field field = Class.forName("com.sony.imaging.app.base.shooting.widget.AppNameConstantView")
                .getDeclaredField("mTextView");
            field.setAccessible(true);
            TextView view = (TextView) field.get(container);
            FilmFonts.apply(view);
            appNames.add(new WeakReference<TextView>(view));
            refreshNames();
        } catch (Throwable unavailable) { event("name_label_unavailable"); }
    }

    public static void refreshNames() {
        String name = current == null ? "Film Lab" : "Film Lab  " + currentName();
        for (int i = appNames.size() - 1; i >= 0; i--) {
            TextView view = appNames.get(i).get();
            if (view == null) appNames.remove(i); else view.setText(name);
        }
    }

    private static String language() {
        Activity host = activity.get();
        return host == null ? "en" : host.getSharedPreferences("film_lab", 0).getString("language", "en");
    }

    private static String currentName() {
        if (current == null) return "Film Lab";
        if (!previewing && current.id == null)
            return "zh".equals(language()) ? "当前效果（未保存）" : "Current look (unsaved)";
        return current.displayName(language());
    }

    /** Inherited controller.setValue ignores applyHook's return, so observe it explicitly. */
    public static void applyResult(String baseId, boolean accepted) {
        if (requestBase != null && requestBase.equals(baseId)) {
            requestSeen = true;
            requestAccepted = accepted;
        } else invalidateLook();
    }

    public static String openNativeMenu(String itemId) {
        if (idleState == null) return "camera_not_ready";
        if (!allowedMenu(itemId)) return "menu_unsupported";
        String error = cancelPreview();
        if (error != null) return error;
        try {
            Bundle bundle = null;
            if (!"Main1".equals(itemId)) {
                bundle = new Bundle();
                bundle.putString("ItemId", itemId);
            }
            Object state = idleState;
            Activity host = activity.get();
            if (host != null) AppLanguage.applySaved(host);
            FilmLabUi.close();
            invalidateLook();
            DiagnosticsBridge.record("ui.action", "stage=open;screen=settings;source=native-camera;status=requested");
            call(state, "setNextState", new Class[] { String.class, Bundle.class }, new Object[] { "Menu", bundle });
            return null;
        } catch (Throwable failure) {
            FilmLabUi.open("settings");
            return "menu_unavailable";
        }
    }

    private static boolean allowedMenu(String id) {
        return "Main1".equals(id) || "FlashMode".equals(id) || "FlashCompensation".equals(id)
            || "WhiteBalance".equals(id) || "ExposureCompensation".equals(id) || "ExposureMode".equals(id)
            || "Iso".equals(id) || "Still_ImageSize".equals(id) || "Still_AspectRatio".equals(id)
            || "setPictureStorageFormat".equals(id) || "drivemode".equals(id)
            || "FocusSelection".equals(id) || "FocusArea".equals(id) || "MeteringMode".equals(id);
    }

    private static String wbMode(Recipe recipe) {
        if ("kelvin".equals(recipe.wb)) return "color-temp";
        return recipe.wb;
    }

    private static boolean supported(Object controller, String item, String value) throws Exception {
        Object values = call(controller, "getSupportedValue", STRING, new Object[] { item });
        return values instanceof List && ((List) values).contains(value);
    }

    private static WhiteBalance readWhiteBalance() throws Exception {
        // CameraSetting.getParameters returns Sony's maintained parameter pair;
        // it does not open a second camera or issue a hardware readback request.
        Object controller = singleton("com.sony.imaging.app.base.shooting.camera.CameraSetting");
        android.util.Pair pair = (android.util.Pair) call(controller, "getParameters", NONE, new Object[0]);
        // The semantic controller mode preserves Sony custom1/2/3 slot identity.
        String mode = (String) call(singleton(WB), "getValue", NONE, new Object[0]);
        Object detail = pair.second;
        return new WhiteBalance(mode,
            ((Integer) call(detail, "getLightBalanceForWhiteBalance", NONE, new Object[0])).intValue(),
            ((Integer) call(detail, "getColorCompensationForWhiteBalance", NONE, new Object[0])).intValue(),
            ((Integer) call(detail, "getColorTemperatureForWhiteBalance", NONE, new Object[0])).intValue());
    }

    private static void writeWhiteBalance(WhiteBalance value, boolean persistOptions, WhiteBalance actual) throws Exception {
        long started = System.nanoTime();
        try { writeWhiteBalanceInternal(value, persistOptions, actual); }
        finally { timing("wb_apply", started); }
    }

    private static void writeWhiteBalanceInternal(WhiteBalance value, boolean persistOptions, WhiteBalance actual) throws Exception {
        Object controller = singleton(WB);
        if (!value.mode.equals(actual.mode)) {
            call(controller, "setValue", STRINGS, new Object[] { "WhiteBalance", value.mode });
            // Sony loads that mode's saved options as part of setValue. Compare
            // its maintained cache again before submitting any detail change.
            actual = readWhiteBalance();
            if (!value.mode.equals(actual.mode)) throw new IllegalStateException("wb_mode_rejected");
        }
        boolean detailsChanged = !value.sameDetails(actual);
        if (!detailsChanged && !persistOptions) return;
        Object detail = Class.forName(WB + "$WhiteBalanceParam")
            .getConstructor(new Class[] { Integer.TYPE, Integer.TYPE, Integer.TYPE })
            .newInstance(new Object[] { Integer.valueOf(value.ab), Integer.valueOf(value.gm), Integer.valueOf(value.kelvin) });
        if (persistOptions) {
            if (detailsChanged) {
                call(controller, "setDetailValue", new Class[] { Object.class }, new Object[] { detail });
            } else if (!value.sameBackup(wbBackup(value.mode))) {
                // Use can promote an accepted preview without a second camera
                // write. Persist exactly the backup that setDetailValue writes.
                restoreWBBackup(value.mode, detail);
            }
        } else {
            // The same native validation/setter as setDetailValue, without saving
            // transient preview values to Sony's per-WB-mode preference keys.
            Object cameraSetting = singleton("com.sony.imaging.app.base.shooting.camera.CameraSetting");
            Object pair = call(cameraSetting, "getEmptyParameters", NONE, new Object[0]);
            Method setter = Class.forName(WB).getDeclaredMethod("setWBOption",
                new Class[] { android.util.Pair.class, detail.getClass() });
            setter.setAccessible(true);
            Object accepted = setter.invoke(controller, new Object[] { pair, detail });
            if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("wb_options_rejected");
            call(cameraSetting, "setParameters", new Class[] { android.util.Pair.class }, new Object[] { pair });
        }
    }

    private static Object wbBackup(String mode) throws Exception {
        Method method = Class.forName(WB).getDeclaredMethod("getWBOptionFromBackUp", STRING);
        method.setAccessible(true);
        return method.invoke(singleton(WB), new Object[] { mode });
    }

    private static void restoreWBBackup(String mode, Object value) throws Exception {
        Method method = Class.forName(WB).getDeclaredMethod("setWBOptionToBackUp",
            new Class[] { String.class, value.getClass() });
        method.setAccessible(true);
        method.invoke(singleton(WB), new Object[] { mode, value });
    }

    private static Object singleton(String className) throws Exception {
        return Class.forName(className).getMethod("getInstance", NONE).invoke(null, new Object[0]);
    }

    private static Object call(Object target, String name, Class[] types, Object[] args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }

    private static Recipe copy(Recipe recipe) { return recipe == null ? null : recipe.copy(); }
    private static void timing(String stage, long started) {
        DiagnosticsBridge.timing(stage, (System.nanoTime() - started) / 1000000L);
    }
    private static void event(String code) {
        String type = "recipe_previewed".equals(code) ? "preview.start"
            : code.indexOf("restore") >= 0 ? "recipe.restore" : "recipe.apply";
        String status = code.endsWith("failed") || code.endsWith("unavailable") ? "failed" : "accepted";
        DiagnosticsBridge.record(type, "code=" + code + ";status=" + status);
    }

    private static void publishActive() {
        if (committed == null) return;
        DiagnosticsBridge.activeRecipe(committed.id, committed.revision, committed.baseId);
        DiagnosticsBridge.checkpoint();
    }

    private static final class WhiteBalance {
        final String mode;
        final int ab, gm, kelvin;
        WhiteBalance(String mode, int ab, int gm, int kelvin) {
            this.mode = mode; this.ab = ab; this.gm = gm; this.kelvin = kelvin;
        }
        boolean sameDetails(WhiteBalance value) {
            return ab == value.ab && gm == value.gm && kelvin == value.kelvin;
        }
        boolean sameBackup(Object value) throws Exception {
            return ab == backupInt(value, "lightBalance")
                && gm == backupInt(value, "colorComp")
                && kelvin == backupInt(value, "colorTemp");
        }
        private static int backupInt(Object value, String name) throws Exception {
            Field field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(value);
        }
    }
}
