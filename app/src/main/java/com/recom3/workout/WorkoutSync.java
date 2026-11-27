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
    private final ReconWorkoutTransport transport;
    private final DynamicResults dynamic;
    private final ConcurrentHashMap<String, DynamicDelivery.State> dynamicStates = new ConcurrentHashMap<String, DynamicDelivery.State>();
    private final java.util.Map<String, DynamicDelivery.Attempt> revisions = new java.util.HashMap<String, DynamicDelivery.Attempt>();
    private boolean retryScheduled, retryAgain;
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
        dynamic = new DynamicResults(files);
        transport = new ReconWorkoutTransport(context, new Runnable() {
            public void run() { retrySaved(); }
        });
        batch = new SyncBatch(queue, transport, this);
    }
    public void state(String id, SyncQueue.State state) { states.put(id, state); }
    SyncQueue.State state(String id) {
        SyncQueue.State state = states.get(id);
        return state == null ? SyncQueue.State.PENDING : state;
    }

    DynamicDelivery.State dynamicState(String id) {
        DynamicDelivery.State value = dynamicStates.get(id);
        return value == null ? DynamicDelivery.State.LOCAL : value;
    }
    // Called before a CSV edit: no queued snapshot or active attempt may submit older bytes afterwards.
    synchronized void invalidate(String id) {
        DynamicDelivery.Attempt old = revisions.get(id);
        if (old != null) old.cancel();
        DynamicDelivery.Attempt blocked = new DynamicDelivery.Attempt(); blocked.cancel();
        revisions.put(id, blocked);
        dynamicStates.put(id, DynamicDelivery.State.LOCAL);
    }
    synchronized void retrySaved() {
        if (retryScheduled) { retryAgain = true; return; }
        retryScheduled = true;
        worker.execute(new Runnable() {
            public void run() {
                try { retry(); }
                finally {
                    synchronized (WorkoutSync.this) {
                        retryScheduled = false;
                        if (retryAgain) { retryAgain = false; retrySaved(); }
                    }
                }
            }
        });
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
        final DynamicDelivery.Attempt revision;
        synchronized (this) {
            DynamicDelivery.Attempt old = revisions.get(snapshot.id);
            if (old != null) old.cancel();
            revision = new DynamicDelivery.Attempt(); revisions.put(snapshot.id, revision);
        }
        worker.execute(new Runnable() {
            public void run() {
                try {
                    if (!snapshot.legacy()) {
                        synchronized (revision) {
                            if (!revision.valid()) return;
                            revision.payloadHash = dynamic.enqueue(snapshot).hash;
                            dynamicStates.put(snapshot.id, DynamicDelivery.State.PENDING);
                        }
                        retry();
                        return;
                    }
                    SyncQueue.Entry entry = queue.enqueue(snapshot);
                    state(entry.id, entry.state);
                    retry();
                } catch (IOException failure) {
                    if (!snapshot.legacy() && revision.valid()) dynamicStates.put(snapshot.id, DynamicDelivery.State.ERROR);
                    state(snapshot.id, SyncQueue.State.FAILED);
                    Log.w("WorkoutIce3Sync", "Queue save pending; completed CSV retained");
                } catch (SecurityException failure) {
                    if (!snapshot.legacy() && revision.valid()) dynamicStates.put(snapshot.id, DynamicDelivery.State.ERROR);
                    state(snapshot.id, SyncQueue.State.FAILED);
                    Log.w("WorkoutIce3Sync", "Queue storage unavailable; completed CSV retained");
                }
            }
        });
    }
    private void recoverUnqueued() {
        java.util.Map<String, DynamicDelivery.Attempt> pending;
        synchronized (this) { pending = new java.util.HashMap<String, DynamicDelivery.Attempt>(revisions); }
        for (java.util.Map.Entry<String, DynamicDelivery.Attempt> item : pending.entrySet()) {
            DynamicDelivery.Attempt revision = item.getValue();
            if (!revision.valid() || revision.payloadHash != null) continue;
            try {
                java.io.File csv = new java.io.File(files, "workouts/workout-" + item.getKey() + ".csv");
                java.io.File backup = new java.io.File(csv.getPath() + ".bak");
                if (backup.exists()) csv = backup;
                WorkoutSession saved = WorkoutSession.read(TemplateJson.utf8(SyncFiles.read(csv, 262144)));
                synchronized (revision) {
                    if (revision.valid() && saved.id.equals(item.getKey()) && saved.complete() && !saved.legacy())
                        revision.payloadHash = dynamic.enqueue(saved).hash;
                }
            } catch (IOException unavailable) { dynamicStates.put(item.getKey(), DynamicDelivery.State.ERROR); }
            catch (SecurityException unavailable) { dynamicStates.put(item.getKey(), DynamicDelivery.State.ERROR); }
        }
    }
    private void retryDynamic() throws IOException, InterruptedException {
        recoverUnqueued(); // Only a failed/new queue write; network retries never encode an existing entry.
        long deadline = System.nanoTime() + 30000000000L;
        int attempts = 0;
        java.util.List<DynamicResults.Entry> entries = dynamic.entries();
        for (String id : dynamic.unreadable()) dynamicStates.put(id, DynamicDelivery.State.ERROR);
        for (DynamicResults.Entry entry : entries) {
            DynamicDelivery.Attempt revision;
            synchronized (this) {
                revision = revisions.get(entry.id);
                if (revision == null) {
                    revision = new DynamicDelivery.Attempt(); revision.payloadHash = entry.hash;
                    revisions.put(entry.id, revision);
                }
            }
            if (!revision.valid() || !entry.hash.equals(revision.payloadHash)) continue;
            try {
                // A reopened/reset incomplete CSV must not resend a formerly completed revision after process death.
                java.io.File csv = new java.io.File(files, "workouts/workout-" + entry.id + ".csv");
                java.io.File backup = new java.io.File(csv.getPath() + ".bak");
                if (backup.exists()) csv = backup;
                if (csv.exists()) {
                    WorkoutSession saved = WorkoutSession.read(TemplateJson.utf8(SyncFiles.read(csv, 262144)));
                    if (!saved.complete()) { dynamicStates.put(entry.id, DynamicDelivery.State.LOCAL); continue; }
                    if (saved.legacy()) { dynamicStates.put(entry.id, DynamicDelivery.State.ERROR); continue; }
                }
                if (dynamic.confirmed(entry)) { dynamicStates.put(entry.id, DynamicDelivery.State.STORED); continue; }
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining <= 0 || attempts++ >= 20) break;
                dynamicStates.put(entry.id, DynamicDelivery.State.SENDING);
                DynamicDelivery.State result;
                try { result = DynamicDelivery.send(dynamic, entry, transport, revision, Math.min(10000L, remaining)); }
                catch (IOException failure) { result = DynamicDelivery.State.ERROR; }
                catch (SecurityException failure) { result = DynamicDelivery.State.ERROR; }
                synchronized (revision) {
                    if (revision.valid()) dynamicStates.put(entry.id, result);
                }
            } catch (IOException unreadable) {
                if (revision.valid()) dynamicStates.put(entry.id, DynamicDelivery.State.ERROR);
            } catch (SecurityException unavailable) {
                if (revision.valid()) dynamicStates.put(entry.id, DynamicDelivery.State.ERROR);
            }
        }
    }
    private void retry() {
        try { retryDynamic(); }
        catch (IOException failure) { Log.w("WorkoutIce3Sync", "Dynamic results retained; queue unavailable"); }
        catch (SecurityException failure) { Log.w("WorkoutIce3Sync", "Dynamic results retained; storage unavailable"); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        try {
            batch.run();
            if (queue.hadReadFailure()) Log.w("WorkoutIce3Sync", "Unreadable queue entry retained for recovery");
        } catch (IOException failure) { Log.w("WorkoutIce3Sync", "Sync pending; local results retained"); }
        catch (SecurityException failure) { Log.w("WorkoutIce3Sync", "Sync unavailable; local results retained"); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

}
