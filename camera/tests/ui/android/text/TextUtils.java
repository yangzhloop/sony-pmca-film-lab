package android.text;
public final class TextUtils {
    public enum TruncateAt { END }
    public static boolean equals(CharSequence left, CharSequence right) {
        return left == right || (left != null && right != null && left.toString().equals(right.toString()));
    }
}
