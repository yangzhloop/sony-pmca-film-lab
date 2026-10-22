package android.view;
import android.content.Context;
import android.widget.FrameLayout;
public class Window {
    private final View decor;
    public Window(Context context) { decor = new FrameLayout(context); }
    public View getDecorView() { return decor; }
}
