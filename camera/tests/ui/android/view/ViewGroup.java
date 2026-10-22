package android.view;
import android.content.Context;
import java.util.ArrayList;
public class ViewGroup extends View {
    private final ArrayList<View> children = new ArrayList<View>();
    public int removals;
    public ViewGroup(Context context) { super(context); }
    public void addView(View view) {
        if (view.getParent() != null) throw new IllegalStateException("already_parented");
        children.add(view); view.parent(this);
    }
    public void addView(View view, LayoutParams params) { view.setLayoutParams(params); addView(view); }
    public void removeView(View view) { children.remove(view); view.parent(null); }
    public void removeAllViews() {
        for (View child : children) child.parent(null);
        children.clear(); removals++;
    }
    public View getChildAt(int index) { return children.get(index); }
    public int getChildCount() { return children.size(); }
    public int indexOfChild(View view) { return children.indexOf(view); }
    public static class LayoutParams {
        public int width, height;
        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
    }
}
