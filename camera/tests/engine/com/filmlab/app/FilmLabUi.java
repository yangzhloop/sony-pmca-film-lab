package com.filmlab.app;
import android.app.Activity;
import android.view.KeyEvent;
/** Test-only overlay boundary: production FilmLabUi is never compiled into this suite. */
public final class FilmLabUi {
    public static RecipeStore store;
    public static boolean open, closeOnKey;
    public static int openCalls;
    public static String page;
    public static void attach(Activity host) { }
    public static void detach(Activity host) { open = false; }
    public static RecipeStore getStore() { return store; }
    public static void open(String value) { page = value; open = true; openCalls++; }
    public static boolean isOpen() { return open; }
    public static void close() { open = false; }
    public static void cancelPendingPreview() { }
    public static boolean dispatch(KeyEvent event) { if (closeOnKey) close(); return true; }
}
