package android.widget;
import android.content.Context;
public class ScrollView extends FrameLayout {
    private int scrollY;
    public ScrollView(Context context) { super(context); }
    public void setFillViewport(boolean value) { }
    public int getScrollY() { return scrollY; }
    public void scrollTo(int x, int y) { scrollY = y; }
    public void smoothScrollBy(int x, int y) { scrollY += y; }
}
