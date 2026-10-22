package android.widget;
import android.content.Context;
import android.view.View;
import android.text.TextUtils;
public class TextView extends View {
    private CharSequence text = "";
    public int textColor;
    public int maxLines;
    public TextUtils.TruncateAt ellipsize;
    public TextView(Context context) { super(context); }
    public void setText(CharSequence value) { text = value; }
    public CharSequence getText() { return text; }
    public void setTextColor(int value) { textColor = value; }
    public void setTextSize(int unit, float size) { }
    public void setGravity(int gravity) { }
    public void setMaxLines(int count) { maxLines = count; }
    public void setMaxWidth(int width) { }
    public void setEllipsize(TextUtils.TruncateAt where) { ellipsize = where; }
}
