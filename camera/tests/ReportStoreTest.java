import com.filmlab.diagnostics.ReportStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone main tests; compile against API10 and run with an ordinary JVM. */
public final class ReportStoreTest {
    private static int assertions;
    private static File root;

    private static final class TestClock implements ReportStore.Clock {
        long value;
        public long elapsedMillis() { return value; }
    }
    private static final class FailingStore extends ReportStore {
        boolean failFinal;
        boolean corruptFinal;
        boolean corruptTemporary;
        FailingStore(File dir) { super(dir); }
        protected void writeFile(File file, byte[] data) throws IOException {
            boolean card = "FILMLAB".equals(file.getParentFile().getName());
            if (card && file.getName().endsWith(".TXT") && failFinal) {
                failFinal = false;
                throw new IOException("injected private path must not escape");
            }
            if (card && file.getName().endsWith(".TXT") && corruptFinal) {
                corruptFinal = false;
                super.writeFile(file, "partial".getBytes("UTF-8"));
                return;
            }
            if (card && file.getName().endsWith(".TMP") && corruptTemporary) {
                corruptTemporary = false;
                super.writeFile(file, "partial".getBytes("UTF-8"));
                return;
            }
            super.writeFile(file, data);
        }
    }
    private static final class BlockingStore extends ReportStore {
        final CountDownLatch exportEntered = new CountDownLatch(1);
        final CountDownLatch releaseExport = new CountDownLatch(1);
        BlockingStore(File dir) { super(dir); }
        protected void writeFile(File file, byte[] data) throws IOException {
            if ("FILMLAB".equals(file.getParentFile().getName()) && file.getName().endsWith(".TMP")) {
                exportEntered.countDown();
                try {
                    if (!releaseExport.await(10, TimeUnit.SECONDS)) throw new IOException("TEST_EXPORT_TIMEOUT");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("TEST_EXPORT_INTERRUPTED");
                }
            }
            super.writeFile(file, data);
        }
    }
    private static final class AsyncStore extends ReportStore {
        final CountDownLatch stateEntered = new CountDownLatch(1);
        final CountDownLatch releaseState = new CountDownLatch(1);
        volatile boolean blockState;
        volatile boolean failState;
        volatile int stateWrites;
        AsyncStore(File dir) {
            super(dir, new ReportStore.Clock() { public long elapsedMillis() { return System.nanoTime() / 1000000L; } }, true, -1L);
        }
        protected void writeFile(File file, byte[] data) throws IOException {
            if ("CAMERA.TMP".equals(file.getName())) {
                stateWrites++;
                if (blockState) {
                    stateEntered.countDown();
                    try { if (!releaseState.await(10, TimeUnit.SECONDS)) throw new IOException("TEST_TIMEOUT"); }
                    catch (InterruptedException interrupted) { throw new IOException("TEST_INTERRUPTED"); }
                }
                if (failState) { failState = false; throw new IOException("PRIVATE_FAILURE_MUST_NOT_ESCAPE"); }
            }
            super.writeFile(file, data);
        }
    }

    public static void main(String[] args) throws Exception {
        root = File.createTempFile("filmlab-reports-", ".test");
        check(root.delete() && root.mkdir(), "create test root");
        try {
            redactionAndUtf8();
            rotationAndPreviousSession();
            collisionsAndExactReadback();
            retryAndCorruption();
            rejectedDestinationsAndSize();
            privateCrashRecovery();
            unavailablePrivateStorage();
            blockedCardDoesNotBlockPrivateEvidence();
            cameraStateSurvivesRestartWithoutFreshnessClaim();
            cameraClockAndExportHistory();
            asynchronousPersistenceIsBoundedAndNonblocking();
            asynchronousFailureIsReported();
            stageTimingsAndStartupOutliveRollingEvents();
            System.out.println("ReportStoreTest: 13 scenarios, " + assertions + " assertions passed");
        } finally { remove(root); }
    }

