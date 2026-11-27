package android.content;
import java.util.HashMap;
public class Intent {
    private final String action;
    private final HashMap<String, Object> extras = new HashMap<String, Object>();
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String key, byte[] bytes) { extras.put(key, bytes); return this; }
    public Intent putExtra(String key, int value) { extras.put(key, value); return this; }
    public int getIntExtra(String key, int fallback) { return extras.containsKey(key) ? (Integer)extras.get(key) : fallback; }
    public byte[] getByteArrayExtra(String key) { return (byte[])extras.get(key); }
}
