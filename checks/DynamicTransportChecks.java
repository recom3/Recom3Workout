package com.recom3.workout;

import android.content.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Production sender/outbox/worker, replacing only host Android and receiving-phone boundaries. */
public final class DynamicTransportChecks {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static final class Subscription {
        final BroadcastReceiver receiver; final IntentFilter filter;
        Subscription(BroadcastReceiver receiver, IntentFilter filter) { this.receiver = receiver; this.filter = filter; }
    }
    private static final class App extends Context {
        final File files, phone;
        final List<Subscription> subscriptions = new ArrayList<Subscription>();
        final List<WorkoutWire> sent = Collections.synchronizedList(new ArrayList<WorkoutWire>());
        volatile String capFault = "", ackFault = "", senderThread;
        volatile boolean failSend, hold, capOnly;
        volatile Runnable duringProbe, duringResult;
        volatile CountDownLatch resultSent = new CountDownLatch(1);
        App(File base) { files = new File(base, UUID.randomUUID().toString()); phone = new File(files, "phone"); files.mkdirs(); }
        public File getFilesDir() { return files; }
        public synchronized Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            subscriptions.add(new Subscription(receiver, filter)); return null;
        }
        public synchronized void unregisterReceiver(BroadcastReceiver receiver) {
            for (Iterator<Subscription> it = subscriptions.iterator(); it.hasNext();) if (it.next().receiver == receiver) it.remove();
        }
        synchronized int temporary() {
            int count = 0; for (Subscription s : subscriptions) if (s.filter.hasAction(WorkoutWire.STORED)) count++; return count;
        }
        void emit(Intent intent) {
            List<Subscription> copy;
            synchronized (this) { copy = new ArrayList<Subscription>(subscriptions); }
            for (Subscription s : copy) if (s.filter.hasAction(intent.getAction())) s.receiver.onReceive(this, intent);
        }
        void reply(WorkoutWire frame, String broadcastAction) throws IOException {
            emit(new Intent(broadcastAction).putExtra("message", frame.encode()));
        }
        void state(int state) { emit(new Intent(ReconWorkoutTransport.LINK_STATE).putExtra("state", state)); }
        public void sendBroadcast(Intent intent) {
            if (failSend) throw new SecurityException("Host transport failure");
            try {
                if (!"com.reconinstruments.mobilesdk.hudconnectivity.channel.object".equals(intent.getAction())) throw new AssertionError("stock OBJECT action");
                WorkoutWire wire = WorkoutWire.decode(intent.getByteArrayExtra("HUDConnectivityMessage"));
                senderThread = Thread.currentThread().getName(); sent.add(wire);
                if (wire.action.equals(WorkoutWire.CAPABILITIES)) {
                    if (temporary() != 1) throw new AssertionError("receiver registered before probe");
                    if (wire.request <= 0 || !wire.sender.equals("com.recom3.workout") || !wire.info.isEmpty() || wire.data.length != 0)
                        throw new AssertionError("exact capability envelope");
                    if (duringProbe != null) duringProbe.run();
                    if (capFault.equals("lost")) return;
                    String data = WorkoutWire.CAPABILITIES_JSON;
                    if (capFault.equals("type")) data = data.replace("20000", "\"20000\"");
                    if (capFault.equals("schema")) data = data.replace("[1,2]", "[1]");
                    if (capFault.equals("budget")) data = data.replace("25600", "25601");
                    if (capFault.equals("duplicate")) data = data.replace("{", "{\"schemaVersion\":1,");
                    if (capFault.equals("extra")) data = data.replace("}", ",\"accountId\":1}");
                    if (capFault.equals("trailing")) data += " ";
                    byte[] bytes = data.getBytes("UTF-8"); if (capFault.equals("utf8")) bytes[0] = (byte)0xff;
                    reply(new WorkoutWire(capFault.equals("request") ? wire.request + 1 : capFault.equals("zero") ? 0 : wire.request,
                            capFault.equals("sender") ? "legacy" : "com.recom3.snow3",
                            capFault.equals("action") ? WorkoutWire.ROUTINE_V2_STORED : WorkoutWire.CAPABILITIES_RESULT,
                            capFault.equals("info") ? "unexpected" : "", bytes), WorkoutWire.CAPABILITIES_RESULT);
                } else if (wire.action.equals(WorkoutWire.SESSION)) {
                    if (temporary() != 1) throw new AssertionError("receiver registered before result");
                    if (!wire.sender.equals("com.recom3.workout") || !wire.info.equals(WorkoutWire.sha256(wire.data)) || wire.data.length > 20000)
                        throw new AssertionError("exact original-byte result envelope");
                    if (capOnly) throw new AssertionError("incompatible receiver must not receive a result");
                    resultSent.countDown();
                    if (duringResult != null) duringResult.run();
                    if (hold) return;
                    // Host phone storage boundary: acknowledge only after writing and reading exact bytes.
                    String json = TemplateJson.utf8(wire.data);
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"sessionId\":\"([^\"]+)\"").matcher(json);
                    if (!matcher.find()) throw new AssertionError("encoder session ID");
                    String id = matcher.group(1);
                    File stored = new File(phone, id + ".json"); SyncFiles.write(stored, wire.data);
                    if (!Arrays.equals(SyncFiles.read(stored, 20000), wire.data)) throw new AssertionError("phone readback");
                    if (ackFault.equals("lost")) return;
                    String receivedId = ackFault.equals("uuid") ? UUID.randomUUID().toString() : id;
                    if (ackFault.equals("account")) receivedId += ":account:2"; // Account metadata is not in the Jet receipt contract.
                    if (ackFault.equals("json")) receivedId = "{\"sessionId\":\"" + id + "\"}";
                    byte[] data = receivedId.getBytes("UTF-8"); if (ackFault.equals("utf8")) data[0] = (byte)0xff;
                    reply(new WorkoutWire(ackFault.equals("request") ? wire.request + 1 : ackFault.equals("zero") ? 0 : wire.request,
                            ackFault.equals("sender") ? "com.recom3.workout" : "com.recom3.snow3",
                            ackFault.equals("action") ? WorkoutWire.TEMPLATE_STORED : WorkoutWire.STORED,
                            ackFault.equals("digest") ? wire.info.toUpperCase(java.util.Locale.US) : wire.info, data), WorkoutWire.STORED);
                }
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        int count(String action) { int count = 0; synchronized (sent) { for (WorkoutWire frame : sent) if (frame.action.equals(action)) count++; } return count; }
    }
    static WorkoutSession fixture(File files) throws Exception {
        WorkoutTemplate routine = DynamicRoutine.parse(DynamicChecks.bundle(4).replace("\"repsTarget\":10", "\"repsTarget\":100").getBytes("UTF-8"));
        WorkoutSession session = DynamicChecks.create(routine, new ProfileStore(files, null));
        for (Exercise exercise : session.exercises()) {
            session.record(exercise, 1, exercise.id.equals("dips") ? 10 : 0, 1700000000000L, "MANUAL");
            session.record(exercise, 2, 8, 1700000001000L, "TARGET");
        }
        return session;
    }
    private static DynamicDelivery.State send(App app, WorkoutSession session, ReconWorkoutTransport transport, long timeout) throws Exception {
        DynamicResults queue = new DynamicResults(app.files); queue.enqueue(session);
        return DynamicDelivery.send(queue, queue.entries().get(0), transport, new DynamicDelivery.Attempt(), timeout);
    }
    private static WorkoutSync worker(App app) throws Exception {
        java.lang.reflect.Constructor<WorkoutSync> constructor = WorkoutSync.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true); return constructor.newInstance(app);
    }
    private static void idle(WorkoutSync sync) throws Exception {
        java.lang.reflect.Field f = WorkoutSync.class.getDeclaredField("worker"); f.setAccessible(true);
        ((ExecutorService)f.get(sync)).submit(new Runnable() { public void run() {} }).get(15, TimeUnit.SECONDS);
    }
    private static void stop(WorkoutSync sync) throws Exception {
        java.lang.reflect.Field f = WorkoutSync.class.getDeclaredField("worker"); f.setAccessible(true); ((ExecutorService)f.get(sync)).shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "dynamic-transport-" + UUID.randomUUID()); root.mkdirs();
        App app = new App(root); WorkoutSession session = fixture(app.files);
        check(!new DynamicResults(app.files).confirmed(session), "unqueued completed result has no durable receipt");
        byte[] original = DynamicWorkoutJson.encode(session).getBytes("UTF-8");
        String json = new String(original, "UTF-8");
        check(json.contains("\"exerciseId\":\"dips\"") && json.contains("\"repsCompleted\":0")
                && json.contains("\"repsTarget\":100") && json.contains("\"repsTarget\":8"), "real encoder carries four exercises, Dips, distinct targets, 100 and zero counts");
        SessionStore archive = new SessionStore(app.files, new File(app.files, "exports")); archive.save(session); File exported = archive.export(session);
        byte[] csv = SyncFiles.read(exported, 262144);
        ReconWorkoutTransport transport = new ReconWorkoutTransport(app);
        check(send(app, session, transport, 1000) == DynamicDelivery.State.STORED, "production transport negotiates then commits the exact durable receipt");
        check(new DynamicResults(app.files).confirmed(session) && app.temporary() == 0, "receipt survives a new outbox instance and temporal receiver is removed");
        check(Arrays.equals(original, app.sent.get(1).data) && Arrays.equals(original, SyncFiles.read(new File(app.phone, session.id + ".json"), 20000)), "SESSION and phone storage retain the real encoder bytes exactly");
        check(app.sent.get(0).request > 0 && app.sent.get(1).request > 0 && app.sent.get(0).request != app.sent.get(1).request, "capability and result use distinct fresh positive request keys");
        check(Arrays.equals(csv, SyncFiles.read(exported, 262144)) && new File(app.files, "workout-results-v2/" + session.id + ".json").exists(), "successful delivery retains result JSON and unchanged CSV");
        int sent = app.sent.size(); send(app, session, transport, 1000);
        check(app.sent.size() == sent, "confirmed bytes are not resubmitted on retry");
        for (String fault : new String[] {"lost", "request", "zero", "sender", "action", "info", "type", "schema", "budget", "duplicate", "extra", "trailing", "utf8"}) {
            App bad = new App(root); WorkoutSession s = fixture(bad.files); bad.capFault = fault; bad.capOnly = true;
            long start = System.nanoTime();
            DynamicDelivery.State state = send(bad, s, new ReconWorkoutTransport(bad), 35);
            check(state == DynamicDelivery.State.INCOMPATIBLE && bad.count(WorkoutWire.SESSION) == 0
                    && !new DynamicResults(bad.files).confirmed(s) && bad.temporary() == 0
                    && System.nanoTime() - start < 1000000000L, "invalid capability " + fault + " fails closed within deadline without v1 fallback");
        }
        for (String fault : new String[] {"lost", "request", "zero", "sender", "action", "digest", "uuid", "account", "json", "utf8"}) {
            App bad = new App(root); WorkoutSession s = fixture(bad.files); bad.ackFault = fault;
            check(send(bad, s, new ReconWorkoutTransport(bad), 35) == DynamicDelivery.State.TIMEOUT
                    && !new DynamicResults(bad.files).confirmed(s) && bad.temporary() == 0, "invalid/lost receipt " + fault + " leaves original result retryable");
        }
        App loss = new App(root); WorkoutSession lost = fixture(loss.files); ReconWorkoutTransport lossTransport = new ReconWorkoutTransport(loss);
        loss.ackFault = "lost"; send(loss, lost, lossTransport, 35); byte[] first = loss.sent.get(1).data;
        WorkoutWire late = loss.sent.get(1); loss.ackFault = "";
        loss.reply(new WorkoutWire(late.request, "com.recom3.snow3", WorkoutWire.STORED, late.info, lost.id.getBytes("UTF-8")), WorkoutWire.STORED);
        check(!new DynamicResults(loss.files).confirmed(lost), "receipt arriving after timeout cannot commit outside the closed attempt");
        check(send(loss, lost, lossTransport, 1000) == DynamicDelivery.State.STORED && Arrays.equals(first, loss.sent.get(3).data)
                && loss.sent.get(1).request != loss.sent.get(3).request, "lost-ACK retry negotiates anew and preserves exact original bytes and UUID");
        App stale = new App(root); final WorkoutSession corrected = fixture(stale.files); final DynamicResults outbox = new DynamicResults(stale.files);
        outbox.enqueue(corrected); final DynamicResults.Entry old = outbox.entries().get(0); final DynamicDelivery.Attempt attempt = new DynamicDelivery.Attempt();
        stale.duringResult = new Runnable() { public void run() {
            attempt.cancel(); corrected.record(corrected.exercise("dips"), 1, 11, 1700000000000L, "CORRECTED");
            try { outbox.enqueue(corrected); } catch (IOException e) { throw new IllegalStateException(e); }
        }};
        check(DynamicDelivery.send(outbox, old, new ReconWorkoutTransport(stale), attempt, 200) == DynamicDelivery.State.PENDING
                && !outbox.confirmed(corrected) && !outbox.confirmed(old), "correction during result send cannot inherit an old digest receipt");
        stale.duringResult = null;
        check(send(stale, corrected, new ReconWorkoutTransport(stale), 1000) == DynamicDelivery.State.STORED
                && !old.hash.equals(outbox.entries().get(0).hash), "corrected same UUID delivers its newly saved original bytes with a new digest");
        final App before = new App(root); WorkoutSession beforeSession = fixture(before.files); final DynamicDelivery.Attempt beforeAttempt = new DynamicDelivery.Attempt();
        DynamicResults beforeQueue = new DynamicResults(before.files); beforeQueue.enqueue(beforeSession);
        before.duringProbe = new Runnable() { public void run() { beforeAttempt.cancel(); } };
        check(DynamicDelivery.send(beforeQueue, beforeQueue.entries().get(0), new ReconWorkoutTransport(before), beforeAttempt, 200) == DynamicDelivery.State.PENDING
                && before.count(WorkoutWire.SESSION) == 0, "invalidated snapshot cannot submit old bytes after capability negotiation");
        final App reconnect = new App(root); WorkoutSession reconnectSession = fixture(reconnect.files); ReconWorkoutTransport reconnectTransport = new ReconWorkoutTransport(reconnect);
        reconnect.duringResult = new Runnable() { public void run() { reconnect.state(0); reconnect.state(2); } };
        check(send(reconnect, reconnectSession, reconnectTransport, 100) == DynamicDelivery.State.ERROR && !new DynamicResults(reconnect.files).confirmed(reconnectSession), "disconnect/reconnect during send rejects an otherwise exact old-connection ACK");
        reconnect.duringResult = null;
        check(send(reconnect, reconnectSession, reconnectTransport, 1000) == DynamicDelivery.State.STORED && reconnect.count(WorkoutWire.CAPABILITIES) == 2, "reconnected receiver is probed again before any result retry");
        App failure = new App(root); WorkoutSession failedSession = fixture(failure.files); failure.failSend = true;
        boolean failed = false; try { send(failure, failedSession, new ReconWorkoutTransport(failure), 200); } catch (IOException expected) { failed = true; }
        check(failed && failure.temporary() == 0 && !new DynamicResults(failure.files).confirmed(failedSession), "transport error removes temporary receiver and retains unconfirmed data");
        App large = new App(root);
        Profile[] largeProfiles = new Profile[4];
        for (int i = 0; i < 4; i++) {
            largeProfiles[i] = new Profile(new Exercise(String.format(java.util.Locale.US, "oversize-%023d", i), "Oversized test exercise", "", 1));
            for (Profile.Setting setting : Profile.Setting.values()) largeProfiles[i].change(setting, setting.max);
            largeProfiles[i].change(Profile.Setting.SETS, 15);
        }
        WorkoutSession largeSession = new WorkoutSession(largeProfiles);
        for (Exercise exercise : largeSession.exercises()) {
            for (int number = 1; number <= 15; number++) largeSession.record(exercise, number, 0, 1700000001000L + number, "MANUAL");
        }
        check(DynamicWorkoutJson.encode(largeSession).getBytes("UTF-8").length > 20000
                && send(large, largeSession, new ReconWorkoutTransport(large), 200) == DynamicDelivery.State.OVERSIZED && large.sent.isEmpty(), "oversized real encoder result stays in the same local queue without truncation or fragmentation (" + DynamicWorkoutJson.encode(largeSession).getBytes("UTF-8").length + " bytes)");
        check(new WorkoutWire(1, "com.recom3.workout", WorkoutWire.SESSION, old.hash, new byte[20000]).encode().length <= 25600, "20,000-byte payload fits complete OBJECT frame budget");
        boolean frameRejected = false;
        try { new WorkoutWire(1, "s", "a", "i", new byte[25600]).encode(); } catch (IOException expected) { frameRejected = true; }
        check(frameRejected, "complete frame over 25,600 bytes is rejected before broadcast");
        App boundary = new App(root); WorkoutSession boundarySession = fixture(boundary.files);
        byte[] padded = Arrays.copyOf(DynamicWorkoutJson.encode(boundarySession).getBytes("UTF-8"), 20000);
        Arrays.fill(padded, DynamicWorkoutJson.encode(boundarySession).getBytes("UTF-8").length, padded.length, (byte)' ');
        ReconWorkoutTransport boundaryTransport = new ReconWorkoutTransport(boundary);
        check(boundaryTransport.deliver(new DynamicResults.Entry(boundarySession.id, padded), new DynamicDelivery.Attempt(), 1000) == DynamicDelivery.State.STORED,
                "exact 20,000-byte transport boundary is accepted without changing submitted bytes");
        int boundaryCount = boundary.sent.size();
        check(boundaryTransport.deliver(new DynamicResults.Entry(boundarySession.id, new byte[20001]), new DynamicDelivery.Attempt(), 1000) == DynamicDelivery.State.OVERSIZED
                && boundary.sent.size() == boundaryCount, "20,001-byte transport boundary is refused before probing or sending");
        check(new WorkoutWire(1, "s", "a", "i", new byte[25577]).encode().length == 25600,
                "complete OBJECT accepts its exact 25,600-byte boundary");
        App legacy = new App(root); Profile[] legacyProfiles = {new Profile(Exercise.SQUAT), new Profile(Exercise.PUSH_UP), new Profile(Exercise.PULL_UP)};
        for (Profile profile : legacyProfiles) profile.change(Profile.Setting.SETS, 1);
        WorkoutSession legacySession = new WorkoutSession(legacyProfiles);
        for (Exercise exercise : legacySession.exercises()) legacySession.record(exercise, 1, 5, 1700000000000L, "MANUAL");
        SyncQueue.Entry v1 = new SyncQueue(legacy.files).enqueue(legacySession);
        check(new ReconWorkoutTransport(legacy).sendAndAwait(v1, 77, 1000) && legacy.sent.size() == 1
                && Arrays.equals(legacy.sent.get(0).data, WorkoutJson.encode(legacySession).getBytes("UTF-8")),
                "production SESSION v1 transport and exact bytes remain independent of v2 negotiation");
        App broken = new App(root); WorkoutSession brokenSession = fixture(broken.files);
        DynamicResults brokenQueue = new DynamicResults(broken.files); brokenQueue.enqueue(brokenSession);
        File brokenReceipt = new File(broken.files, "workout-results-v2/" + brokenSession.id + ".receipt"); brokenReceipt.mkdir();
        boolean receiptFailed = false;
        try { send(broken, brokenSession, new ReconWorkoutTransport(broken), 1000); } catch (IOException expected) { receiptFailed = true; }
        check(receiptFailed && new File(broken.files, "workout-results-v2/" + brokenSession.id + ".json").isFile(),
                "local receipt write/read failure retains the original queued JSON");
        // Real process worker: a held receipt lets a new edit invalidate the old snapshot without HUD waiting.
        final App process = new App(root); final WorkoutSession work = fixture(process.files);
        SessionStore processStore = new SessionStore(process.files, new File(process.files, "exports")); processStore.save(work);
        final WorkoutSync sync = worker(process); process.hold = true; sync.completed(work);
        check(process.resultSent.await(2, TimeUnit.SECONDS), "real process worker starts result send away from caller");
        sync.invalidate(work.id); work.record(work.exercise("dips"), 1, 12, 1700000000000L, "CORRECTED"); processStore.save(work);
        process.hold = false; sync.completed(work); idle(sync);
        check(new DynamicResults(process.files).confirmed(work) && sync.dynamicState(work.id) == DynamicDelivery.State.STORED
                && process.senderThread.equals("WorkoutIce3Sync"), "real worker cancels in-flight old revision and sends corrected bytes off HUD thread");
        check(process.count(WorkoutWire.SESSION) == 2, "single worker sends each revision once with no retry of an invalidated snapshot");
        stop(sync);
        // Reopen exact queue/receipt and suppress a completed revision whose CSV was explicitly reopened.
        work.resetFrom(work.exercise("dips"), 2); processStore.save(work);
        int prior = process.sent.size(); WorkoutSync reopened = worker(process); reopened.startup(); idle(reopened);
        check(process.sent.size() == prior && reopened.dynamicState(work.id) == DynamicDelivery.State.LOCAL, "startup never resends a formerly completed result after its CSV was reopened"); stop(reopened);
        App restart = new App(root); WorkoutSession recovered = fixture(restart.files); new SessionStore(restart.files, null).save(recovered);
        WorkoutSync restarted = worker(restart); restarted.startup(); idle(restarted);
        check(new DynamicResults(restart.files).confirmed(recovered), "startup recovers final CSV-to-outbox gap then delivers through production transport"); stop(restarted);
        App retrySave = new App(root); WorkoutSession savedOnly = fixture(retrySave.files);
        new SessionStore(retrySave.files, null).save(savedOnly);
        File blockedOutbox = new File(retrySave.files, "workout-results-v2"); blockedOutbox.createNewFile();
        WorkoutSync saveWorker = worker(retrySave); saveWorker.completed(savedOnly); idle(saveWorker);
        check(saveWorker.dynamicState(savedOnly.id) == DynamicDelivery.State.ERROR && retrySave.sent.isEmpty(),
                "queue write failure reports error while the completed CSV remains available");
        if (!blockedOutbox.delete()) throw new IOException("Cannot unblock synthetic queue fixture");
        saveWorker.retrySaved(); idle(saveWorker);
        check(new DynamicResults(retrySave.files).confirmed(savedOnly), "explicit retry recovers a failed outbox save without another workout"); stop(saveWorker);
        // Export actual encoder fixture for read-only ICE3 contract verification.
        SyncFiles.write(new File(args[0], "workout-v2-real-encoder.json"), original);
        SyncFiles.write(new File(args[0], "workout-v2-capabilities.object"), app.sent.get(0).encode());
        SyncFiles.write(new File(args[0], "workout-v2-session.object"), app.sent.get(1).encode());
        System.out.println(passed + " dynamic transport checks passed (host boundaries; no device delivery)");
        System.exit(0);
    }
}
