package android.view;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
public class View {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    public static int creations;
    private int visibility = VISIBLE;
    private final Context context;
    private ViewGroup parent;
    private ViewGroup.LayoutParams layoutParams;
    private final ViewTreeObserver observer = new ViewTreeObserver();
    public int backgroundColor;
    private OnClickListener listener;
    public View(Context context) { this.context = context; creations++; }
    public Context getContext() { return context; }
    public ViewTreeObserver getViewTreeObserver() { return observer; }
    public ViewGroup getParent() { return parent; }
    public ViewGroup.LayoutParams getLayoutParams() { return layoutParams; }
    public void setLayoutParams(ViewGroup.LayoutParams value) { layoutParams = value; }
    void parent(ViewGroup value) { parent = value; }
    public int getVisibility() { return visibility; }
    public void setVisibility(int value) { visibility = value; }
    public void setBackgroundColor(int value) { backgroundColor = value; }
    public void setFocusable(boolean value) { }
    public void setFocusableInTouchMode(boolean value) { }
    public boolean requestFocus() { return true; }
    public void bringToFront() { }
    public int getWidth() { return 640; }
    public int getHeight() { return layoutParams != null && layoutParams.height > 0 ? layoutParams.height : 480; }
    public int getTop() {
        if (parent == null) return 0;
        int top = 0;
        for (int i = 0; i < parent.indexOfChild(this); i++) top += parent.getChildAt(i).getHeight();
        return top;
    }
    public void setPadding(int left, int top, int right, int bottom) { }
    public boolean post(Runnable task) { return new Handler().post(task); }
    public void setOnClickListener(OnClickListener value) { listener = value; }
    public boolean performClick() { if (listener == null) return false; listener.onClick(this); return true; }
    protected void onDraw(Canvas canvas) { }
    public interface OnClickListener { void onClick(View view); }
}
