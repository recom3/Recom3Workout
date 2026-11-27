package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Align { LEFT, RIGHT, CENTER }
    public Paint(int flags) {}
    public void setColor(int color) {}
    public void setTextSize(float size) {}
    public void setTypeface(Typeface face) {}
    public void setTextAlign(Align align) {}
    public float measureText(String value) { return value.length() * 8; }
}
