package android.content;
import java.io.File;
/** Host-only test boundary; never packaged in the app. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public SharedPreferences getSharedPreferences(String name, int mode) { return new SharedPreferences(); }
    public Context getApplicationContext() { return this; }
    public abstract File getFilesDir();
    public abstract void sendBroadcast(Intent intent);
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
    public void unregisterReceiver(BroadcastReceiver receiver) {}
}
