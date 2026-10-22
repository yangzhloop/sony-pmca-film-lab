package com.filmlab.diagnostics;
import java.util.ArrayList;
import java.util.List;
public final class DiagnosticsBridge {
    public static final List<String> events = new ArrayList<String>();
    public static void record(String event, String detail) { events.add(event + " " + detail); }
    public static void activeRecipe(String id, int revision, String baseId) { }
    public static void checkpoint() { }
    public static void timing(String stage, long duration) { }
    public static void milestone(String stage) { }
}
