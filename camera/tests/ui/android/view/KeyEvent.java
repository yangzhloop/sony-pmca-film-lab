package android.view;
public class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    public static final int KEYCODE_DPAD_UP = 19, KEYCODE_DPAD_DOWN = 20,
        KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22, KEYCODE_DPAD_CENTER = 23,
        KEYCODE_ENTER = 66, KEYCODE_MENU = 82, KEYCODE_BACK = 4,
        KEYCODE_CAMERA = 27, KEYCODE_FOCUS = 80;
    private final int action, key, scan, repeat;
    public KeyEvent(int action, int key) { this(action, key, 0, 0); }
    public KeyEvent(int action, int key, int scan, int repeat) {
        this.action = action; this.key = key; this.scan = scan; this.repeat = repeat;
    }
    public int getAction() { return action; }
    public int getKeyCode() { return key; }
    public int getScanCode() { return scan; }
    public int getRepeatCount() { return repeat; }
}
