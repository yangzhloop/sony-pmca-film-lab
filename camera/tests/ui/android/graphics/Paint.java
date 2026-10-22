package android.graphics;
public class Paint {
    public enum Style { STROKE, FILL }
    public enum Align { CENTER }
    public Paint(int flags) { }
    public void setColor(int color) { }
    public void setStrokeWidth(float value) { }
    public void setStyle(Style value) { }
    public void setTextSize(float value) { }
    public void setTextAlign(Align value) { }
}
