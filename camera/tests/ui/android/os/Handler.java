package android.os;
import java.util.ArrayList;
public class Handler {
    private static final ArrayList<Task> tasks = new ArrayList<Task>();
    private static final class Task {
        Handler owner; Runnable runnable; long due;
        Task(Handler owner, Runnable runnable, long due) { this.owner = owner; this.runnable = runnable; this.due = due; }
    }
    public Handler() { }
    public Handler(Looper looper) { }
    public boolean post(Runnable runnable) { return postDelayed(runnable, 0); }
    public boolean postDelayed(Runnable runnable, long delay) {
        synchronized (tasks) { tasks.add(new Task(this, runnable, SystemClock.now + delay)); tasks.notifyAll(); }
        return true;
    }
    public void removeCallbacks(Runnable runnable) {
        synchronized (tasks) {
            for (int i = tasks.size() - 1; i >= 0; i--)
                if (tasks.get(i).owner == this && tasks.get(i).runnable == runnable) tasks.remove(i);
        }
    }
    public void removeCallbacksAndMessages(Object token) {
        synchronized (tasks) {
            for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i).owner == this) tasks.remove(i);
        }
    }
    public static void advance(long milliseconds) { SystemClock.now += milliseconds; drain(); }
    public static void drain() {
        int executed = 0;
        while (true) {
            Task chosen = null;
            synchronized (tasks) {
                for (Task task : tasks) {
                    if (task.due <= SystemClock.now && (chosen == null || task.due < chosen.due)) chosen = task;
                }
                if (chosen == null) return;
                tasks.remove(chosen);
            }
            if (++executed > 10000) throw new AssertionError("handler_loop");
            chosen.runnable.run();
        }
    }
    public static void reset() { synchronized (tasks) { tasks.clear(); SystemClock.now = 0; } }
}
