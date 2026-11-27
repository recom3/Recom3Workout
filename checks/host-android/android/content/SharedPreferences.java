package android.content;
/** Minimal host-only legacy calibration boundary. */
public class SharedPreferences {
    public int getInt(String key, int fallback) { return fallback; }
}
