package android.view;

/** Minimal host fixture. Never include this class in an APK. */
public final class KeyEvent {
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_DPAD_CENTER = 23;
    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_MENU = 82;
    public static final int KEYCODE_BACK = 4;

    private final int scanCode;
    private final int keyCode;

    public KeyEvent(int scanCode, int keyCode) {
        this.scanCode = scanCode;
        this.keyCode = keyCode;
    }

    public int getScanCode() { return scanCode; }
    public int getKeyCode() { return keyCode; }
}
