package com.filmlab.diagnostics;

import android.view.KeyEvent;

/**
 * Local navigation for Film Lab overlays and diagnostic pages. Sony scan codes are read
 * without linking ScalarInput, so this class can also run on plain Android.
 * The fallback table is a hypothesis to validate with the key viewer on each
 * body; it does not change the keys or DISP controller in Sony shooting.
 */
public final class CameraKeys {
    public static final int NONE = 0;
    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    public static final int CENTER = 5;
    public static final int MENU = 6;

    private static boolean usedFallback;
    private static final int SONY_UP = sony("ISV_KEY_UP", 103);
    private static final int SONY_DOWN = sony("ISV_KEY_DOWN", 108);
    private static final int SONY_LEFT = sony("ISV_KEY_LEFT", 105);
    private static final int SONY_RIGHT = sony("ISV_KEY_RIGHT", 106);
    private static final int SONY_CENTER = sony("ISV_KEY_ENTER", 232);
    private static final int SONY_MENU = sony("ISV_KEY_MENU", 514);
    private static final int SONY_SOFT_MENU = sony("ISV_KEY_SK1", 229);
    private static final int SONY_DISP = sony("ISV_KEY_DISP", 608);

    private CameraKeys() {}

    private static int sony(String field, int fallback) {
        try {
            return Class.forName("com.sony.scalar.sysutil.ScalarInput")
                    .getField(field).getInt(null);
        } catch (Exception unavailable) {
            usedFallback = true;
            return fallback;
        } catch (LinkageError unavailable) {
            usedFallback = true;
            return fallback;
        }
    }

    public static boolean usesFallback() { return usedFallback; }

    public static int navigation(KeyEvent event) {
        int scan = event.getScanCode();
        if (scan == SONY_MENU || scan == SONY_SOFT_MENU) return MENU;
        if (scan == SONY_CENTER) return CENTER;
        if (scan == SONY_UP || scan == SONY_DISP) return UP;
        if (scan == SONY_DOWN) return DOWN;
        if (scan == SONY_LEFT) return LEFT;
        if (scan == SONY_RIGHT) return RIGHT;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: return CENTER;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK: return MENU;
            default: return NONE;
        }
    }
}
