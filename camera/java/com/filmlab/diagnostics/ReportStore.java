package com.filmlab.diagnostics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App-owned diagnostics only: no Android imports, camera calls, network access,
 * raw exception messages, user recipe names, or arbitrary filesystem dumps.
 * Caller owns its immutable report text and can retry export without rebuilding.
 * Successful export means a closed file passed immediate readback, not durable
 * storage across power loss or verified USB visibility.
 */
public class ReportStore {
    public static final String HEADER = "FILM LAB CAMERA DIAGNOSTIC REPORT v1";
    public static final int MAX_REPORT_BYTES = 256 * 1024;
    public static final int MAX_LOG_BYTES = 64 * 1024;
    public static final int MAX_CRASH_BYTES = 8 * 1024;
    public static final int MAX_STATE_BYTES = 16 * 1024;
    private static final int MAX_EVENTS = 256;
    private static final long FLUSH_DELAY_MS = 250L;
    private static final String[] TIMING_KEYS = { "ui_navigation", "ui_render", "preview_queue", "look_apply", "wb_apply", "recipe_apply", "preview_apply", "restore_apply", "diagnostic_flush", "font_load", "startup_init" };
    private static final String[] MILESTONE_KEYS = { "ui_attached", "first_menu_layout", "first_idle", "active_restore_done", "native_on_create", "native_on_boot" };
    private static final String UTF8 = "UTF-8";
    private static final String CRASH_HEADER = "FILMLAB_JAVA_CRASH_V1";
    private static final String STATE_HEADER = "FILMLAB_CAMERA_STATE_V1";
    private static final String[] FACT_KEYS = {
        "appVersion", "buildCommit", "buildId", "buildVariant", "baseAppSha256", "sourceReleaseSha256", "upstreamCommit", "generatorVersion",
        "model", "firmware", "platformApi", "androidApi", "cameraTime", "cameraTimeSource", "fontStatus",
        "screen", "language", "sessionMode", "displayState", "displaySource",
        "activeId", "activeRevision", "savedRevision", "highlightedId", "highlightedRevision",
        "draftId", "draftRevision", "unsavedDraft", "baseId", "baseRevision",
        "requestedId", "requestedStrength", "applyStatus",
        "color", "tone", "shadows", "highlights", "wb", "kelvin", "ab", "gm",
        "requestedMatrix", "curveSize", "curveHash", "readbackSource", "readbackAgeMs",
        "readbackStatus", "observedMatrix", "observedCurveHash", "savedImageVerification",
        "observedAfterRequest", "matrixMatchesRequest", "rgbSupport", "gammaSupport", "lastErrorCode",
        "capabilityStatus", "matrixCapability", "curveCapability", "captureCapability",
        "flashMode", "flashCompensationEv", "flashAccessory", "flashReadiness",
        "captureStatus", "captureCallback", "quality", "iso", "exposureMode",
        "operationId", "operationStage", "operationStatus", "operationDurationMs",
        "storageStatus", "storageFreeBytes", "lastStateStatus", "lastStateSession", "lastStateAgeMs"
    };
    // Deliberately narrower than FACT_KEYS: no screen, language, storage path,
    // arbitrary caller keys or recipe names are persisted in camera evidence.
    private static final String[] STATE_KEYS = {
        "appVersion", "buildId", "model", "firmware", "cameraTime", "cameraTimeSource",
        "activeId", "activeRevision", "baseId", "requestedId", "requestedStrength", "applyStatus",
        "requestedMatrix", "curveSize", "curveHash", "readbackSource", "readbackStatus",
        "observedMatrix", "observedAfterRequest", "matrixMatchesRequest", "rgbSupport", "gammaSupport",
        "wb", "flashMode", "flashCompensationEv", "captureStatus", "captureCallback", "lastErrorCode",
        "savedImageVerification", "operationId", "operationStage", "operationStatus", "operationDurationMs",
        "readbackAgeAtCheckpointMs"
    };
    private static final Set<String> BASE_IDS = words("neutral classic-chrome portrait negative slide cinema acros deep-mono pop-color fuji-velvia fuji-astia fuji-chrome fuji-reala fuji-proneg fuji-negative fuji-eterna fuji-bleach fuji-acros ricoh-positive ricoh-negative ricoh-hcbw ricoh-daido ricoh-cross");
    private static final Set<String> EVENTS = words("session.open lifecycle.resume lifecycle.pause lifecycle.stop camera.open camera.close camera.error preview.start preview.stop parameters.request parameters.accepted parameters.readback parameters.failed capture.request capture.callback capture.timeout recipe.apply recipe.restore storage.check report.create report.export report.failure java.crash ui.action key.event other");
    private static final Set<String> ERROR_CODES = words("attach_failed restore_failed recipe_applied recipe_previewed recipe_apply_failed recipe_restore_failed matrix_failed gamma_failed name_label_unavailable camera_not_ready white_balance_unavailable white_balance_unsupported ab_unsupported gm_unsupported kelvin_unsupported recipe_invalid recipe_missing restore_unavailable white_balance_restore_failed menu_unsupported menu_unavailable");
    private static final Set<String> CODES = words("unknown unavailable untested unsupported missing failed busy cached requested accepted verified not-verified not-probed not-observed not-read not-set not-run not-applicable no-card mounted writable read-only present absent none pending success ready open close capture apply restore export create read write finalize verify launch pause resume shoot library detail editor settings index flash language report about licenses developer device processing keys events still movie auto kelvin front slow rear wireless front-sync slow-sync slow-rear-sync rear-sync off on en zh lcd evf true false info clean histogram level browser-simulation camera-api app-cache camera-diagnostics app-diagnostics-only diagnostics minimal-camera native-camera java-runtime working-tree debug release system-clock sony-timeutil callback timeout cancelled up down left right center menu fn disp half shutter press repeat printable tab other raw rawjpeg jpeg fine standard P A S M color tone shadows highlights wb ab gm baseId name daylight cloudy-daylight incandescent fluorescent shade native_camera_get_parameters inherited_not_probed observed cached_before_request rejected error supported external_storage_mounted verified_export pair_getter not_instrumented current-session previous-session persisted-snapshot bundled-loaded bundled-failed system-fallback playback");
    private static final Pattern STORED_EVENT = Pattern.compile("^session=(S[0-9a-f]{1,16}) elapsed_ms=([0-9]{1,18}) seq=([0-9]{1,18}) event=([a-z.]+) detail=(.*)$");

