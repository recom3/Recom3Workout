package com.recom3.workout;

import java.io.*;
import java.util.*;

public final class SyncChecks {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static WorkoutSession complete(long time, int sets) {
        Profile[] profiles = new Profile[Exercise.values().length];
        for (Exercise exercise : Exercise.values()) {
            Profile p = profiles[exercise.ordinal()] = new Profile(exercise);
            p.change(Profile.Setting.TARGET, 10); p.change(Profile.Setting.SETS, sets);
        }
        WorkoutSession session = new WorkoutSession(profiles); session.begin(time);
        for (int set = 1; set <= sets; set++) {
            for (Exercise exercise : Exercise.values()) session.record(exercise, set, 10, ++time, "TARGET");
        }
        return session;
    }
    private static File root(File parent, String name) throws IOException {
        File root = new File(parent, name + "-" + UUID.randomUUID());
        if (!root.mkdirs()) throw new IOException("Test directory unavailable");
        return root;
    }
    private static final class Observer implements SyncBatch.Observer {
        final Map<String, SyncQueue.State> states = new HashMap<String, SyncQueue.State>();
        public void state(String id, SyncQueue.State state) { states.put(id, state); }
    }
    private static final class Phone implements SyncBatch.Transport {
        final List<String> order = new ArrayList<String>();
        final Set<String> stored = new HashSet<String>();
        final Map<String, String> hashes = new HashMap<String, String>();
        boolean disconnected, throwFailure, loseAck;
        public boolean sendAndAwait(SyncQueue.Entry entry, int request, long timeoutMs) throws IOException {
            order.add(entry.id);
            if (throwFailure) throw new IOException("Synthetic transfer failure");
            if (disconnected) return false;
            stored.add(entry.id); hashes.put(entry.id, entry.hash); // Durable fake receiver upsert.
            if (loseAck) return false;
            WorkoutWire ack = new WorkoutWire(request, "test-receiver", WorkoutWire.STORED,
                    entry.hash, entry.id.getBytes("UTF-8"));
            return WorkoutWire.decode(ack.encode()).confirms(entry, request);
        }
    }
    private static void batch(SyncQueue queue, Phone phone, Observer observer) throws Exception {
        new SyncBatch(queue, phone, observer).run();
    }
    public static void main(String[] args) throws Exception {
        File base = root(new File(args[0]), "sync-checks");
        WorkoutSession session = complete(1795600000000L, 1);
        String csv = session.csv();
        String json = WorkoutJson.encode(session);
        check(json.contains("\"schemaVersion\":1") && json.contains(session.id)
                && json.contains("2026-") && json.contains(".003Z")
                && !json.contains("workoutId") && !json.contains("templateRevision"),
                "versioned JSON uses the stable session ID and UTC without invented template fields");
        session.record(Exercise.SQUAT, 1, 8, WorkoutJson.completedAt(session), "CORRECTED");
        String corrected = WorkoutJson.encode(session);
        check(corrected.contains("\"repsCompleted\":8") && corrected.contains("CORRECTED"),
                "payload preserves actual corrected repetitions and completion reasons");
        session = WorkoutSession.read(csv);
        check(WorkoutJson.encode(session).equals(json), "JSON is stable after CSV restore and across retries");
        WorkoutSession max = complete(1795600000000L, 20);
        SyncQueue.Entry maxEntry = new SyncQueue(base).enqueue(max);
        check(new WorkoutWire(1, "com.recom3.workout", WorkoutWire.SESSION, maxEntry.hash,
                maxEntry.json.getBytes("UTF-8")).encode().length <= WorkoutWire.MAX_FRAME,
                "maximum sixty recorded sets fit the existing 25 KiB OBJECT envelope");
        File successful = root(base, "success"); SyncQueue queue = new SyncQueue(successful);
        SyncQueue.Entry entry = queue.enqueue(session);
        check(entry.state == SyncQueue.State.PENDING, "completed workout is pending before transfer");
        Phone phone = new Phone(); Observer observer = new Observer();
        batch(queue, phone, observer);
        check(queue.entries().get(0).state == SyncQueue.State.SYNCED && phone.stored.contains(session.id),
                "only receiver storage confirmation marks a workout synced");
        batch(queue, phone, observer); queue.enqueue(session); batch(queue, phone, observer);
        check(phone.order.size() == 1 && queue.entries().size() == 1,
                "synced sessions are retained and neither enqueued nor transmitted twice");
        File offline = root(base, "offline"); queue = new SyncQueue(offline); queue.enqueue(session);
        phone = new Phone(); phone.disconnected = true; batch(queue, phone, observer);
        check(queue.entries().get(0).state == SyncQueue.State.FAILED, "disconnected ICE3 keeps a retryable session");
        queue = new SyncQueue(offline);
        check(queue.entries().get(0).json.equals(json), "closing and restarting preserves pending JSON byte for byte");
        phone.disconnected = false; batch(queue, phone, observer);
        check(queue.entries().get(0).state == SyncQueue.State.SYNCED,
                "restart retry confirms the retained session and clears its pending state");
        File failure = root(base, "failure"); queue = new SyncQueue(failure); queue.enqueue(session);
        phone = new Phone(); phone.throwFailure = true; batch(queue, phone, observer);
        check(queue.entries().get(0).state == SyncQueue.State.FAILED, "transfer exception cannot discard the payload");
        File lostAck = root(base, "lost-ack"); queue = new SyncQueue(lostAck); queue.enqueue(session);
        phone = new Phone(); phone.loseAck = true; batch(queue, phone, observer);
        check(queue.entries().get(0).state == SyncQueue.State.FAILED && phone.stored.size() == 1,
                "sending and storing bytes without an ACK never marks the sender synced");
        phone.loseAck = false; batch(new SyncQueue(lostAck), phone, observer);
        check(phone.stored.size() == 1 && phone.order.size() == 2,
                "lost-ACK retry keeps the same ID and deduplicates at the receiver");
        File multiple = root(base, "multiple"); queue = new SyncQueue(multiple);
        WorkoutSession earlier = complete(10000, 1), later = complete(20000, 1);
        queue.enqueue(later); queue.enqueue(earlier); phone = new Phone(); batch(queue, phone, observer);
        check(phone.order.equals(Arrays.asList(earlier.id, later.id)), "pending sessions send in completion order");
        SyncQueue.Entry stale = entry;
        check(!new WorkoutWire(99, "phone", WorkoutWire.STORED, stale.hash, stale.id.getBytes("UTF-8"))
                .confirms(stale, 100), "wrong request ACK is rejected");
        check(!new WorkoutWire(100, "phone", WorkoutWire.STORED, "wrong-hash", stale.id.getBytes("UTF-8"))
                .confirms(stale, 100), "wrong checksum ACK is rejected");
        check(!new WorkoutWire(100, "phone", WorkoutWire.STORED, stale.hash, earlier.id.getBytes("UTF-8"))
                .confirms(stale, 100), "wrong session ACK is rejected");
        check(!new WorkoutWire(100, "phone", WorkoutWire.SESSION, stale.hash, stale.id.getBytes("UTF-8"))
                .confirms(stale, 100), "transport and application actions cannot substitute for a stored ACK");
        boolean malformed = false;
        try { WorkoutWire.decode(new byte[20]); } catch (IOException expected) { malformed = true; }
        check(malformed, "malformed Recon frame is rejected");
        File atomic = root(base, "atomic"); queue = new SyncQueue(atomic); entry = queue.enqueue(session);
        File record = new File(atomic, "ice3-sync/" + session.id + ".queue");
        byte[] committed = SyncFiles.read(record, 22000);
        File backup = new File(record.getPath() + ".bak");
        FileOutputStream output = new FileOutputStream(backup); output.write(committed); output.close();
        output = new FileOutputStream(record); output.write(1); output.close();
        check(new SyncQueue(atomic).entries().get(0).hash.equals(entry.hash),
                "interrupted queue write restores the last committed payload and state");
        if (!record.renameTo(backup)) throw new IOException("Cannot simulate missing primary");
        check(new SyncQueue(atomic).entries().size() == 1, "backup-only queue entry survives reboot");
        File corrupt = new File(atomic, "ice3-sync/" + UUID.randomUUID() + ".queue");
        output = new FileOutputStream(corrupt); output.write(1); output.close();
        queue = new SyncQueue(atomic);
        check(queue.entries().size() == 1 && queue.hadReadFailure() && corrupt.isFile(),
                "corrupt entry is retained while healthy sessions remain retryable");
        File csvRoot = root(base, "csv"); SessionStore store = new SessionStore(csvRoot, new File(csvRoot, "exports"));
        store.save(session); File external = store.export(session);
        File internal = new File(csvRoot, "workouts/workout-" + session.id + ".csv");
        byte[] internalBefore = SyncFiles.read(internal, 262144), externalBefore = SyncFiles.read(external, 262144);
        queue = new SyncQueue(csvRoot);
        check(SyncRecovery.recover(csvRoot, queue, observer) == 0 && queue.entries().size() == 1,
                "startup repairs a crash after the final CSV save and before queue creation");
        phone = new Phone(); batch(queue, phone, observer);
        check(Arrays.equals(internalBefore, SyncFiles.read(internal, 262144))
                && Arrays.equals(externalBefore, SyncFiles.read(external, 262144)) && csv.equals(session.csv()),
                "recovery and confirmed synchronization leave internal and exported CSV bytes unchanged");
        queue.enqueue(WorkoutSession.read(csv));
        check(queue.entries().size() == 1 && queue.entries().get(0).state == SyncQueue.State.SYNCED,
                "archive reconciliation does not requeue an already confirmed payload");
        WorkoutSession revision = new WorkoutSession(session);
        revision.record(Exercise.SQUAT, 1, 8, WorkoutJson.completedAt(session), "CORRECTED");
        SyncQueue.Entry revised = queue.enqueue(revision);
        check(revised.state == SyncQueue.State.PENDING && revised.id.equals(entry.id) && !revised.hash.equals(entry.hash),
                "corrected completed results requeue under the same stable session ID with a new digest");
        boolean staleRejected = false;
        try { queue.mark(entry, SyncQueue.State.SYNCED); } catch (IOException expected) { staleRejected = true; }
        check(staleRejected, "old acknowledgement cannot mark a corrected payload synced");
        File interruptedCsv = root(base, "interrupted-csv");
        SessionStore interruptedStore = new SessionStore(interruptedCsv, null);
        interruptedStore.save(session);
        File archived = new File(interruptedCsv, "workouts/workout-" + session.id + ".csv");
        File archivedBackup = new File(archived.getPath() + ".bak");
        if (!archived.renameTo(archivedBackup)) throw new IOException("Cannot simulate CSV backup");
        output = new FileOutputStream(archived); output.write(1); output.close();
        SyncQueue recoveredQueue = new SyncQueue(interruptedCsv);
        check(SyncRecovery.recover(interruptedCsv, recoveredQueue, observer) == 0
                && recoveredQueue.entries().size() == 1 && archived.length() == 1 && archivedBackup.exists(),
                "sync reads committed CSV backups without modifying either archive file");
        File limited = root(base, "bounded"); SyncQueue limitedQueue = new SyncQueue(limited);
        for (int i = 0; i < 21; i++) limitedQueue.enqueue(complete(30000 + i * 10, 1));
        Phone noPhone = new Phone(); noPhone.disconnected = true;
        batch(limitedQueue, noPhone, observer);
        check(noPhone.order.size() == 20 && limitedQueue.entries().get(20).state == SyncQueue.State.PENDING,
                "a retry pass has a finite attempt budget and preserves the remaining queue");
        final Thread caller = Thread.currentThread(); final boolean[] offCaller = {false};
        final SyncQueue workerQueue = queue;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    new SyncBatch(workerQueue, new SyncBatch.Transport() {
                        public boolean sendAndAwait(SyncQueue.Entry e, int r, long timeout) {
                            offCaller[0] = Thread.currentThread() != caller; return false;
                        }
                    }, new Observer()).run();
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }
        }, "WorkoutIce3Sync-check");
        worker.start(); worker.join(5000);
        check(!worker.isAlive() && offCaller[0], "bounded worker pass performs transfer away from the caller thread");
        FileOutputStream fixture = new FileOutputStream(new File(base, "session-example.json"));
        fixture.write(json.getBytes("UTF-8")); fixture.close();
        System.out.println(passed + " sync checks passed");
        System.out.println("JSON fixture: " + new File(base, "session-example.json"));
    }
}
