package android.content;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
public class Context {
    public static File assetDirectory;
    private final File files;
    private final Resources resources = new Resources();
    private final Map<String, SharedPreferences> preferences = new HashMap<String, SharedPreferences>();
    public Context() {
        try { files = File.createTempFile("filmlab-ui-", ""); files.delete(); files.mkdir(); files.deleteOnExit(); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
    }
    public File getFilesDir() { return files; }
    public Resources getResources() { return resources; }
    public AssetManager getAssets() { return new AssetManager(assetDirectory); }
    public SharedPreferences getSharedPreferences(String key, int mode) {
        SharedPreferences value = preferences.get(key);
        if (value == null) { value = new SharedPreferences(); preferences.put(key, value); }
        return value;
    }
}
