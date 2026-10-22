package android.app;
import android.content.SharedPreferences;
import android.view.Window;
public class Activity {
    private final SharedPreferences preferences = new SharedPreferences();
    private final Window window = new Window();
    public SharedPreferences getSharedPreferences(String key, int mode) { return preferences; }
    public Window getWindow() { return window; }
    public boolean isFinishing() { return false; }
}
