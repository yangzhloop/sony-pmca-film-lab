package android.graphics;
public class Canvas {
    public int save() { return 1; }
    public void scale(float x, float y) { }
    public void drawRect(float left, float top, float right, float bottom, Paint paint) { }
    public void drawLine(float x1, float y1, float x2, float y2, Paint paint) { }
    public void drawText(String text, float x, float y, Paint paint) { }
    public void restore() { }
}
