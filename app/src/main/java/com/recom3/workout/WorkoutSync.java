package com.recom3.workout;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Process-scoped bounded jobs survive Activity closure; CSV archives recover a killed process. */
final class WorkoutSync implements SyncBatch.Observer {
    private static WorkoutSync instance;
    private final File files;
    private final SyncQueue queue;
    private final SyncBatch batch;
    private boolean startupScheduled;
    private final ConcurrentHashMap<String, SyncQueue.State> states = new ConcurrentHashMap<String, SyncQueue.State>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable task) { return new Thread(task, "WorkoutIce3Sync"); }
    });

    static synchronized WorkoutSync get(Context context) {
        if (instance == null) instance = new WorkoutSync(context.getApplicationContext());
        return instance;
    }
    private WorkoutSync(Context context) {
        files = context.getFilesDir();
        queue = new SyncQueue(files);
        batch = new SyncBatch(queue, new ReconWorkoutTransport(context), this);
    }
    public void state(String id, SyncQueue.State state) { states.put(id, state); }
    SyncQueue.State state(String id) {
        SyncQueue.State state = states.get(id);
        return state == null ? SyncQueue.State.PENDING : state;
    }

    synchronized void startup() {
        if (startupScheduled) return;
        startupScheduled = true;
        worker.execute(new Runnable() {
            public void run() {
                try {
                    if (SyncRecovery.recover(files, queue, WorkoutSync.this) != 0)
                        Log.w("WorkoutIce3Sync", "Archive not queued; original CSV retained");
                    retry();
                } finally {
                    synchronized (WorkoutSync.this) { startupScheduled = false; }
                }
            }
        });
    }
    void completed(WorkoutSession session) {
        final WorkoutSession snapshot = new WorkoutSession(session);
        worker.execute(new Runnable() {
            public void run() {
                try {
                    SyncQueue.Entry entry = queue.enqueue(snapshot);
                    state(entry.id, entry.state);
                    retry();
                } catch (IOException failure) {
                    state(snapshot.id, SyncQueue.State.FAILED);
                    Log.w("WorkoutIce3Sync", "Queue save pending; completed CSV retained");
                } catch (SecurityException failure) {
                    state(snapshot.id, SyncQueue.State.FAILED);
                    Log.w("WorkoutIce3Sync", "Queue storage unavailable; completed CSV retained");
                }
            }
        });
    }
    private void retry() {
        try {
            batch.run();
            if (queue.hadReadFailure()) Log.w("WorkoutIce3Sync", "Unreadable queue entry retained for recovery");
        } catch (IOException failure) { Log.w("WorkoutIce3Sync", "Sync pending; local results retained"); }
        catch (SecurityException failure) { Log.w("WorkoutIce3Sync", "Sync unavailable; local results retained"); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

}
