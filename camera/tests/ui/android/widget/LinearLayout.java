package android.widget;
import android.content.Context;
import android.view.ViewGroup;
public class LinearLayout extends ViewGroup {
    public static final int VERTICAL = 1, HORIZONTAL = 0;
    public LinearLayout(Context context) { super(context); }
    public void setOrientation(int orientation) { }
    public void setGravity(int gravity) { }
    public static class LayoutParams extends ViewGroup.LayoutParams {
        public int bottomMargin;
        public float weight;
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(int width, int height, float weight) { super(width, height); this.weight = weight; }
        public void setMargins(int left, int top, int right, int bottom) { }
    }
}
