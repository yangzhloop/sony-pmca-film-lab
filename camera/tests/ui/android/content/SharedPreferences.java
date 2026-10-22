package android.content;
import java.util.HashMap;
import java.util.Map;
public class SharedPreferences {
    private final Map<String, Object> values = new HashMap<String, Object>();
    public boolean contains(String key) { return values.containsKey(key); }
    public String getString(String key, String fallback) {
        Object value = values.get(key); return value instanceof String ? (String) value : fallback;
    }
    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key); return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        public Editor putString(String key, String value) { values.put(key, value); return this; }
        public Editor putBoolean(String key, boolean value) { values.put(key, Boolean.valueOf(value)); return this; }
        public boolean commit() { return true; }
    }
}
