package com.filmlab.diagnostics;

import android.content.Context;
import android.hardware.Camera;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Pair;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Passive, best-effort observations. Never opens, configures or closes a camera. */
public final class DiagnosticsBridge {
    private static volatile ReportStore store;
    private static final Map<String, String> cached = new HashMap<String, String>();
    private static long requestedAt = -1L;
    private static long observedAt = -1L;
    private static long operation = 0L;
    private static long stateOrder = 0L;
    private static long requestedOrder = -1L;
    private static long observedOrder = -1L;
    private static int[] requestedMatrix;
    private static int[] observedMatrix;
    private static String observedSummary = "";
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static boolean handlerInstalled;
    private static boolean stateDirty;
    private static boolean cameraEvidence;
    private static long startupStartedAt = -1L;
    private static final Map<String, Long> earlyMilestones = new HashMap<String, Long>();
    private static final Map<String, long[]> earlyTimings = new HashMap<String, long[]>();

    private DiagnosticsBridge() { }

    public static synchronized void init(Context context) {
        if (store != null || context == null) return;
        long started = SystemClock.elapsedRealtime();
        if (startupStartedAt < 0L) startupStartedAt = started;
        try {
            store = new ReportStore(new File(context.getFilesDir(), "diagnostics"), new ReportStore.Clock() {
                public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
            }, true, startupStartedAt);
            for (Map.Entry<String, Long> entry : earlyMilestones.entrySet()) store.milestoneAt(entry.getKey(), entry.getValue().longValue());
            earlyMilestones.clear();
            for (Map.Entry<String, long[]> entry : earlyTimings.entrySet()) {
                long[] sample = entry.getValue();
                store.mergeTiming(entry.getKey(), sample[0], sample[1], sample[2], sample[3]);
            }
            earlyTimings.clear();
            cached.put("appVersion", BuildInfo.VERSION);
            cached.put("buildId", BuildInfo.BUILD_ID);
            cached.put("buildVariant", "native-camera");
            cached.put("baseAppSha256", BuildInfo.BASE_APK_SHA256);
            cached.put("sourceReleaseSha256", BuildInfo.SOURCE_RELEASE_SHA256);
            cached.put("upstreamCommit", BuildInfo.UPSTREAM_COMMIT);
            cached.put("model", scalarString("model.name"));
            cached.put("platformApi", scalarString("version.platform"));
            cached.put("firmware", scalarFirmware());
            cached.put("androidApi", Integer.toString(Build.VERSION.SDK_INT));
            cached.put("sessionMode", "unknown");
            cached.put("displayState", "unknown");
            cached.put("displaySource", "inherited_not_probed");
            cached.put("savedImageVerification", "not-verified");
            cached.put("captureStatus", "not-observed");
            cached.put("captureCapability", "not-probed");
            cached.put("readbackSource", "unavailable");
            cached.put("readbackStatus", "unavailable");
            cached.put("activeId", "unknown");
            // An accepted native preview request is not necessarily the committed look.
            store.record("session.open", "status=ready");
            installCrashHandler();
        } catch (Throwable ignored) { /* Diagnostics must not break the Sony path. */ }
        timing("startup_init", Math.max(0L, SystemClock.elapsedRealtime() - started));
    }

    /** Cheap memory-only aggregate. Stage names are a fixed privacy allowlist. */
    public static synchronized void timing(String stage, long durationMillis) {
        if (!ReportStore.knownTiming(stage) || durationMillis < 0L) return;
        if (store != null) store.timing(stage, durationMillis);
        else {
            long[] value = earlyTimings.get(stage);
            if (value == null) { value = new long[4]; earlyTimings.put(stage, value); }
            value[0] = ReportStore.saturatedAdd(value[0], 1L);
            value[1] = durationMillis;
            value[2] = Math.max(value[2], durationMillis);
            value[3] = ReportStore.saturatedAdd(value[3], durationMillis);
        }
    }

