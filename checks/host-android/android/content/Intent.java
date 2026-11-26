package android.content;
import java.util.HashMap;
public class Intent {
    private final String action;
    private final HashMap<String, byte[]> extras = new HashMap<String, byte[]>();
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String key, byte[] bytes) { extras.put(key, bytes); return this; }
    public byte[] getByteArrayExtra(String key) { return extras.get(key); }
}
