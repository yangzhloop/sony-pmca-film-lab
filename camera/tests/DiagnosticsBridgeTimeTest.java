package com.filmlab.diagnostics;

import java.lang.reflect.Field;
import java.util.Map;

/** Runs without a camera, once with the Sony clock fixture and once without it. */
public final class DiagnosticsBridgeTimeTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "missing".equals(args[0])) {
            check("unknown".equals(DiagnosticsBridge.facts("device", "en").get("cameraTime")), "missing Sony clock stays unknown");
            check("unavailable".equals(DiagnosticsBridge.facts("device", "en").get("cameraTimeSource")), "no fabricated Android time fallback");
        } else {
            Field mode = Class.forName("com.sony.scalar.sysutil.TimeUtil").getField("mode");
            mode.setInt(null, 0);
            Map<String, String> east = DiagnosticsBridge.facts("device", "en");
            check("2026-10-18T05:06:07Z".equals(east.get("cameraTime")), "Sony local time and offset converted to UTC");
            check("sony-timeutil".equals(east.get("cameraTimeSource")), "actual Sony source explicit");
            for (int invalid = 1; invalid <= 4; invalid++) {
                mode.setInt(null, invalid);
                Map<String, String> bad = DiagnosticsBridge.facts("device", "en");
                check("unknown".equals(bad.get("cameraTime")) && "unavailable".equals(bad.get("cameraTimeSource")), "invalid or failing clock never reuses previous time: " + invalid);
            }
            mode.setInt(null, 5);
            check("2026-10-18T18:06:07Z".equals(DiagnosticsBridge.facts("device", "en").get("cameraTime")), "negative camera timezone converted correctly");
            mode.setInt(null, 6);
            check("2026-10-18T04:06:07Z".equals(DiagnosticsBridge.facts("device", "en").get("cameraTime")), "summer time offset included");
        }
        System.out.println("DiagnosticsBridgeTimeTest: " + assertions + " assertions passed");
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
