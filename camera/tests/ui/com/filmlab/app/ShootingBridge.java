package com.filmlab.app;
import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import testsupport.Probe;
public final class ShootingBridge {
    public static final List<Recipe> previews = new ArrayList<Recipe>();
    public static int applies, cancels;
    public static String cancelError, previewError;
    public static String nativeMenu, nativeMenuError;
    public static void reset() { previews.clear(); applies = 0; cancels = 0; cancelError = null; previewError = null; nativeMenu = null; nativeMenuError = null; }
    public static String apply(Activity activity, Recipe recipe) { applies++; Probe.events.add("apply"); return null; }
    public static String preview(Activity activity, Recipe recipe) { previews.add(recipe.copy()); Probe.events.add("preview"); return previewError; }
    public static String cancelPreview() { cancels++; Probe.events.add("cancel"); return cancelError; }
    public static String rollbackLastApply() { return null; }
    public static String openNativeMenu(String id) { nativeMenu = id; return nativeMenuError; }
    public static void syncActiveMetadata(Recipe active) { }
}