    private static void redactionAndUtf8() throws Exception {
        TestClock clock = new TestClock(); clock.value = 1000;
        ReportStore store = new ReportStore(directory("redaction-private"), clock);
        String secret = "PRIVATE_我的配方_https://example.invalid/secret /private/serial-123";
        clock.value = 1250;
        store.record("camera.open", "status=ready;stage=open;code=1;message=" + secret);
        store.record(secret, secret);
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("appVersion", "0.1.0-diagnostics");
        facts.put("buildCommit", "0123456789012345678901234567890123456789");
        facts.put("buildId", "abc1234");
        facts.put("model", "ILCE-7RM2"); facts.put("firmware", "4.01");
        facts.put("requestedId", "pop-color"); facts.put("requestedStrength", "70");
        facts.put("wb", "cloudy-daylight");
        facts.put("applyStatus", "accepted"); facts.put("readbackSource", "native_camera_get_parameters");
        facts.put("readbackStatus", "cached_before_request"); facts.put("displaySource", "inherited_not_probed");
        facts.put("rgbSupport", "supported"); facts.put("gammaSupport", "unknown");
        facts.put("observedAfterRequest", "false"); facts.put("matrixMatchesRequest", "unknown");
        facts.put("lastErrorCode", "-1"); facts.put("storageStatus", "external_storage_mounted");
        facts.put("requestedMatrix", "4096,0,0,0,4096,0,0,0,4096");
        facts.put("activeId", secret); facts.put("name", secret); facts.put("serial", secret);
        facts.put("screen", secret); facts.put("color", secret);
        String report = store.createReport(facts);
        check(report.startsWith(ReportStore.HEADER + "\n"), "report header");
        check(report.indexOf(secret) < 0 && report.indexOf("example.invalid") < 0, "names paths serial and network omitted");
        check(report.indexOf("activeId=recipe-") >= 0, "untrusted ID hashed");
        check(report.indexOf("model=ILCE-7RM2") >= 0, "actual supplied model retained");
        check(report.indexOf("buildId=abc1234") >= 0, "build identity not mistaken for a user recipe ID");
        check(report.indexOf("requestedId=pop-color") >= 0 && report.indexOf("requestedStrength=70") >= 0, "fixed native recipe ID and strength retained");
        check(report.indexOf("readbackSource=native_camera_get_parameters") >= 0 && report.indexOf("readbackStatus=cached_before_request") >= 0, "native cached-source distinctions retained");
        check(report.indexOf("observedAfterRequest=false") >= 0 && report.indexOf("matrixMatchesRequest=unknown") >= 0 && report.indexOf("applyStatus=accepted") >= 0, "accepted call not inferred as matched readback");
        check(report.indexOf("displaySource=inherited_not_probed") >= 0 && report.indexOf("storageStatus=external_storage_mounted") >= 0 && report.indexOf("lastErrorCode=-1") >= 0, "native diagnostic codes retained");
        String fullBuildId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        facts.put("buildId", fullBuildId); facts.put("flashMode", "wireless");
        String extended = store.createReport(facts);
        check(extended.indexOf("buildId=" + fullBuildId) >= 0 && extended.indexOf("flashMode=wireless") >= 0, "full source hash and native flash enum retained");
        store.record("key.event", "key_code=19;scan_code=103;repeat_count=0;message=" + secret);
        check(store.getRecentEvents().indexOf("key_code=19;scan_code=103;repeat_count=0") >= 0 && store.getRecentEvents().indexOf(secret) < 0, "integer key facts kept without characters");
        store.record("lifecycle.resume", "phase=launch;code=1;mode=playback;screen=none");
        check(store.getRecentEvents().indexOf("phase=launch;code=1;mode=playback;screen=none") >= 0,
            "fixed native playback boot route survives report privacy filtering");
        store.record("recipe.apply", "status=failed;code=white_balance_unsupported;message=" + secret);
        check(store.getRecentEvents().indexOf("code=white_balance_unsupported") >= 0 && store.getRecentEvents().indexOf(secret) < 0, "fixed engine error code retained without arbitrary exception details");
        store.record("recipe.apply", "status=failed;code=" + secret);
        check(store.getRecentEvents().indexOf(secret) < 0, "unlisted engine error code remains redacted");
        check(report.indexOf("platformApi=unknown") >= 0 && report.indexOf("observedMatrix=unknown") >= 0, "unavailable facts not inferred from requests");
        check(report.indexOf("requestedMatrix=4096,0,0,0,4096,0,0,0,4096") >= 0, "requested matrix distinct");
        check(report.indexOf("color=unknown") >= 0 && report.indexOf("screen=unknown") >= 0, "invalid typed facts rejected");
        check(report.indexOf("wb=cloudy-daylight") >= 0, "fixed native WB mode retained");
        check(report.indexOf("elapsed_ms=250") >= 0 && report.indexOf("redacted=true") >= 0, "monotonic time and redaction marked");
        check(report.indexOf("steps=\nexpected=\nactual=\n") >= 0, "manual reproduction fields");
        check(report.indexOf("power-loss") >= 0 && report.indexOf("osLogcat=not-collected") >= 0, "limits explicit");
        check(new String(report.getBytes("UTF-8"), "UTF-8").equals(report) && report.indexOf('\u2014') >= 0, "UTF8 round trip preserves report text");
        clock.value = 1100;
        store.record("camera.close", "status=success");
        check(store.getRecentEvents().indexOf("elapsed_ms=100") < 0, "elapsed clock never moves backwards");
    }

