package android.hardware;
/** Tripwires: recipe operations must use Sony's maintained controller, never open/capture/read directly. */
public class Camera {
    public static int opens, captures, reads;
    public static Camera open() { opens++; throw new AssertionError("unexpected camera open"); }
    public Object getParameters() { reads++; throw new AssertionError("unexpected direct read"); }
    public void takePicture() { captures++; throw new AssertionError("unexpected capture"); }
}
