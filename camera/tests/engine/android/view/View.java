package android.view;
import java.util.ArrayList;
import java.util.List;
public class View {
    public boolean deferred;
    private final List<Runnable> queue = new ArrayList<Runnable>();
    public boolean post(Runnable runnable) {
        if (deferred) queue.add(runnable); else runnable.run();
        return true;
    }
    public void drain() {
        while (!queue.isEmpty()) queue.remove(0).run();
    }
}