    private static void rotationAndPreviousSession() throws Exception {
        File privateDir = directory("rotation-private");
        ReportStore store = new ReportStore(privateDir);
        for (int index = 0; index < 2200; index++) store.record("parameters.request", "status=requested;field=color;number=" + index + ";operation_id=OP" + index);
        File current = new File(privateDir, "APPLOG.TXT");
        File previous = new File(privateDir, "APPLOG1.TXT");
        check(current.isFile() && previous.isFile(), "two rotating private files");
        check(current.length() <= ReportStore.MAX_LOG_BYTES && previous.length() <= ReportStore.MAX_LOG_BYTES, "both logs bounded at64KiB");
        check(store.getRecentEvents().getBytes("UTF-8").length <= ReportStore.MAX_LOG_BYTES, "memory tail bounded");
        String report = store.createReport(null);
        check(report.indexOf("memoryDroppedEvents=0\n") < 0, "dropped event count explicit");
        check(report.getBytes("UTF-8").length <= ReportStore.MAX_REPORT_BYTES, "report bounded after rotation");
        ReportStore restarted = new ReportStore(privateDir);
        String restored = restarted.createReport(null);
        check(restored.indexOf("[PREVIOUS_SESSION_EVENTS]\nsession=") >= 0, "previous session tail restored");
        check(restored.indexOf("seq=2200 ") >= 0, "newest previous event retained");
        StringBuilder untrusted = new StringBuilder();
        while (untrusted.length() < ReportStore.MAX_LOG_BYTES + 1024) untrusted.append("PRIVATE_INJECTED_PATH /secret/file\n");
        write(current, untrusted.toString());
        String filtered = new ReportStore(privateDir).createReport(null);
        check(filtered.indexOf("PRIVATE_INJECTED_PATH") < 0, "unrecognized previous contents omitted");
        check(filtered.indexOf("previousTailLimited=true") >= 0, "oversized previous tail limitation marked");
    }

    private static void collisionsAndExactReadback() throws Exception {
        ReportStore store = new ReportStore(directory("collision-private"));
        String report = store.createReport(null);
        File card = directory("collision-card");
        File folder = new File(card, "FILMLAB"); check(folder.mkdir(), "create app card folder");
        File old = new File(folder, "FLR00001.TXT"); write(old, "previous immutable report");
        File orphan = new File(folder, "FLR00002.TMP"); write(orphan, "previous incomplete export");
        File result = store.export(card, report);
        check("FLR00003.TXT".equals(result.getName()), "TXT and TMP collisions skipped");
        check(read(old).equals("previous immutable report") && read(orphan).equals("previous incomplete export"), "existing user exports untouched");
        check(read(result).equals(report), "exact closed-file UTF8 readback");
        check(result.getName().matches("[A-Z0-9]{8}\\.TXT"), "8.3 final name");
        check(!new File(folder, "FLR00003.TMP").exists(), "own temporary cleaned");
        File next = new ReportStore(directory("collision-second-private")).export(card, report);
        check("FLR00004.TXT".equals(next.getName()) && read(next).equals(report), "independent instance also avoids overwrites");
    }

