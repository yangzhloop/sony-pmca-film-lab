package android.content;
import java.util.HashMap;
import java.util.Map;
public class SharedPreferences {
    private final Map<String, Boolean> values = new HashMap<String, Boolean>();
    public boolean getBoolean(String key, boolean fallback) {
        Boolean value = values.get(key); return value == null ? fallback : value.booleanValue();
    }
    public String getString(String key, String fallback) { return fallback; }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        public Editor putBoolean(String key, boolean value) { values.put(key, Boolean.valueOf(value)); return this; }
        public boolean commit() { return true; }
    }
}