    public interface Clock { long elapsedMillis(); }

    public static final class ExportException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;
        public ExportException(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }

    private final File privateDir;
    private final Object exportLock = new Object();
    private final Object privateIoLock = new Object();
    private final Object crashIoLock = new Object();
    private final Clock clock;
    private final String clockSource;
    private final long clockStart;
    private final String sessionId;
    private final ArrayList<String> events = new ArrayList<String>();
    private String previousEvents = "";
    private String crashSnapshot = "";
    private int memoryBytes;
    private long sequence;
    private long lastElapsed;
    private long droppedEvents;
    private long privateWriteFailures;
    private long filteredPreviousLines;
    private boolean previousTailLimited;
    private int reportSequence;
    private Map<String, String> savedState = new HashMap<String, String>();
    private String savedStateSession = "unknown";
    private long savedStateElapsed = -1L;
    private final boolean asynchronous;
    private final ArrayList<String> pendingEvents = new ArrayList<String>();
    private int pendingBytes;
    private long pendingDroppedEvents;
    private long coalescedCheckpoints;
    private long pendingGeneration;
    private long completedGeneration;
    private long failedGeneration = -1L;
    private long dirtyAtNanos;
    private boolean flushRequested;
    private boolean historyLoaded;
    private StateSnapshot pendingState;
    private final Map<String, long[]> timings = new HashMap<String, long[]>();
    private final Map<String, Long> milestones = new HashMap<String, Long>();

    private static final class StateSnapshot {
        final Map<String, String> facts;
        final long elapsed;
        final String text;
        StateSnapshot(Map<String, String> facts, long elapsed, String text) {
            this.facts = facts; this.elapsed = elapsed; this.text = text;
        }
    }

    public ReportStore(File privateDir) {
        this(privateDir, new Clock() {
            public long elapsedMillis() { return System.nanoTime() / 1000000L; }
        }, "System.nanoTime", false, -1L);
    }

    public ReportStore(File privateDir, Clock clock) {
        this(privateDir, clock, "caller-provided-monotonic-clock", false, -1L);
    }

    /** Production mode defers all routine private I/O to one bounded worker. */
    public ReportStore(File privateDir, Clock clock, boolean asynchronous, long startMillis) {
        this(privateDir, clock, "caller-provided-monotonic-clock", asynchronous, startMillis);
    }

    private ReportStore(File privateDir, Clock clock, String clockSource, boolean asynchronous, long startMillis) {
        if (clock == null) throw new IllegalArgumentException("Clock is required");
        this.privateDir = privateDir;
        this.clock = clock;
        this.clockSource = clockSource;
        this.clockStart = startMillis < 0L ? clock.elapsedMillis() : startMillis;
        this.sessionId = "S" + Long.toHexString(System.nanoTime());
        this.asynchronous = asynchronous;
        if (asynchronous) {
            Thread worker = new Thread(new Runnable() { public void run() { persistenceLoop(); } }, "FilmLab-diagnostics");
            worker.setDaemon(true);
            worker.start();
        } else loadHistory();
    }

    private void loadHistory() {
        if (privateReady()) {
            String previous = loadEvents(new File(privateDir, "APPLOG1.TXT")) + loadEvents(new File(privateDir, "APPLOG.TXT"));
            String crash = loadCrash(new File(privateDir, "CRASH.TXT"));
            if (!loadState(new File(privateDir, "CAMERA.TXT"))) loadState(new File(privateDir, "CAMERA1.TXT"));
            synchronized (this) {
                previousEvents = previous;
                if (crashSnapshot.length() == 0) crashSnapshot = crash;
            }
        }
        synchronized (this) { historyLoaded = true; notifyAll(); }
    }

    private static Set<String> words(String value) {
        Set<String> result = new HashSet<String>();
        String[] items = value.split(" ");
        for (int index = 0; index < items.length; index++) result.add(items[index]);
        return result;
    }