    private static void retryAndCorruption() throws Exception {
        FailingStore store = new FailingStore(directory("failure-private"));
        File card = directory("failure-card");
        String retained = store.createReport(null);
        store.failFinal = true;
        expectExport(store, card, retained, "FINALIZE_FAILED");
        check(new File(card, "FILMLAB").list().length == 0, "failed finalization cleans only own files");
        File retried = store.export(card, retained);
        check(read(retried).equals(retained), "retry uses unchanged retained snapshot");
        store.corruptFinal = true;
        expectExport(store, card, retained, "VERIFY_FAILED");
        check(read(retried).equals(retained), "readback failure preserves existing reports");
        store.corruptTemporary = true;
        expectExport(store, card, retained, "TEMP_WRITE_FAILED");
        check(read(store.export(card, retained)).equals(retained), "retry after temporary corruption succeeds");
    }

    private static void rejectedDestinationsAndSize() throws Exception {
        ReportStore store = new ReportStore(directory("destinations-private"));
        String report = store.createReport(null);
        expectExport(store, null, report, "NO_CARD");
        File missing = new File(root, "missing-card");
        expectExport(store, missing, report, "NO_CARD");
        check(!missing.exists(), "missing card root never created");
        File fileRoot = new File(root, "card-is-file"); write(fileRoot, "unchanged");
        expectExport(store, fileRoot, report, "CARD_NOT_DIRECTORY");
        File card = directory("destinations-card");
        expectExport(store, card, "not a report", "INVALID_REPORT");
        StringBuilder exact = new StringBuilder(ReportStore.HEADER).append('\n');
        while (exact.length() < ReportStore.MAX_REPORT_BYTES) exact.append('x');
        File full = store.export(card, exact.toString());
        check(full.length() == ReportStore.MAX_REPORT_BYTES && read(full).equals(exact.toString()), "exact byte limit exported without truncation");
        expectExport(store, card, exact.toString() + "\u4e2d", "REPORT_TOO_LARGE");
        check(full.length() == ReportStore.MAX_REPORT_BYTES, "oversize rejection preserves earlier export");
        File blockedCard = directory("blocked-card"); write(new File(blockedCard, "FILMLAB"), "untouched");
        expectExport(store, blockedCard, report, "CREATE_DIRECTORY_FAILED");
        check(read(new File(blockedCard, "FILMLAB")).equals("untouched"), "non-directory app destination preserved");
    }

    private static void privateCrashRecovery() throws Exception {
        File privateDir = directory("crash-private");
        ReportStore store = new ReportStore(privateDir);
        RuntimeException error = new IllegalStateException("PRIVATE_USER_NAME /secret/path https://private.invalid");
        error.setStackTrace(new StackTraceElement[] { new StackTraceElement("com.filmlab.Test", "apply", "/PRIVATE_FILE_PATH", 123) });
        error.initCause(new IllegalArgumentException("PRIVATE_CAUSE_MESSAGE"));
        store.recordCrash(error);
        File crash = new File(privateDir, "CRASH.TXT");
        check(crash.isFile() && crash.length() <= ReportStore.MAX_CRASH_BYTES, "bounded private crash snapshot");
        String report = store.createReport(null);
        check(report.indexOf("java.lang.IllegalStateException") >= 0 && report.indexOf("com.filmlab.Test#apply:123") >= 0, "safe crash class and frame retained");
        check(report.indexOf("PRIVATE_") < 0 && report.indexOf("private.invalid") < 0, "exception messages and source filenames excluded");
        String restored = new ReportStore(privateDir).createReport(null);
        check(restored.indexOf("java.lang.IllegalStateException") >= 0, "private crash survives next logger session");
        check(!new File(privateDir, "FILMLAB").exists(), "crash path performs no card export");
        StackTraceElement[] manyFrames = new StackTraceElement[200];
        String longClass = "com.filmlab.LongClassNameForDiagnosticBoundsAndRecoverableCrashEvidenceOnly";
        for (int index = 0; index < manyFrames.length; index++) manyFrames[index] = new StackTraceElement(longClass, "longMethodNameForDiagnosticBoundsOnly", "/PRIVATE_FILE_PATH", 123);
        error.setStackTrace(manyFrames);
        store.recordCrash(error);
        check(crash.length() <= ReportStore.MAX_CRASH_BYTES && read(crash).indexOf("truncated=true") >= 0, "oversized recoverable stack bounded and marked");
    }

