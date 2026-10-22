package android.content.res;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
public class AssetManager {
    private final File directory;
    public AssetManager(File value) { directory = value; }
    public InputStream open(String path) throws IOException { return new FileInputStream(new File(directory, path)); }
}