    /** First occurrence only, including milestones reached before Context init. */
    public static synchronized void milestone(String stage) {
        long now = SystemClock.elapsedRealtime();
        if (!ReportStore.knownMilestone(stage)) return;
        if (startupStartedAt < 0L) startupStartedAt = now;
        if (store != null) store.milestoneAt(stage, Math.max(0L, now - startupStartedAt));
        else if (!earlyMilestones.containsKey(stage)) earlyMilestones.put(stage, Long.valueOf(Math.max(0L, now - startupStartedAt)));
    }

    public static synchronized void flushAsync() {
        checkpoint();
        if (store != null) store.flushAsync();
    }

    /** Only a worker or controlled exit path may wait; never a camera key path. */
    public static boolean awaitFlush(long timeoutMillis) {
        ReportStore target;
        synchronized (DiagnosticsBridge.class) { target = store; }
        return target == null || target.awaitFlush(timeoutMillis);
    }

    private static void installCrashHandler() {
        if (handlerInstalled) return;
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                try { if (store != null) store.recordCrash(error); } catch (Throwable ignored) { }
                if (previousHandler != null) previousHandler.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            }
        });
        handlerInstalled = true;
    }

    private static String scalarString(String key) {
        try {
            Class<?> scalar = Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            Object value = scalar.getMethod("getString", new Class<?>[] { String.class }).invoke(null, new Object[] { key });
            return value instanceof String ? (String) value : "unknown";
        } catch (Throwable unavailable) { return "unknown"; }
    }

    private static String scalarFirmware() {
        try {
            Class<?> scalar = Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            Object value = scalar.getMethod("getFirmwareVersion", new Class<?>[0]).invoke(null, new Object[0]);
            return value instanceof String ? (String) value : "unknown";
        } catch (Throwable unavailable) { return "unknown"; }
    }

    public static synchronized void record(String event, String details) {
        try { if (store != null) store.record(event, details); } catch (Throwable ignored) { }
        if ("lifecycle.pause".equals(event) || "lifecycle.stop".equals(event) || "camera.close".equals(event)) flushAsync();
    }

    /** Call only after a recipe has actually been selected for the native engine.
     * Identifiers are filtered again by ReportStore; do not pass display names. */
    public static synchronized void activeRecipe(String id, int revision, String baseId) {
        cached.put("activeId", ReportStore.safeRecipeId(id));
        cached.put("activeRevision", Integer.toString(Math.max(0, revision)));
        cached.put("baseId", ReportStore.safeRecipeId(baseId));
        stateDirty = true;
    }

    public static synchronized void fontStatus(String status) {
        cached.put("fontStatus", "bundled-loaded".equals(status) || "bundled-failed".equals(status)
            || "system-fallback".equals(status) ? status : "unknown");
    }

    /** Coalesced private write request. No disk I/O, camera clock query or camera
     * parameters request occurs on the native preview/capture calling thread. */
    public static synchronized void checkpoint() {
        try {
            if (store != null && cameraEvidence && stateDirty) {
                Map<String, String> snapshot = cachedFacts("unknown", "unknown");
                // A prior report's camera clock sample is not this checkpoint's
                // timestamp. Keep its unavailable clock explicit; elapsed age
                // remains independently recorded by the persistence store.
                snapshot.put("cameraTime", "unknown");
                snapshot.put("cameraTimeSource", "unavailable");
                if (store.saveCameraState(snapshot)) stateDirty = false;
            }
        } catch (Throwable ignored) { }
    }

    public static synchronized Map<String, String> lastCameraState() {
        return store == null ? new HashMap<String, String>() : store.getLastCameraState();
    }

    /** Called with the actual array already selected by the upstream look engine. */
    public static synchronized void matrixRequested(String id, int[] matrix) {
        try {
            requestedAt = SystemClock.elapsedRealtime();
            cameraEvidence = true;
            stateDirty = true;
            requestedOrder = ++stateOrder;
            requestedMatrix = matrix == null ? null : (int[]) matrix.clone();
            cached.put("requestedId", ReportStore.safeRecipeId(id));
            cached.put("requestedMatrix", matrixText(requestedMatrix));
            cached.put("curveHash", "unknown");
            cached.put("curveSize", "unknown");
            cached.put("applyStatus", "requested");
            cached.put("operationId", "OP" + (++operation));
            cached.put("operationStage", "apply");
            cached.put("operationStatus", "requested");
            record("parameters.request", "id=" + id + ";operation_id=OP" + operation + ";status=requested");
        } catch (Throwable ignored) { }
    }

    public static synchronized void gammaRequested(String id, byte[] bytes) {
        try {
            if (bytes == null) return;
            cached.put("curveSize", Integer.toString(bytes.length / 2));
            cached.put("curveHash", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Throwable ignored) { }
    }

    public static synchronized void strengthObserved(int value) {
        try { cached.put("requestedStrength", Integer.toString(value)); } catch (Throwable ignored) { }
    }

    public static synchronized void applyResult(String id, boolean accepted) {
        try {
            cached.put("applyStatus", accepted ? "accepted" : "rejected");
            cached.put("operationStatus", accepted ? "accepted" : "failed");
            if (requestedAt >= 0) cached.put("operationDurationMs", Long.toString(Math.max(0L, SystemClock.elapsedRealtime() - requestedAt)));
            record(accepted ? "parameters.accepted" : "parameters.failed", "id=" + id + ";status=" + (accepted ? "accepted" : "failed"));
            checkpoint();
        } catch (Throwable ignored) { }
    }

    /** The native method has already fetched this Pair from Camera.getParameters.
     * Only inspect its parameter data; do not issue another camera request here. */
    public static synchronized void observeParameters(Object candidate) {
        try {
            if (!(candidate instanceof Pair)) return;
            Pair<?, ?> pair = (Pair<?, ?>) candidate;
            Object modifier = pair.second;
            if (modifier == null) return;
            Object rgb = invoke(modifier, "getRGBMatrix");
            observedMatrix = rgb instanceof int[] ? (int[]) ((int[]) rgb).clone() : null;
            cached.put("observedMatrix", matrixText(observedMatrix));
            cached.put("rgbSupport", support(modifier, "isRGBMatrixSupported"));
            cached.put("gammaSupport", support(modifier, "isExtendedGammaTableSupported"));
            cached.put("readbackSource", "native_camera_get_parameters");
            cached.put("readbackStatus", "observed");
            if (pair.first instanceof Camera.Parameters) {
                Camera.Parameters parameters = (Camera.Parameters) pair.first;
                cached.put("wb", whiteBalance(parameters.getWhiteBalance()));
            }
            Object flash = invoke(modifier, "getFlashType");
            cached.put("flashMode", flash instanceof String ? flashMode((String) flash) : "unknown");
            Object compensation = invoke(modifier, "getFlashCompensation");
            Object step = invoke(modifier, "getFlashCompensationStep");
            cached.put("flashCompensationEv", compensation instanceof Number && step instanceof Number
                ? String.format(Locale.US, "%.4f", Double.valueOf(((Number) compensation).doubleValue() * ((Number) step).doubleValue())) : "unknown");
            observedAt = SystemClock.elapsedRealtime();
            cameraEvidence = true;
            stateDirty = true;
            observedOrder = ++stateOrder;
            String summary = cached.get("observedMatrix") + "/" + cached.get("flashMode") + "/" + cached.get("flashCompensationEv") + "/" + cached.get("wb");
            if (!summary.equals(observedSummary)) {
                observedSummary = summary;
                record("parameters.readback", "source=native_camera_get_parameters;status=observed");
            }
        } catch (Throwable ignored) { cached.put("readbackStatus", "failed"); }
    }

    private static Object invoke(Object target, String name) {
        try { return target.getClass().getMethod(name, new Class<?>[0]).invoke(target, new Object[0]); }
        catch (Throwable unavailable) { return null; }
    }

    private static String support(Object modifier, String methodName) {
        try {
            Method method = modifier.getClass().getMethod(methodName, new Class<?>[0]);
            Object result = method.invoke(modifier, new Object[0]);
            return result instanceof Boolean ? (((Boolean) result).booleanValue() ? "supported" : "unsupported") : "unknown";
        } catch (NoSuchMethodException missing) { return "missing"; }
        catch (Throwable failed) { return "error"; }
    }

    private static String whiteBalance(String value) {
        if ("auto".equals(value)) return "auto";
        // Preserve a useful fixed vocabulary without collecting arbitrary strings.
        if ("daylight".equals(value) || "cloudy-daylight".equals(value) || "incandescent".equals(value)
            || "fluorescent".equals(value) || "shade".equals(value) || "flash".equals(value)) return value;
        return "unknown";
    }

    private static String flashMode(String value) {
        if ("front-sync".equals(value)) return "front";
        if ("slow-sync".equals(value)) return "slow";
        if ("rear-sync".equals(value)) return "rear";
        if ("off".equals(value) || "auto".equals(value) || "wireless".equals(value) || "slow-rear-sync".equals(value)) return value;
        return "unknown";
    }

    public static synchronized void cameraError(int code) {
        try {
            cached.put("lastErrorCode", Integer.toString(code));
            cameraEvidence = true;
            stateDirty = true;
            record("camera.error", "code=" + code + ";status=failed");
            checkpoint();
        } catch (Throwable ignored) { }
    }

    public static synchronized void captureRequested(int shutterType) {
        try {
            cached.put("captureStatus", "requested");
            cameraEvidence = true;
            stateDirty = true;
            cached.put("captureCallback", "not-observed");
            record("capture.request", "status=requested;code=" + shutterType);
            checkpoint();
        } catch (Throwable ignored) { }
    }

    public static synchronized void shutterCallback(int result) {
        try {
            cached.put("captureStatus", result == 0 ? "callback" : "failed");
            cameraEvidence = true;
            stateDirty = true;
            cached.put("captureCallback", "callback");
            record("capture.callback", "status=" + (result == 0 ? "observed" : "failed") + ";code=" + result);
            checkpoint();
        } catch (Throwable ignored) { }
    }

    public static synchronized Map<String, String> facts(String screen, String language) {
        refreshCameraTime();
        return cachedFacts(screen, language);
    }

    private static Map<String, String> cachedFacts(String screen, String language) {
        Map<String, String> result = new HashMap<String, String>(cached);
        result.put("screen", screen);
        result.put("language", language);
        result.put("readbackAgeMs", observedAt < 0 ? "unknown" : Long.toString(Math.max(0L, SystemClock.elapsedRealtime() - observedAt)));
        boolean after = observedOrder >= 0 && requestedOrder >= 0 && observedOrder > requestedOrder;
        result.put("observedAfterRequest", requestedAt < 0 || observedAt < 0 ? "unknown" : Boolean.toString(after));
        result.put("matrixMatchesRequest", !after || observedMatrix == null || requestedMatrix == null ? "unknown" : Boolean.toString(Arrays.equals(requestedMatrix, observedMatrix)));
        if (observedAt >= 0 && requestedAt >= 0 && !after) result.put("readbackStatus", "cached_before_request");
        if (store != null) {
            Map<String, String> saved = store.getLastCameraState();
            result.put("lastStateStatus", saved.get("lastStateStatus"));
            result.put("lastStateSession", saved.get("lastStateSession"));
            result.put("lastStateAgeMs", saved.get("lastStateAgeMs"));
        }
        return result;
    }

    /** Sony's camera clock is distinct from Android's often unset epoch clock.
     * These public fields/signatures match the inherited ScalarCalendar helper.
     * A valid result is camera-set time, never an independently verified date. */
    private static void refreshCameraTime() {
        cached.put("cameraTime", "unknown");
        cached.put("cameraTimeSource", "unavailable");
        try {
            Class<?> time = Class.forName("com.sony.scalar.sysutil.TimeUtil");
            Object plain = time.getMethod("getCurrentCalendar", new Class<?>[0]).invoke(null, new Object[0]);
            Object zone = time.getMethod("getCurrentTimeZone", new Class<?>[0]).invoke(null, new Object[0]);
            int year = intField(plain, "year");
            int offset = intField(zone, "gmtDiff") + intField(zone, "summerTimeDiff");
            if (year < 2000 || year > 2099 || offset < -840 || offset > 840) return;
            GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.setLenient(false);
            calendar.clear();
            calendar.set(year, intField(plain, "month") - 1, intField(plain, "day"),
                intField(plain, "hour"), intField(plain, "minute"), intField(plain, "second"));
            long utcMillis = calendar.getTimeInMillis() - offset * 60000L;
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            cached.put("cameraTime", format.format(new java.util.Date(utcMillis)));
            cached.put("cameraTimeSource", "sony-timeutil");
        } catch (Throwable unavailable) { /* Leave unavailable, not Android 1970. */ }
    }

    private static int intField(Object value, String field) throws Exception {
        return value.getClass().getField(field).getInt(value);
    }

    public static synchronized String report(String screen, String language) {
        if (store == null) throw new IllegalStateException("Diagnostic store unavailable");
        return store.createReport(facts(screen, language));
    }

    public static synchronized String recentEvents() { return store == null ? "unavailable" : store.getRecentEvents(); }

    /** Invoked by the UI's worker thread; does not hold the camera-state lock. */
    public static File export(String snapshot) throws IOException {
        ReportStore target;
        boolean attempted = false;
        synchronized (DiagnosticsBridge.class) { target = store; }
        try {
            if (target == null) throw new IOException("STORE_UNAVAILABLE");
            // Snapshot text stays immutable. A bounded flush improves crash/
            // restart evidence without blocking camera event hooks or card I/O.
            target.awaitFlush(1500L);
            String state = Environment.getExternalStorageState();
            if (Environment.MEDIA_REMOVED.equals(state) || Environment.MEDIA_BAD_REMOVAL.equals(state)) {
                throw new ReportStore.ExportException("NO_CARD");
            }
            if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                throw new ReportStore.ExportException("CARD_NOT_WRITABLE");
            }
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                throw new ReportStore.ExportException("STORAGE_UNAVAILABLE");
            }
            File root = Environment.getExternalStorageDirectory();
            attempted = true;
            File output = target.export(root, snapshot);
            synchronized (DiagnosticsBridge.class) { cached.put("storageStatus", "verified_export"); }
            return output;
        } catch (IOException failure) {
            String code = failure instanceof ReportStore.ExportException
                ? ((ReportStore.ExportException) failure).getCode() : "EXPORT_IO_FAILED";
            exportFailed("NO_CARD".equals(code) ? "no-card"
                : "STORAGE_UNAVAILABLE".equals(code) ? "unavailable" : "failed", !attempted);
            throw failure;
        } catch (RuntimeException failure) {
            exportFailed("failed", !attempted);
            throw failure;
        }
    }

    private static synchronized void exportFailed(String status, boolean logFailure) {
        cached.put("storageStatus", status);
        if (logFailure) record("report.failure", "status=" + status + ";stage=export");
    }

    private static String matrixText(int[] matrix) {
        if (matrix == null || matrix.length != 9) return "unknown";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < matrix.length; index++) { if (index > 0) result.append(','); result.append(matrix[index]); }
        return result.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < bytes.length; index++) result.append(String.format(Locale.US, "%02x", Integer.valueOf(bytes[index] & 255)));
        return result.toString();
    }
}