    private static void unavailablePrivateStorage() throws Exception {
        File unavailable = new File(root, "private-is-file"); write(unavailable, "untouched");
        ReportStore store = new ReportStore(unavailable);
        store.record("session.open", "status=ready");
        store.recordCrash(new RuntimeException("must not leak"));
        String report = store.createReport(null);
        check(report.indexOf("event=session.open") >= 0, "memory evidence works with unavailable private dir");
        check(report.indexOf("privateWriteFailures=0\n") < 0, "private write failure explicit");
        check(read(unavailable).equals("untouched"), "unrelated private file not replaced");
    }

    private static void blockedCardDoesNotBlockPrivateEvidence() throws Exception {
        final File privateDir = directory("blocking-private");
        final File card = directory("blocking-card");
        final BlockingStore store = new BlockingStore(privateDir);
        final String retained = store.createReport(null);
        final Throwable[] failures = new Throwable[2];
        final File[] exported = new File[1];
        final CountDownLatch evidenceWritten = new CountDownLatch(1);
        Thread exporter = new Thread(new Runnable() {
            public void run() {
                try { exported[0] = store.export(card, retained); }
                catch (Throwable failure) { failures[0] = failure; }
            }
        }, "test-blocked-card-export");
        Thread observer = new Thread(new Runnable() {
            public void run() {
                try {
                    store.record("camera.error", "status=failed;code=42");
                    store.recordCrash(new IllegalStateException("PRIVATE_ERROR_MESSAGE"));
                    Map<String, String> state = new HashMap<String, String>();
                    state.put("captureStatus", "failed");
                    store.saveCameraState(state);
                } catch (Throwable failure) { failures[1] = failure; }
                finally { evidenceWritten.countDown(); }
            }
        }, "test-private-evidence");
        exporter.start();
        try {
            check(store.exportEntered.await(3, TimeUnit.SECONDS), "card write reached blocking boundary");
            observer.start();
            check(evidenceWritten.await(3, TimeUnit.SECONDS), "log and crash complete while card export is blocked");
            check(store.releaseExport.getCount() == 1 && exporter.isAlive(), "card export remains blocked during evidence checks");
            check(failures[1] == null, "private evidence writes did not fail");
            check(store.getRecentEvents().indexOf("event=camera.error detail=status=failed;code=42") >= 0, "global camera error has its own event category");
            check(new File(privateDir, "CRASH.TXT").isFile(), "crash evidence persisted before card release");
            check(new File(privateDir, "CAMERA.TXT").isFile(), "private camera checkpoint completed while card export blocked");
            Map<String, String> facts = new HashMap<String, String>();
            facts.put("storageStatus", "failed");
            check(store.createReport(facts).indexOf("storageStatus=failed") >= 0, "report snapshot remains available during export");
        } finally {
            store.releaseExport.countDown();
            exporter.join(5000);
            if (observer.isAlive()) observer.join(5000);
        }
        check(!exporter.isAlive() && !observer.isAlive(), "workers terminate after release");
        check(failures[0] == null && exported[0] != null, "card export completes after release");
        check(read(exported[0]).equals(retained), "concurrent app evidence does not mutate retained export text");
    }

