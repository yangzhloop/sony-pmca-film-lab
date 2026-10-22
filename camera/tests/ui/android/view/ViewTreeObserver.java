package android.view;
import android.os.Handler;
import java.util.ArrayList;
public class ViewTreeObserver {
    private final ArrayList<OnGlobalLayoutListener> listeners = new ArrayList<OnGlobalLayoutListener>();
    public boolean isAlive() { return true; }
    public void addOnGlobalLayoutListener(final OnGlobalLayoutListener listener) {
        listeners.add(listener);
        new Handler().post(new Runnable() { public void run() {
            if (listeners.contains(listener)) listener.onGlobalLayout();
        }});
    }
    public void removeGlobalOnLayoutListener(OnGlobalLayoutListener listener) { listeners.remove(listener); }
    public interface OnGlobalLayoutListener { void onGlobalLayout(); }
}
