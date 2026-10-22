import com.filmlab.app.PinyinDictionary;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;

public final class PinyinDictionaryTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(!PinyinDictionary.available(), "starts unloaded");
        check(PinyinDictionary.load(new FileInputStream(args[0])), "loads bundled data");
        check(PinyinDictionary.lookup("YU").indexOf('\u8bed') >= 0, "includes missing glyph yu");
        check(PinyinDictionary.lookup("yu").indexOf('\u96e8') >= 0, "rain");
        check(PinyinDictionary.lookup("hou").indexOf('\u540e') >= 0, "after");
        check(PinyinDictionary.lookup("jie").indexOf('\u8857') >= 0, "street");
        check(PinyinDictionary.lookup("jiao").indexOf('\u89d2') >= 0, "corner");
        check(PinyinDictionary.lookup("lv").indexOf('\u7eff') >= 0, "umlaut v");
        check(PinyinDictionary.lookup("l\u00fc").equals(PinyinDictionary.lookup("lv")), "umlaut alias");
        check(PinyinDictionary.lookup("unknown").length() == 0, "unknown reading");
        check(PinyinDictionary.lookup("yu\tprivate").length() == 0, "reject invalid query");
        String saved = PinyinDictionary.lookup("yu");
        check(!PinyinDictionary.load(new ByteArrayInputStream("yu\tprivate\n".getBytes("UTF-8"))), "reject corrupt data");
        check(PinyinDictionary.lookup("yu").equals(saved), "failed load preserves dictionary");
        check(!PinyinDictionary.load(new ByteArrayInputStream(new byte[0])), "reject empty file");
        System.out.println("PinyinDictionaryTest: " + assertions + " assertions passed");
    }
}