    private static void cameraStateSurvivesRestartWithoutFreshnessClaim() throws Exception {
        File privateDir = directory("camera-state-private");
        TestClock clock = new TestClock(); clock.value = 1000;
        ReportStore first = new ReportStore(privateDir, clock);
        Map<String, String> facts = new HashMap<String, String>();
        String secret = "PRIVATE_USER_RECIPE_我的配方 /secret/file https://private.invalid";
        facts.put("activeId", secret); facts.put("name", secret); facts.put("file", secret);
        facts.put("requestedId", "fuji-velvia"); facts.put("requestedStrength", "70");
        facts.put("readbackSource", "native_camera_get_parameters"); facts.put("readbackStatus", "observed");
        facts.put("observedMatrix", "4096,0,0,0,4096,0,0,0,4096");
        facts.put("readbackAgeMs", "25"); facts.put("savedImageVerification", "not-verified");
        facts.put("cameraTime", "2026-10-18T05:06:07Z"); facts.put("cameraTimeSource", "sony-timeutil");
        clock.value = 1500;
        check(first.saveCameraState(facts), "camera checkpoint saved");
        String stored = read(new File(privateDir, "CAMERA.TXT"));
        check(stored.indexOf(secret) < 0 && stored.indexOf("PRIVATE_") < 0 && stored.indexOf("private.invalid") < 0, "checkpoint redacts names arbitrary paths and URLs before disk");
        check(stored.indexOf("activeId=recipe-") >= 0 && stored.indexOf("requestedId=fuji-velvia") >= 0, "checkpoint IDs are built-in or hashed");
        check(stored.getBytes("UTF-8").length <= ReportStore.MAX_STATE_BYTES, "private state bounded");
        clock.value = 1700;
        Map<String, String> sameSession = first.getLastCameraState();
        check("current-session".equals(sameSession.get("lastStateStatus")) && "200".equals(sameSession.get("lastStateAgeMs")), "same session uses monotonic checkpoint age");
        check("25".equals(sameSession.get("readbackAgeAtCheckpointMs")), "readback age explicitly belongs to checkpoint");
        sameSession.put("requestedId", "PRIVATE_MUTATION");
        check("fuji-velvia".equals(first.getLastCameraState().get("requestedId")), "saved facts accessor returns defensive copy");
        TestClock resetClock = new TestClock(); resetClock.value = 30;
        ReportStore second = new ReportStore(privateDir, resetClock);
        Map<String, String> restored = second.getLastCameraState();
        check("previous-session".equals(restored.get("lastStateStatus")), "restart marks previous session explicitly");
        check("unknown".equals(restored.get("lastStateAgeMs")), "reset monotonic clock cannot produce fresh snapshot age");
        check(sameSession.get("lastStateSession").equals(restored.get("lastStateSession")), "original checkpoint session preserved");
        String report = second.createReport(null);
        int currentStart = report.indexOf("[FACTS]\n");
        int savedStart = report.indexOf("[LAST_PERSISTED_CAMERA_STATE]\n");
        String current = report.substring(currentStart, savedStart);
        String historical = report.substring(savedStart, report.indexOf("[PREVIOUS_SESSION_EVENTS]"));
        check(current.indexOf("observedMatrix=unknown") >= 0 && current.indexOf("requestedId=unknown") >= 0, "old state never appears as current readback");
        check(historical.indexOf("freshness=persisted-snapshot") >= 0 && historical.indexOf("origin=previous-session") >= 0 && historical.indexOf("checkpointAgeMs=unknown") >= 0, "report shows snapshot provenance and unknown cross-session age");
        check(historical.indexOf("requestedId=fuji-velvia") >= 0 && historical.indexOf("readbackAgeAtCheckpointMs=25") >= 0, "previous useful facts survive restart");
        check(historical.indexOf("savedImageVerification=not-verified") >= 0, "saved-photo claim not upgraded by persistence");
        facts.put("requestedId", "fuji-acros");
        check(second.saveCameraState(facts), "new checkpoint rotates backup");
        write(new File(privateDir, "CAMERA.TXT"), "FILMLAB_CAMERA_STATE_V1\nsessionId=Sabc\nPRIVATE_INJECTED");
        ReportStore recovered = new ReportStore(privateDir, resetClock);
        check("fuji-velvia".equals(recovered.getLastCameraState().get("requestedId")), "incomplete primary falls back to complete previous checkpoint");
        String malicious = stored.replace("requestedId=fuji-velvia", "requestedId=" + secret)
            .replace("observedMatrix=4096,0,0,0,4096,0,0,0,4096", "observedMatrix=" + secret)
            .replace("complete=true", "name=" + secret + "\ncomplete=true");
        write(new File(privateDir, "CAMERA.TXT"), malicious);
        String filtered = new ReportStore(privateDir, resetClock).createReport(null);
        check(filtered.indexOf("PRIVATE_") < 0 && filtered.indexOf("private.invalid") < 0, "loaded checkpoint re-sanitizes values and drops unknown keys");
    }

