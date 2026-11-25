package com.recom3.workout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import java.io.IOException;

/** Uses the stock Recon OBJECT owner; never opens or replaces its Bluetooth sockets. */
final class ReconWorkoutTransport implements SyncBatch.Transport {
    private final Context context;
    ReconWorkoutTransport(Context context) { this.context = context.getApplicationContext(); }

    public boolean sendAndAwait(final SyncQueue.Entry entry, final int request, long timeoutMs)
            throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Sync must run off the main thread");
        final Object lock = new Object();
        final boolean[] received = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            public void onReceive(Context ignored, Intent intent) {
                try {
                    if (!WorkoutWire.STORED.equals(intent.getAction())) return;
                    WorkoutWire response = WorkoutWire.decode(intent.getByteArrayExtra("message"));
                    if (!response.confirms(entry, request)) return;
                    synchronized (lock) { received[0] = true; lock.notifyAll(); }
                } catch (IOException invalid) { /* Unrelated or malformed message is not an acknowledgement. */ }
                catch (RuntimeException invalid) { /* Includes malformed broadcast extras. */ }
            }
        };
        boolean registered = false;
        try {
            byte[] frame = new WorkoutWire(request, "com.recom3.workout", WorkoutWire.SESSION,
                    entry.hash, entry.json.getBytes("UTF-8")).encode();
            context.registerReceiver(receiver, new IntentFilter(WorkoutWire.STORED));
            registered = true;
            Intent intent = new Intent("com.reconinstruments.mobilesdk.hudconnectivity.channel.object");
            intent.putExtra("HUDConnectivityMessage", frame);
            context.sendBroadcast(intent);
            long deadline = System.nanoTime() + timeoutMs * 1000000L;
            synchronized (lock) {
                while (!received[0]) {
                    long remaining = (deadline - System.nanoTime()) / 1000000L;
                    if (remaining <= 0) return false;
                    lock.wait(remaining);
                }
                return true;
            }
        } catch (SecurityException failure) { throw new IOException("Recon OBJECT unavailable", failure); }
        finally { if (registered) context.unregisterReceiver(receiver); }
    }
}
