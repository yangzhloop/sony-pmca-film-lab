// SPDX-License-Identifier: MIT
package com.filmlab.app;

import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Full, static CJK font independent of the camera's incomplete font fallback. */
public final class FilmFonts {
    private static Typeface regular;
    private static boolean attempted;
    private static String state = "not_loaded";

    private FilmFonts() { }

    public static void apply(TextView view) {
        apply(view, false);
    }

    public static synchronized void apply(TextView view, boolean bold) {
        if (!attempted) {
            attempted = true;
            long started = SystemClock.elapsedRealtime();
            try {
                regular = Typeface.createFromAsset(view.getContext().getAssets(), "fonts/FilmLabCJK.ttf");
                state = "bundled-loaded";
            } catch (RuntimeException failure) {
                // Remain usable and expose a clear status to Developer tools.
                regular = Typeface.DEFAULT;
                state = "bundled-failed";
            }
            try {
                com.filmlab.diagnostics.DiagnosticsBridge.fontStatus(state);
                com.filmlab.diagnostics.DiagnosticsBridge.timing("font_load", Math.max(0L, SystemClock.elapsedRealtime() - started));
            }
            catch (RuntimeException unavailableDiagnostics) { /* Font loading must remain usable. */ }
        }
        // Fake bold preserves the selected CJK face on legacy Android rather
        // than letting a synthesized Typeface select a system fallback face.
        view.setTypeface(regular);
        view.getPaint().setFakeBoldText(bold);
    }

    public static void applyTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface face = text.getTypeface();
            apply(text, face != null && face.isBold());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyTree(group.getChildAt(index));
            }
        }
    }

    public static synchronized String status() {
        return state;
    }
}
