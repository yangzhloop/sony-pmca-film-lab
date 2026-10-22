package android.app;
import android.content.Context;
import android.view.Window;
import testsupport.Probe;
public class Activity extends Context {
    private final Window window = new Window(this);
    public int finishCalls;
    public boolean finishAccepted = true;
    public Window getWindow() { return window; }
    public boolean isFinishing() { return finishCalls > 0 && finishAccepted; }
    public void finish() { Probe.events.add("finish"); finishCalls++; }
}