    private static void cameraClockAndExportHistory() throws Exception {
        ReportStore store = new ReportStore(directory("clock-private"));
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("cameraTime", "1970-01-01T00:00:14Z"); facts.put("cameraTimeSource", "sony-timeutil");
        String unknown = store.createReport(facts);
        check(unknown.indexOf("generatedAtUtc=unknown\nwallClockSource=unavailable") >= 0, "unset clock is unknown instead of apparent 1970 creation date");
        facts.put("cameraTime", "2026-02-30T12:00:00Z");
        check(store.createReport(facts).indexOf("generatedAtUtc=unknown") >= 0, "impossible camera calendar rejected");
        facts.put("cameraTime", "2026-10-18T05:06:07Z");
        String retained = store.createReport(facts);
        check(retained.indexOf("generatedAtUtc=2026-10-18T05:06:07Z\nwallClockSource=Sony-TimeUtil-camera-set-unverified") >= 0, "camera time carries actual source and verification limit");
        facts.put("cameraTimeSource", "system-clock");
        check(store.createReport(facts).indexOf("generatedAtUtc=unknown") >= 0, "unsourced or Android epoch clocks do not become camera date");
        File card = directory("clock-card");
        File exported = store.export(card, retained);
        check(read(exported).equals(retained) && retained.indexOf("event=report.export") < 0, "successful export preserves immutable snapshot");
        check(store.createReport(null).indexOf("event=report.export detail=status=verified;stage=export") >= 0, "future report includes prior successful export");
        expectExport(store, null, retained, "NO_CARD");
        check(store.createReport(null).indexOf("event=report.failure detail=status=failed;stage=export") >= 0, "future report includes failed export without filename or exception text");
    }

