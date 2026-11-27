package android.content;
import java.io.File;
/** Host-only test boundary; never packaged in the app. */
public abstract class Context {
    public Context getApplicationContext() { return this; }
    public abstract File getFilesDir();
    public abstract void sendBroadcast(Intent intent);
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
    public void unregisterReceiver(BroadcastReceiver receiver) {}
}
