package com.filmlab.diagnostics;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class DiagnosticsBridge {
    public static int snapshots;
    public static final List<String> exported = new ArrayList<String>();
    public static boolean blockExport, failExport, blockFlush;
    public static int exportStarted, exportFinished;
    public static int flushStarted;
    public static synchronized void reset() {
        snapshots = 0; exported.clear(); blockExport = false; failExport = false;
        exportStarted = 0; exportFinished = 0; blockFlush = false; flushStarted = 0;
    }
    public static synchronized String report(String screen, String language) { return "snapshot-" + (++snapshots); }
    public static File export(String snapshot) throws IOException {
        synchronized (DiagnosticsBridge.class) {
            exportStarted++; exported.add(snapshot); DiagnosticsBridge.class.notifyAll();
            while (blockExport) {
                try { DiagnosticsBridge.class.wait(3000); }
                catch (InterruptedException e) { throw new IOException("interrupted"); }
            }
            exportFinished++; DiagnosticsBridge.class.notifyAll();
            if (failExport) throw new ReportStore.ExportException("NO_STORAGE");
            return new File("FLR0000" + exportFinished + ".TXT");
        }
    }
    public static synchronized void awaitStarted(int target) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (exportStarted < target && System.currentTimeMillis() < deadline)
            DiagnosticsBridge.class.wait(Math.max(1, deadline - System.currentTimeMillis()));
        if (exportStarted < target) throw new AssertionError("export_worker_did_not_start");
    }
    public static synchronized void release() { blockExport = false; DiagnosticsBridge.class.notifyAll(); }
    public static void flushAsync() { testsupport.Probe.events.add("flush"); }
    public static synchronized boolean awaitFlush(long timeout) {
        flushStarted++; DiagnosticsBridge.class.notifyAll();
        long deadline = System.currentTimeMillis() + timeout;
        while (blockFlush && System.currentTimeMillis() < deadline) {
            try { DiagnosticsBridge.class.wait(Math.max(1, deadline - System.currentTimeMillis())); }
            catch (InterruptedException failed) { return false; }
        }
        return !blockFlush;
    }
    public static synchronized void awaitFlushStarted() throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (flushStarted == 0 && System.currentTimeMillis() < deadline)
            DiagnosticsBridge.class.wait(Math.max(1, deadline - System.currentTimeMillis()));
        if (flushStarted == 0) throw new AssertionError("exit_worker_did_not_start");
    }
    public static synchronized void releaseFlush() { blockFlush = false; DiagnosticsBridge.class.notifyAll(); }
    public static void record(String event, String detail) { }
    public static void milestone(String event) { }
    public static void timing(String event, long elapsed) { }
    public static String recentEvents() { return "host events"; }
    public static Map<String, String> facts(String screen, String language) { return new HashMap<String, String>(); }
    public static Map<String, String> lastCameraState() { return new HashMap<String, String>(); }
}
