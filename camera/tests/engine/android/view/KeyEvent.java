package android.view;
public class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    private final int action, scan, key;
    public KeyEvent(int action, int scan, int key) { this.action = action; this.scan = scan; this.key = key; }
    public int getAction() { return action; }
    public int getScanCode() { return scan; }
    public int getKeyCode() { return key; }
}
