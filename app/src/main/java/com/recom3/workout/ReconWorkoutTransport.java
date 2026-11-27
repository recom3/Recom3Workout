package com.recom3.workout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import java.io.IOException;

/** Uses the stock Recon OBJECT owner; never opens or replaces its Bluetooth sockets. */
final class ReconWorkoutTransport implements SyncBatch.Transport, DynamicDelivery.Transport {
    static final String LINK_STATE = "HUD_STATE_CHANGED";
    private final Context context;
    private final Object link = new Object();
    private long generation;
    private int linkState = -1; // The stock broadcast is not sticky. Unknown allows only a fresh probe.
    private DynamicDelivery.Attempt active;
    private static final java.util.concurrent.atomic.AtomicInteger REQUEST =
            new java.util.concurrent.atomic.AtomicInteger(new java.security.SecureRandom().nextInt(0x3fffffff) + 1);
    private static int request() throws IOException {
        int key = REQUEST.incrementAndGet();
        if (key <= 0) throw new IOException("Request keys exhausted; restart required");
        return key;
    }
    ReconWorkoutTransport(Context context) { this(context, null); }
    ReconWorkoutTransport(Context context, final Runnable reconnected) {
        this.context = context.getApplicationContext();
        IntentFilter filter = new IntentFilter(LINK_STATE);
        filter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        filter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        filter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        this.context.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context ignored, Intent intent) {
                int state = LINK_STATE.equals(intent.getAction()) ? intent.getIntExtra("state", -1) : -1;
                synchronized (link) {
                    generation++;
                    linkState = state;
                }
                if (state == 2 && reconnected != null) reconnected.run();
            }
        }, filter);
    }

    /** No capability cache: each result probes the current observable stock connection epoch. */
    public DynamicDelivery.State deliver(final DynamicResults.Entry entry, final DynamicDelivery.Attempt attempt,
                                         long timeoutMs) throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Sync must run off the main thread");
        if (entry.size() > 20000) return DynamicDelivery.State.OVERSIZED;
        final long epoch;
        synchronized (link) {
            if (active != null) throw new IOException("A result operation is already active");
            if (linkState != -1 && linkState != 2) return DynamicDelivery.State.ERROR;
            active = attempt; epoch = generation;
        }
        final Object lock = new Object();
        final WorkoutWire[] expected = {null};
        final long[] deadline = {0};
        final boolean[] received = {false};
        final boolean[] closed = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            public void onReceive(Context ignored, Intent intent) {
                try {
                    synchronized (lock) {
                        WorkoutWire sent = expected[0];
                        if (closed[0] || sent == null || !attempt.valid() || !live(epoch)
                                || System.nanoTime() >= deadline[0]) return;
                        String action = sent.action.equals(WorkoutWire.CAPABILITIES)
                                ? WorkoutWire.CAPABILITIES_RESULT : WorkoutWire.STORED;
                        if (!action.equals(intent.getAction())) return;
                        WorkoutWire reply = WorkoutWire.decode(intent.getByteArrayExtra("message"));
                        if (!action.equals(reply.action) || !"com.recom3.snow3".equals(reply.sender)
                                || reply.request != sent.request || reply.request <= 0) return;
                        if (sent.action.equals(WorkoutWire.CAPABILITIES)) {
                            // Exact advertised bytes validate the complete structure, field types and budgets.
                            if (!reply.info.isEmpty() || !java.util.Arrays.equals(reply.data,
                                    WorkoutWire.CAPABILITIES_JSON.getBytes("UTF-8"))) return;
                        } else if (!entry.hash.equals(reply.info) || !entry.id.equals(TemplateJson.utf8(reply.data))) return;
                        received[0] = true; lock.notifyAll();
                    }
                } catch (IOException invalid) { /* No receipt for malformed or unrelated bytes. */ }
                catch (RuntimeException invalid) { /* Includes malformed broadcast extras. */ }
            }
        };
        boolean registered = false;
        long budget = Math.min(10000L, Math.max(1L, timeoutMs));
        long end = System.nanoTime() + budget * 1000000L;
        try {
            IntentFilter filter = new IntentFilter(WorkoutWire.CAPABILITIES_RESULT);
            filter.addAction(WorkoutWire.STORED);
            context.registerReceiver(receiver, filter); registered = true;
            WorkoutWire probe = new WorkoutWire(request(), "com.recom3.workout", WorkoutWire.CAPABILITIES, "", new byte[0]);
            if (!exchange(probe, attempt, epoch, lock, expected, deadline, received, Math.min(end, System.nanoTime() + 3000000000L)))
                return attempt.valid() && live(epoch) ? DynamicDelivery.State.INCOMPATIBLE : DynamicDelivery.State.ERROR;
            WorkoutWire result = new WorkoutWire(request(), "com.recom3.workout", WorkoutWire.SESSION, entry.hash, entry.bytes());
            if (!exchange(result, attempt, epoch, lock, expected, deadline, received, end))
                return attempt.valid() && live(epoch) ? DynamicDelivery.State.TIMEOUT : DynamicDelivery.State.ERROR;
            return attempt.valid() && live(epoch) ? DynamicDelivery.State.STORED : DynamicDelivery.State.ERROR;
        } catch (SecurityException failure) { throw new IOException("Recon OBJECT unavailable", failure); }
        finally {
            synchronized (lock) { closed[0] = true; expected[0] = null; }
            try { if (registered) context.unregisterReceiver(receiver); }
            finally { synchronized (link) { active = null; } }
        }
    }
    private boolean live(long epoch) {
        synchronized (link) { return generation == epoch && (linkState == -1 || linkState == 2); }
    }
    private boolean exchange(WorkoutWire sent, DynamicDelivery.Attempt attempt, long epoch, Object lock,
                             WorkoutWire[] expected, long[] deadline, boolean[] received, long end)
            throws IOException, InterruptedException {
        byte[] frame = sent.encode(); // Full 25,600-byte envelope bound, before submission.
        synchronized (attempt) {
            synchronized (lock) {
                if (!attempt.valid() || !live(epoch) || System.nanoTime() >= end) return false;
                expected[0] = sent; received[0] = false; deadline[0] = end;
                Intent intent = new Intent("com.reconinstruments.mobilesdk.hudconnectivity.channel.object");
                intent.putExtra("HUDConnectivityMessage", frame);
                context.sendBroadcast(intent);
            }
        }
        synchronized (lock) {
            while (attempt.valid() && live(epoch) && !received[0]) {
                long remaining = (end - System.nanoTime()) / 1000000L;
                if (remaining <= 0) return false;
                lock.wait(Math.min(50L, remaining));
            }
            return attempt.valid() && live(epoch) && received[0] && System.nanoTime() < end;
        }
    }

    static void sendObject(Context context, WorkoutWire frame) throws IOException {
        Intent intent = new Intent("com.reconinstruments.mobilesdk.hudconnectivity.channel.object");
        intent.putExtra("HUDConnectivityMessage", frame.encode());
        context.sendBroadcast(intent);
    }

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
