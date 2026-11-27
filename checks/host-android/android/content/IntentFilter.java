package android.content;
public class IntentFilter {
    private final java.util.Set<String> actions = new java.util.HashSet<String>();
    public IntentFilter(String action) { actions.add(action); }
    public void addAction(String action) { actions.add(action); }
    public boolean hasAction(String action) { return actions.contains(action); }
}