    private static byte[] bytes(String value) throws IOException { return value.getBytes(UTF8); }
    private static int size(String value) {
        try { return bytes(value).length; } catch (IOException impossible) { throw new IllegalStateException("UTF-8 unavailable"); }
    }
    private static String code(String value) { return value != null && CODES.contains(value) ? value : "unknown"; }
    private static String numeric(String value) {
        return value != null && value.matches("[+-]?[0-9]{1,15}(\\.[0-9]{1,6})?") ? value : "unknown";
    }
    private static String hash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}|[0-9a-fA-F]{40}|[0-9a-fA-F]{64}") ? value.toLowerCase(Locale.US) : "unknown";
    }
    private static String recipeId(String value) {
        if (value == null || value.length() == 0 || "unknown".equals(value)) return "unknown";
        if (BASE_IDS.contains(value) || "none".equals(value) || "unsaved".equals(value)) return value;
        if (value.matches("recipe-[0-9a-f]{12}")) return value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(UTF8));
            StringBuilder output = new StringBuilder("recipe-");
            for (int index = 0; index < 6; index++) output.append(String.format(Locale.US, "%02x", Integer.valueOf(hashed[index] & 255)));
            return output.toString();
        } catch (Exception error) { return "redacted-id"; }
    }
    /** Safe identifier for on-screen developer facts as well as report files. */
    public static String safeRecipeId(String value) {
        return value != null && value.length() > 256 ? "unknown" : recipeId(value);
    }
    private static String matrix(String value) {
        if (value == null || value.length() > 180) return "unknown";
        String[] values = value.trim().split("[,\\s]+");
        if (values.length != 9) return "unknown";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (!values[index].matches("[+-]?[0-9]{1,7}(\\.[0-9]{1,6})?")) return "unknown";
            if (index > 0) result.append(',');
            result.append(values[index]);
        }
        return result.toString();
    }
    private static String fact(String key, String value) {
        if (value == null || value.length() > 256 || "unknown".equals(value) || "unavailable".equals(value)) return value != null && "unavailable".equals(value) ? "unavailable" : "unknown";
        if ("buildId".equals(key)) return value.matches("[0-9a-fA-F]{7,64}") ? value.toLowerCase(Locale.US) : "unknown";
        if ("lastStateSession".equals(key)) return value.matches("S[0-9a-f]{1,16}") ? value : "unknown";
        if (key.endsWith("Id") && !"operationId".equals(key)) return recipeId(value);
        if ("operationId".equals(key)) return value.matches("OP[0-9]{1,12}") ? value : "unknown";
        if ("buildCommit".equals(key) || "upstreamCommit".equals(key)) return value.matches("[0-9a-fA-F]{40}") ? value.toLowerCase(Locale.US) : code(value);
        if ("baseAppSha256".equals(key) || "sourceReleaseSha256".equals(key)) return value.matches("[0-9a-fA-F]{64}") ? value.toLowerCase(Locale.US) : "unknown";
        if ("requestedStrength".equals(key)) return "30".equals(value) || "50".equals(value) || "70".equals(value) || "100".equals(value) ? value : "unknown";
        if ("curveHash".equals(key) || "observedCurveHash".equals(key)) return hash(value);
        if ("requestedMatrix".equals(key) || "observedMatrix".equals(key)) return matrix(value);
        if ("appVersion".equals(key) || "generatorVersion".equals(key)) return value.matches("[0-9]{1,4}(\\.[0-9]{1,4}){0,3}(-(dev|alpha|beta|rc|diagnostics|camera)([.-][0-9]{1,6})?)?") ? value : "unknown";
        if ("model".equals(key)) return value.matches("ILCE-[A-Z0-9]{1,12}") ? value : "unknown";
        if ("firmware".equals(key) || "platformApi".equals(key)) return value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}") ? value : "unknown";
        if ("cameraTime".equals(key)) return validUtc(value) ? value : "unknown";
        if (key.endsWith("Revision") || key.endsWith("Ms") || key.endsWith("Bytes") || "androidApi".equals(key) || "curveSize".equals(key) || "color".equals(key) || "tone".equals(key) || "shadows".equals(key) || "highlights".equals(key) || "kelvin".equals(key) || "ab".equals(key) || "gm".equals(key) || "iso".equals(key) || "flashCompensationEv".equals(key) || "lastErrorCode".equals(key)) return numeric(value);
        return code(value);
    }

    private static boolean validUtc(String value) {
        if (value == null || !value.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try { return format.format(format.parse(value)).equals(value); }
        catch (java.text.ParseException invalid) { return false; }
    }

    /** Detail is structured key=value;key=value, never free-form prose. */
    private static String detail(String input) {
        if (input == null || input.length() == 0) return "none";
        if (input.length() > 2048) return "redacted=true";
        String[] parts = input.split(";");
        StringBuilder result = new StringBuilder();
        boolean redacted = false;
        for (int index = 0; index < parts.length && index < 16; index++) {
            String part = parts[index].trim();
            int split = part.indexOf('=');
            if (split < 1) { if ("none".equals(part)) continue; redacted = true; continue; }
            String key = part.substring(0, split).trim();
            String value = part.substring(split + 1).trim();
            String safe = null;
            if ("status".equals(key) || "stage".equals(key) || "screen".equals(key) || "language".equals(key) || "display_mode".equals(key) || "field".equals(key) || "mode".equals(key) || "source".equals(key) || "phase".equals(key) || "control".equals(key) || "repeat".equals(key) || "redacted".equals(key)) safe = code(value);
            else if ("code".equals(key)) safe = ERROR_CODES.contains(value) ? value : numeric(value);
            else if ("duration_ms".equals(key) || "revision".equals(key) || "number".equals(key)) safe = numeric(value);
            else if ("key_code".equals(key) || "scan_code".equals(key) || "repeat_count".equals(key)) safe = value.matches("-?[0-9]{1,10}") ? value : "unknown";
            else if ("operation_id".equals(key)) safe = value.matches("OP[0-9]{1,12}") ? value : "unknown";
            else if ("id".equals(key)) safe = recipeId(value);
            if (safe == null || "unknown".equals(safe)) { redacted = true; continue; }
            if (result.length() > 0) result.append(';');
            result.append(key).append('=').append(safe);
        }
        if (parts.length > 16) redacted = true;
        if (redacted) { if (result.length() > 0) result.append(';'); result.append("redacted=true"); }
        return result.length() == 0 ? "none" : result.toString();
    }

    public synchronized void record(String event, String details) {
        String safeEvent = event != null && EVENTS.contains(event) ? event : "other";
        long elapsed = clock.elapsedMillis() - clockStart;
        lastElapsed = Math.max(lastElapsed, Math.max(0L, elapsed));
        String line = "session=" + sessionId + " elapsed_ms=" + lastElapsed + " seq=" + (++sequence) + " event=" + safeEvent + " detail=" + detail(details) + "\n";
        int length = size(line);
        while (!events.isEmpty() && (events.size() >= MAX_EVENTS || memoryBytes + length > MAX_LOG_BYTES)) {
            memoryBytes -= size(events.remove(0)); droppedEvents++;
        }
        events.add(line); memoryBytes += length;
        if (asynchronous) {
            while (!pendingEvents.isEmpty() && (pendingEvents.size() >= MAX_EVENTS || pendingBytes + length > MAX_LOG_BYTES)) {
                pendingBytes -= size(pendingEvents.remove(0)); pendingDroppedEvents++;
            }
            pendingEvents.add(line); pendingBytes += length;
            changed();
        } else appendPrivate(line);
    }

    private void changed() {
        if (pendingGeneration == completedGeneration) dirtyAtNanos = System.nanoTime();
        pendingGeneration++;
        notifyAll();
    }

    public synchronized void flushAsync() {
        flushRequested = true;
        notifyAll();
    }

    /** Worker/export/exit paths only. UI navigation must never wait for disk. */
    public synchronized boolean awaitFlush(long timeoutMillis) {
        if (!asynchronous) return true;
        long target = pendingGeneration;
        long limit = System.nanoTime() + Math.max(0L, Math.min(10000L, timeoutMillis)) * 1000000L;
        flushAsync();
        while (!historyLoaded || completedGeneration < target) {
            long remaining = limit - System.nanoTime();
            if (remaining <= 0L) return false;
            try { wait(Math.max(1L, remaining / 1000000L)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return failedGeneration < target;
    }

    private void persistenceLoop() {
        try { synchronized (privateIoLock) { loadHistory(); } }
        catch (Throwable failure) { failedWrite(); synchronized (this) { historyLoaded = true; notifyAll(); } }
        for (;;) {
            String batch;
            StateSnapshot checkpoint;
            long generation;
            synchronized (this) {
                while (pendingEvents.isEmpty() && pendingState == null) {
                    flushRequested = false;
                    try { wait(); } catch (InterruptedException ignored) { }
                }
                long delay = FLUSH_DELAY_MS - (System.nanoTime() - dirtyAtNanos) / 1000000L;
                if (!flushRequested && delay > 0L) {
                    try { wait(delay); } catch (InterruptedException ignored) { }
                    continue;
                }
                StringBuilder lines = new StringBuilder(pendingBytes);
                for (int index = 0; index < pendingEvents.size(); index++) lines.append(pendingEvents.get(index));
                batch = lines.toString();
                pendingEvents.clear(); pendingBytes = 0;
                checkpoint = pendingState; pendingState = null;
                generation = pendingGeneration;
                flushRequested = false;
            }
            long started = System.nanoTime();
            long failuresBefore;
            synchronized (this) { failuresBefore = privateWriteFailures; }
            try {
                synchronized (privateIoLock) {
                    if (batch.length() > 0) appendPrivate(batch);
                    if (checkpoint != null) persistCameraState(checkpoint);
                }
            } catch (Throwable failure) { failedWrite(); }
            synchronized (this) {
                completedGeneration = generation;
                if (privateWriteFailures > failuresBefore) failedGeneration = generation;
                // Work arriving during I/O receives a fresh batching interval.
                if (pendingGeneration > generation) dirtyAtNanos = System.nanoTime();
                timing("diagnostic_flush", Math.max(0L, (System.nanoTime() - started) / 1000000L));
                notifyAll();
            }
        }
    }

    private synchronized void failedWrite() { privateWriteFailures++; }

    public static boolean knownTiming(String stage) { return contains(TIMING_KEYS, stage); }
    public static boolean knownMilestone(String stage) { return contains(MILESTONE_KEYS, stage); }
    private static boolean contains(String[] values, String value) {
        for (int index = 0; index < values.length; index++) if (values[index].equals(value)) return true;
        return false;
    }
    /** Fixed vocabulary and bounded totals; never appends a log event or writes. */
    public synchronized void timing(String stage, long durationMillis) {
        if (!knownTiming(stage) || durationMillis < 0L) return;
        mergeTiming(stage, 1L, durationMillis, durationMillis, durationMillis);
    }
    synchronized void mergeTiming(String stage, long count, long last, long maximum, long total) {
        if (!knownTiming(stage) || count < 1L || last < 0L || maximum < 0L || total < 0L) return;
        long[] value = timings.get(stage);
        if (value == null) { value = new long[4]; timings.put(stage, value); }
        value[0] = saturatedAdd(value[0], count);
        value[1] = last;
        value[2] = Math.max(value[2], maximum);
        value[3] = saturatedAdd(value[3], total);
    }
    static long saturatedAdd(long left, long right) { return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right; }
    public synchronized void milestone(String stage) {
        milestoneAt(stage, Math.max(0L, clock.elapsedMillis() - clockStart));
    }
    /** Elapsed time since this process's initial native bootstrap, not wall time. */
    public synchronized void milestoneAt(String stage, long elapsedMillis) {
        if (knownMilestone(stage) && elapsedMillis >= 0L && !milestones.containsKey(stage)) milestones.put(stage, Long.valueOf(elapsedMillis));
    }

    public synchronized String getRecentEvents() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < events.size(); index++) result.append(events.get(index));
        return result.toString();
    }

    private boolean privateReady() {
        try { return privateDir != null && (privateDir.isDirectory() || (!privateDir.exists() && (privateDir.mkdirs() || privateDir.isDirectory()))); }
        catch (SecurityException failure) { return false; }
    }
    private void appendPrivate(String line) {
        try {
            if (!privateReady()) throw new IOException("PRIVATE_UNAVAILABLE");
            File current = new File(privateDir, "APPLOG.TXT");
            if (current.length() + size(line) > MAX_LOG_BYTES) {
                File older = new File(privateDir, "APPLOG1.TXT");
                if (older.exists() && !older.delete()) throw new IOException("ROTATION_FAILED");
                if (current.exists() && !current.renameTo(older)) throw new IOException("ROTATION_FAILED");
            }
            FileOutputStream output = new FileOutputStream(current, true);
            try { output.write(bytes(line)); } finally { output.close(); }
        } catch (IOException failure) { failedWrite(); }
        catch (SecurityException failure) { failedWrite(); }
    }
    private String loadEvents(File file) {
        try {
            String text = readTail(file, MAX_LOG_BYTES);
            String[] lines = text.split("\n");
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < lines.length; index++) {
                if (lines[index].length() == 0) continue;
                Matcher match = STORED_EVENT.matcher(lines[index]);
                if (!match.matches() || !EVENTS.contains(match.group(4))) { synchronized (this) { filteredPreviousLines++; } continue; }
                result.append("session=").append(match.group(1)).append(" elapsed_ms=").append(match.group(2)).append(" seq=").append(match.group(3)).append(" event=").append(match.group(4)).append(" detail=").append(detail(match.group(5))).append('\n');
            }
            return result.toString();
        } catch (IOException failure) { failedWrite(); return ""; }
        catch (SecurityException failure) { failedWrite(); return ""; }
    }
    private String readTail(File file, int limit) throws IOException {
        if (!file.exists()) return "";
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            long skip = Math.max(0L, input.length() - limit);
            synchronized (this) { previousTailLimited = previousTailLimited || skip > 0; }
            input.seek(skip);
            byte[] buffer = new byte[(int) Math.min((long) limit, input.length())];
            input.readFully(buffer);
            String result = new String(buffer, UTF8);
            if (skip > 0) { int newline = result.indexOf('\n'); return newline < 0 ? "" : result.substring(newline + 1); }
            return result;
        } finally { input.close(); }
    }

    /** Best effort, app-private only. Caller must delegate its original handler. */
    public void recordCrash(Throwable error) {
        StringBuilder result = new StringBuilder(CRASH_HEADER).append('\n').append("sessionId=").append(sessionId).append('\n');
        Throwable current = error;
        boolean truncated = false;
        causes:
        for (int cause = 0; current != null && cause < 3; cause++) {
            String exceptionLine = "exceptionClass=" + safeClass(current.getClass().getName()) + "\n";
            if (size(result.toString()) + size(exceptionLine) >= MAX_CRASH_BYTES - 64) { truncated = true; break; }
            result.append(exceptionLine);
            StackTraceElement[] frames = current.getStackTrace();
            if (frames.length > 24) truncated = true;
            for (int index = 0; index < frames.length && index < 24; index++) {
                String method = frames[index].getMethodName();
                if (method == null || !method.matches("[A-Za-z_$<>][A-Za-z0-9_$<>]{0,63}")) method = "unknown";
                String frame = "frame=" + safeClass(frames[index].getClassName()) + "#" + method + ":" + frames[index].getLineNumber() + "\n";
                if (size(result.toString()) + size(frame) >= MAX_CRASH_BYTES - 64) { truncated = true; break causes; }
                result.append(frame);
            }
            current = current.getCause();
        }
        if (current != null) truncated = true;
        result.append("truncated=").append(truncated).append('\n');
        String snapshot = result.toString();
        synchronized (this) { crashSnapshot = snapshot; }
        // Crash recovery bypasses the batched worker because Sony may terminate
        // the process immediately. CRASH.TXT is independent of log/state files.
        try {
            if (!privateReady()) throw new IOException("PRIVATE_UNAVAILABLE");
            synchronized (crashIoLock) { writeFile(new File(privateDir, "CRASH.TXT"), bytes(snapshot)); }
        } catch (IOException failure) { failedWrite(); }
        catch (SecurityException failure) { failedWrite(); }
    }
    private static String safeClass(String value) {
        return value != null && value.matches("(com\\.filmlab\\.|com\\.sony\\.|android\\.|java\\.|javax\\.|dalvik\\.)[A-Za-z0-9_$.]{1,150}") ? value : "external-class";
    }
    private String loadCrash(File file) {
        try {
            if (!file.exists() || file.length() > MAX_CRASH_BYTES) return "";
            String text = readTail(file, MAX_CRASH_BYTES);
            if (!text.startsWith(CRASH_HEADER + "\n")) return "";
            StringBuilder safe = new StringBuilder(CRASH_HEADER).append('\n');
            String[] lines = text.split("\n");
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                if (line.matches("sessionId=S[0-9a-f]{1,16}")) safe.append(line).append('\n');
                else if (line.matches("truncated=(true|false)")) safe.append(line).append('\n');
                else if (line.startsWith("exceptionClass=")) safe.append("exceptionClass=").append(safeClass(line.substring(15))).append('\n');
                else if (line.matches("frame=(com\\.filmlab\\.|com\\.sony\\.|android\\.|java\\.|javax\\.|dalvik\\.)[A-Za-z0-9_$.]{1,150}#[A-Za-z_$<>][A-Za-z0-9_$<>]{0,63}:-?[0-9]{1,10}")) safe.append(line).append('\n');
            }
            return safe.toString();
        } catch (IOException failure) { failedWrite(); return ""; }
        catch (SecurityException failure) { failedWrite(); return ""; }
    }

    /** A checkpoint is historical evidence, never a live camera readback.
     * In asynchronous mode true means accepted for the next coalesced flush;
     * getLastCameraState only publishes checkpoints after successful disk I/O. */
    public boolean saveCameraState(Map<String, String> facts) {
        if (facts == null) return false;
        Map<String, String> safe = new HashMap<String, String>();
        for (int index = 0; index < STATE_KEYS.length; index++) {
            String key = STATE_KEYS[index];
            String input = facts.get("readbackAgeAtCheckpointMs".equals(key) ? "readbackAgeMs" : key);
            safe.put(key, fact(key, input));
        }
        long elapsed;
        synchronized (this) { elapsed = Math.max(lastElapsed, Math.max(0L, clock.elapsedMillis() - clockStart)); }
        StringBuilder text = new StringBuilder(STATE_HEADER).append('\n')
            .append("sessionId=").append(sessionId).append('\n')
            .append("elapsedMs=").append(elapsed).append('\n');
        for (int index = 0; index < STATE_KEYS.length; index++) text.append(STATE_KEYS[index]).append('=').append(safe.get(STATE_KEYS[index])).append('\n');
        text.append("complete=true\n");
        StateSnapshot snapshot = new StateSnapshot(safe, elapsed, text.toString());
        if (asynchronous) {
            synchronized (this) {
                if (pendingState != null) coalescedCheckpoints++;
                pendingState = snapshot;
                changed();
            }
            return true;
        }
        synchronized (privateIoLock) { return persistCameraState(snapshot); }
    }

    private boolean persistCameraState(StateSnapshot snapshot) {
        try {
            if (!privateReady() || size(snapshot.text) > MAX_STATE_BYTES) throw new IOException("STATE_UNAVAILABLE");
            File target = new File(privateDir, "CAMERA.TXT");
            File backup = new File(privateDir, "CAMERA1.TXT");
            File temporary = new File(privateDir, "CAMERA.TMP");
            byte[] data = bytes(snapshot.text);
            writeFile(temporary, data);
            if (!verify(temporary, data)) throw new IOException("STATE_VERIFY_FAILED");
            if (target.exists()) {
                if (backup.exists() && !backup.delete()) throw new IOException("STATE_ROTATION_FAILED");
                if (!target.renameTo(backup)) throw new IOException("STATE_ROTATION_FAILED");
            }
            if (!temporary.renameTo(target)) {
                if (!target.exists() && backup.exists()) backup.renameTo(target);
                throw new IOException("STATE_REPLACE_FAILED");
            }
            synchronized (this) {
                savedState = snapshot.facts;
                savedStateSession = sessionId;
                savedStateElapsed = snapshot.elapsed;
            }
            return true;
        } catch (IOException failure) { failedWrite(); return false; }
        catch (SecurityException failure) { failedWrite(); return false; }
        finally {
            try { if (privateDir != null) new File(privateDir, "CAMERA.TMP").delete(); }
            catch (SecurityException ignored) { }
        }
    }

    private boolean loadState(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_STATE_BYTES) return false;
            String text = readTail(file, MAX_STATE_BYTES);
            if (!text.startsWith(STATE_HEADER + "\n") || !text.endsWith("complete=true\n")) return false;
            String[] lines = text.split("\n");
            Map<String, String> values = new HashMap<String, String>();
            for (int index = 1; index < lines.length; index++) {
                int equal = lines[index].indexOf('=');
                if (equal > 0) values.put(lines[index].substring(0, equal), lines[index].substring(equal + 1));
            }
            String oldSession = values.get("sessionId");
            String elapsed = values.get("elapsedMs");
            if (oldSession == null || !oldSession.matches("S[0-9a-f]{1,16}") || elapsed == null || !elapsed.matches("[0-9]{1,18}")) return false;
            Map<String, String> safe = new HashMap<String, String>();
            for (int index = 0; index < STATE_KEYS.length; index++) safe.put(STATE_KEYS[index], fact(STATE_KEYS[index], values.get(STATE_KEYS[index])));
            synchronized (this) {
                savedState = safe;
                savedStateSession = oldSession;
                savedStateElapsed = Long.parseLong(elapsed);
            }
            return true;
        } catch (IOException failure) { failedWrite(); return false; }
        catch (SecurityException failure) { failedWrite(); return false; }
    }

    /** Defensive copy. Age across process restarts is intentionally unknown. */
    public synchronized Map<String, String> getLastCameraState() {
        Map<String, String> result = new HashMap<String, String>(savedState);
        result.put("lastStateStatus", savedState.isEmpty() ? "unavailable" : sessionId.equals(savedStateSession) ? "current-session" : "previous-session");
        result.put("lastStateSession", savedStateSession);
        result.put("lastStateAgeMs", !savedState.isEmpty() && sessionId.equals(savedStateSession)
            ? Long.toString(Math.max(0L, clock.elapsedMillis() - clockStart - savedStateElapsed)) : "unknown");
        return result;
    }

    public synchronized String createReport(Map<String, String> facts) {
        Map<String, String> currentFacts = facts == null ? new HashMap<String, String>() : new HashMap<String, String>(facts);
        Map<String, String> snapshot = getLastCameraState();
        currentFacts.put("lastStateStatus", snapshot.get("lastStateStatus"));
        currentFacts.put("lastStateSession", snapshot.get("lastStateSession"));
        currentFacts.put("lastStateAgeMs", snapshot.get("lastStateAgeMs"));
        StringBuilder state = new StringBuilder();
        for (int index = 0; index < FACT_KEYS.length; index++) {
            String key = FACT_KEYS[index];
            state.append(key).append('=').append(fact(key, currentFacts.get(key))).append('\n');
        }
        StringBuilder persisted = new StringBuilder("freshness=persisted-snapshot\n")
            .append("origin=").append(snapshot.get("lastStateStatus")).append('\n')
            .append("sessionId=").append(snapshot.get("lastStateSession")).append('\n')
            .append("checkpointAgeMs=").append(snapshot.get("lastStateAgeMs")).append('\n')
            .append("ageAcrossSessions=unknown-clock-may-reset\n");
        if (savedState.isEmpty()) persisted.append("none\n");
        else for (int index = 0; index < STATE_KEYS.length; index++) persisted.append(STATE_KEYS[index]).append('=').append(snapshot.get(STATE_KEYS[index])).append('\n');
        String generated = "sony-timeutil".equals(currentFacts.get("cameraTimeSource")) ? fact("cameraTime", currentFacts.get("cameraTime")) : "unknown";
        String timeSource = "unknown".equals(generated) ? "unavailable" : "Sony-TimeUtil-camera-set-unverified";
        String identity = HEADER + "\nFilm Lab — app-owned diagnostics\n" +
            "schemaVersion=1\nreportId=" + sessionId + "-R" + (++reportSequence) +
            "\nsessionId=" + sessionId + "\ngeneratedAtUtc=" + generated +
            "\nwallClockSource=" + timeSource + "\nmonotonicSource=" + clockSource +
            "\ntargetModel=Sony A7R II / ILCE-7RM2\n" +
            "scope=app-owned-events-and-caller-supplied-facts\n" +
            "osLogcat=not-collected\ncameraActionsDuringReport=none\n" +
            "cardMountAndUsbVisibility=not-verified-by-logger\n" +
            "exportDurability=not-guaranteed-across-power-loss\n" +
            "recipeIds=built-in-or-hashed-identifiers\n";
        String previous = previousEvents;
        StringBuilder measurements = new StringBuilder("scope=current-process\nunits=milliseconds\nmeasurement=instrumented-code-stages-not-key-to-frame-latency\n");
        for (int index = 0; index < TIMING_KEYS.length; index++) {
            String key = TIMING_KEYS[index];
            long[] value = timings.get(key);
            measurements.append(key).append(" count=").append(value == null ? 0L : value[0]);
            if (value != null) measurements.append(" last_ms=").append(value[1]).append(" max_ms=").append(value[2]).append(" total_ms=").append(value[3]);
            measurements.append('\n');
        }
        StringBuilder startup = new StringBuilder("scope=current-process\norigin=initial-native-bootstrap\nunits=milliseconds-since-origin\n");
        for (int index = 0; index < MILESTONE_KEYS.length; index++) {
            String key = MILESTONE_KEYS[index];
            startup.append(key).append('=').append(milestones.containsKey(key) ? milestones.get(key).toString() : "not-observed").append('\n');
        }
        int omitted = 0;
        String report;
        do {
            report = identity + "memoryDroppedEvents=" + droppedEvents + "\nprivateWriteFailures=" + privateWriteFailures +
                "\nprivatePersistenceMode=" + (asynchronous ? "batched-worker" : "synchronous") +
                "\nprivateHistoryLoaded=" + historyLoaded + "\nprivatePendingEvents=" + pendingEvents.size() +
                "\nprivatePendingBytes=" + pendingBytes + "\nprivateQueueDroppedEvents=" + pendingDroppedEvents +
                "\nprivateCoalescedCheckpoints=" + coalescedCheckpoints +
                "\nprivateFlushStatus=" + (!historyLoaded || completedGeneration < pendingGeneration ? "pending" : failedGeneration >= pendingGeneration ? "failed" : "complete") +
                "\npreviousTailLimited=" + previousTailLimited + "\nfilteredPreviousLines=" + filteredPreviousLines +
                "\nreportCapOmittedPreviousBytes=" + omitted + "\n\n[FACTS]\n" + state +
                "\n[LAST_PERSISTED_CAMERA_STATE]\n" + persisted +
                "\n[STARTUP_MILESTONES]\n" + startup + "\n[STAGE_TIMINGS]\n" + measurements +
                "\n[PREVIOUS_SESSION_EVENTS]\n" + (previous.length() == 0 ? "none\n" : previous) +
                "\n[CURRENT_SESSION_EVENTS]\n" + getRecentEvents() +
                "\n[LAST_RECOVERABLE_JAVA_CRASH]\n" + (crashSnapshot.length() == 0 ? "none\n" : crashSnapshot) +
                "\n[REPRODUCTION_TO_FILL_ON_COMPUTER]\nsteps=\nexpected=\nactual=\n";
            if (size(report) <= MAX_REPORT_BYTES) break;
            int newline = previous.indexOf('\n');
            if (newline < 0) throw new IllegalStateException("REPORT_SIZE_LIMIT");
            omitted += size(previous.substring(0, newline + 1));
            previous = previous.substring(newline + 1);
        } while (true);
        return report;
    }

    /** Injectable file boundary for host tests; called only for app-owned files. */
    protected void writeFile(File file, byte[] data) throws IOException {
        FileOutputStream output = new FileOutputStream(file, false);
        try { output.write(data); output.flush(); } finally { output.close(); }
    }

    // Card I/O must not hold the monitor used by passive native event hooks or
    // the Java crash recorder. Serialize exporters independently of app logs.
    public File export(File cardRoot, String reportText) throws IOException {
        try {
            File result;
            synchronized (exportLock) { result = exportFile(cardRoot, reportText); }
            record("report.export", "status=verified;stage=export");
            return result;
        } catch (IOException failure) {
            record("report.failure", "status=failed;stage=export");
            throw failure;
        } catch (RuntimeException failure) {
            record("report.failure", "status=failed;stage=export");
            throw failure;
        }
    }

    private File exportFile(File cardRoot, String reportText) throws IOException {
        if (cardRoot == null || !cardRoot.exists()) throw new ExportException("NO_CARD");
        if (!cardRoot.isDirectory()) throw new ExportException("CARD_NOT_DIRECTORY");
        if (!cardRoot.canWrite()) throw new ExportException("CARD_NOT_WRITABLE");
        if (reportText == null || !reportText.startsWith(HEADER + "\n")) throw new ExportException("INVALID_REPORT");
        byte[] data = bytes(reportText);
        if (data.length > MAX_REPORT_BYTES) throw new ExportException("REPORT_TOO_LARGE");
        File folder = new File(cardRoot, "FILMLAB");
        if (!folder.isDirectory() && (folder.exists() || !folder.mkdir())) throw new ExportException("CREATE_DIRECTORY_FAILED");
        String cardPath = cardRoot.getCanonicalPath();
        if (!folder.getCanonicalPath().startsWith(cardPath + File.separator)) throw new ExportException("CREATE_DIRECTORY_FAILED");
        for (int candidate = 1; candidate <= 99999; candidate++) {
            String base = String.format(Locale.US, "FLR%05d", Integer.valueOf(candidate));
            File target = new File(folder, base + ".TXT");
            File temporary = new File(folder, base + ".TMP");
            if (target.exists() || temporary.exists()) continue;
            try { if (!temporary.createNewFile()) continue; }
            catch (IOException failure) { throw new ExportException("TEMP_WRITE_FAILED"); }
            boolean targetOwned = false;
            boolean success = false;
            try {
                try { writeFile(temporary, data); } catch (IOException failure) { throw new ExportException("TEMP_WRITE_FAILED"); }
                if (!verify(temporary, data)) throw new ExportException("TEMP_WRITE_FAILED");
                try { if (!target.createNewFile()) continue; }
                catch (IOException failure) { throw new ExportException("FINALIZE_FAILED"); }
                targetOwned = true;
                // Copy into our exclusively reserved destination: renameTo can
                // replace an existing report on some filesystems. Not atomic.
                try { writeFile(target, data); } catch (IOException failure) { throw new ExportException("FINALIZE_FAILED"); }
                if (!verify(target, data)) throw new ExportException("VERIFY_FAILED");
                success = true;
                return target;
            } finally {
                temporary.delete();
                if (targetOwned && !success) target.delete();
            }
        }
        throw new ExportException("NO_FREE_NAME");
    }

    private static boolean verify(File file, byte[] expected) {
        if (!file.isFile() || file.length() != expected.length) return false;
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                byte[] buffer = new byte[4096];
                int offset = 0;
                while (offset < expected.length) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, expected.length - offset));
                    if (read <= 0) return false;
                    for (int index = 0; index < read; index++) if (buffer[index] != expected[offset + index]) return false;
                    offset += read;
                }
                return input.read() == -1;
            } finally { input.close(); }
        } catch (IOException failure) { return false; }
    }
}