    private static void asynchronousPersistenceIsBoundedAndNonblocking() throws Exception {
        File privateDir = directory("async-private");
        ReportStore old = new ReportStore(privateDir);
        old.record("session.open", "status=ready");
        final AsyncStore store = new AsyncStore(privateDir);
        check(store.awaitFlush(3000L), "deferred history load drains");
        check(store.createReport(null).indexOf("[PREVIOUS_SESSION_EVENTS]\nsession=") >= 0, "deferred old history restored before current writes");
        store.blockState = true;
        Map<String, String> first = new HashMap<String, String>(); first.put("requestedId", "fuji-velvia");
        check(store.saveCameraState(first), "async checkpoint accepted without claiming disk completion");
        store.flushAsync();
        final CountDownLatch producerDone = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int index = 0; index < 2000; index++) store.record("parameters.request", "number=" + index + ";status=requested");
                    for (int index = 0; index < 80; index++) {
                        Map<String, String> state = new HashMap<String, String>();
                        state.put("requestedId", "fuji-acros"); state.put("activeRevision", Integer.toString(index));
                        state.put("name", "PRIVATE_SECRET");
                        store.saveCameraState(state);
                        state.put("requestedId", "PRIVATE_MUTATION_AFTER_QUEUE");
                    }
                    store.recordCrash(new IllegalArgumentException("PRIVATE_CRASH_MESSAGE"));
                } catch (Throwable error) { failure[0] = error; }
                finally { producerDone.countDown(); }
            }
        }, "test-native-observer");
        String retained;
        try {
            check(store.stateEntered.await(3, TimeUnit.SECONDS), "private state worker reaches blocked disk boundary");
            producer.start();
            check(producerDone.await(3, TimeUnit.SECONDS) && failure[0] == null, "event observation checkpoint queues and crash recovery continue while private disk worker blocked");
            retained = store.createReport(null);
            check(retained.indexOf("privateFlushStatus=pending") >= 0 && retained.indexOf("privatePendingEvents=256") >= 0, "report distinguishes pending bounded queue from persisted state");
            check(retained.indexOf("privateQueueDroppedEvents=0\n") < 0 && retained.indexOf("privateCoalescedCheckpoints=79") >= 0, "queue overflow and superseded preview checkpoints counted");
            check("unavailable".equals(store.getLastCameraState().get("lastStateStatus")), "queued state never masquerades as successfully persisted state");
            check(new File(privateDir, "CRASH.TXT").isFile(), "crash bypass remains recoverable while state flush blocked");
            check(!store.awaitFlush(20L), "bounded await times out instead of stalling native caller forever");
        } finally { store.releaseState.countDown(); producer.join(3000L); }
        check(store.awaitFlush(5000L), "all queued work drains after slow disk recovers");
        check(store.stateWrites == 2, "80 intermediate checkpoints produce only latest additional disk write");
        Map<String, String> saved = store.getLastCameraState();
        check("fuji-acros".equals(saved.get("requestedId")) && "79".equals(saved.get("activeRevision")), "latest checkpoint wins and queued snapshot is defensive copy");
        String current = store.createReport(null);
        check(current.indexOf("privateFlushStatus=complete") >= 0 && current.indexOf("privatePendingEvents=0") >= 0, "completed flush status shown accurately");
        check(current.indexOf("PRIVATE_") < 0 && read(new File(privateDir, "CAMERA.TXT")).indexOf("PRIVATE_") < 0, "background persistence preserves redaction");
        check(read(new File(privateDir, "APPLOG.TXT")).indexOf("number=1999") >= 0, "latest retained event reaches private disk");
        check(retained.indexOf("privateFlushStatus=pending") >= 0, "later background completion cannot mutate an existing report string");
    }

    private static void asynchronousFailureIsReported() throws Exception {
        AsyncStore store = new AsyncStore(directory("async-failure-private"));
        check(store.awaitFlush(3000L), "failure fixture history initialized");
        store.failState = true;
        Map<String, String> facts = new HashMap<String, String>(); facts.put("requestedId", "fuji-velvia");
        check(store.saveCameraState(facts), "failure not fabricated before queued disk attempt");
        check(!store.awaitFlush(3000L), "failed generation is not reported as successful flush");
        String failed = store.createReport(null);
        check(failed.indexOf("privateFlushStatus=failed") >= 0 && failed.indexOf("privateWriteFailures=1") >= 0, "failure represented without raw exception path");
        check("unavailable".equals(store.getLastCameraState().get("lastStateStatus")), "failed checkpoint does not publish persisted facts");
        facts.put("requestedId", "fuji-acros"); store.saveCameraState(facts);
        check(store.awaitFlush(3000L) && "fuji-acros".equals(store.getLastCameraState().get("requestedId")), "later successful generation can recover from earlier failure");
    }

    private static void stageTimingsAndStartupOutliveRollingEvents() throws Exception {
        TestClock clock = new TestClock(); clock.value = 1000;
        ReportStore store = new ReportStore(directory("metrics-private"), clock);
        store.milestone("native_on_create");
        clock.value = 1020; store.milestone("ui_attached");
        clock.value = 1900; store.milestone("ui_attached");
        store.timing("look_apply", 10L); store.timing("look_apply", 30L);
        store.timing("look_apply", -1L); store.timing("PRIVATE_RECIPE_NAME", 123L);
        store.milestone("PRIVATE_PATH");
        store.timing("wb_apply", Long.MAX_VALUE); store.timing("wb_apply", 3L);
        for (int index = 0; index < 600; index++) store.record("ui.action", "number=" + index);
        String report = store.createReport(null);
        check(report.indexOf("[STARTUP_MILESTONES]\n") >= 0 && report.indexOf("native_on_create=0\n") >= 0, "startup origin retained outside bounded event tail");
        check(report.indexOf("ui_attached=20\n") >= 0 && report.indexOf("first_idle=not-observed") >= 0, "first milestone preserved and unavailable milestones explicit");
        check(report.indexOf("look_apply count=2 last_ms=30 max_ms=30 total_ms=40") >= 0, "stage aggregates count last maximum and total accurately");
        check(report.indexOf("wb_apply count=2 last_ms=3 max_ms=9223372036854775807 total_ms=9223372036854775807") >= 0, "long timing totals saturate without overflow");
        check(report.indexOf("PRIVATE_") < 0 && report.indexOf("not-key-to-frame-latency") >= 0, "unknown metric strings excluded and measurement meaning explicit");
        check(report.getBytes("UTF-8").length <= ReportStore.MAX_REPORT_BYTES, "timing tables preserve report bound");
    }

    private static void expectExport(ReportStore store, File card, String report, String code) throws Exception {
        try { store.export(card, report); throw new AssertionError("Expected export error " + code); }
        catch (ReportStore.ExportException failure) {
            check(code.equals(failure.getCode()) && code.equals(failure.getMessage()), "stable export error " + code);
        }
    }
    private static File directory(String name) throws IOException {
        File result = new File(root, name);
        if (!result.mkdir()) throw new IOException("Cannot create test directory");
        return result;
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void write(File file, String value) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try { output.write(value.getBytes("UTF-8")); } finally { output.close(); }
    }
    private static String read(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), "UTF-8");
        } finally { input.close(); }
    }
    private static void remove(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (int index = 0; index < files.length; index++) remove(files[index]);
        }
        file.delete();
    }
}
