package com.recom3.workout;

import java.io.*;
import java.util.*;
import static com.recom3.workout.Profile.Setting.*;

/** Synthetic model/storage/detector regressions, not physical Dips validation. */
public final class DynamicChecks {
    private static int passed;
    interface Operation { void run() throws Exception; }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static void rejects(Operation operation, String label) throws Exception {
        boolean rejected = false;
        try { operation.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, label);
    }
    static String bundle(int count) {
        String[] ids = {"squats", "push-ups", "pull-ups", "dips", "mystery-lift", "other-a", "other-b", "other-c"};
        StringBuilder definitions = new StringBuilder(), entries = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String id = i < ids.length ? ids[i] : "extra-" + i;
            if (i > 0) { definitions.append(','); entries.append(','); }
            definitions.append("{\"exerciseId\":").append(JsonText.quote(id)).append(",\"name\":")
                    .append(JsonText.quote(i == 4 ? "Mystery, \"Lift\"" : id))
                    .append(",\"measurement\":\"repetitions\",\"definitionVersion\":1,\"detection\":{\"mode\":\"local-profile-v1\"}}");
            entries.append("{\"exerciseId\":").append(JsonText.quote(id))
                    .append(",\"definitionVersion\":1,\"sets\":[{\"setId\":\"").append(id)
                    .append("-1\",\"repsTarget\":10},{\"setId\":\"").append(id).append("-2\",\"repsTarget\":8}]}");
        }
        return "{\"schemaVersion\":2,\"catalog\":{\"schemaVersion\":1,\"catalogId\":\"test-catalog\",\"revision\":1,\"definitions\":["
                + definitions + "]},\"routine\":{\"workoutId\":\"dynamic-test\",\"revision\":1,\"catalogId\":\"test-catalog\",\"catalogRevision\":1,\"exercises\":["
                + entries + "]}}";
    }
    static WorkoutSession create(WorkoutTemplate template, ProfileStore store) throws Exception {
        Exercise[] exercises = template.exercises(); Profile[] profiles = new Profile[exercises.length];
        for (int i = 0; i < profiles.length; i++) profiles[i] = store.load(exercises[i]);
        return WorkoutSession.create(profiles, template);
    }
    private static void motionParity() {
        Profile pull = new Profile(Exercise.PULL_UP);
        Profile clone = new Profile(new Exercise("uncompiled-exercise", "Unknown", "", 1), pull);
        MotionEngine original = new MotionEngine(pull), dynamic = new MotionEngine(clone);
        original.setRunning(true); dynamic.setRunning(true);
        long ms = 1000; boolean same = true;
        double[] signal = {0, 1.2, -1.2, 1.2, 0, 1.2, -1.2, 1.2, 0};
        int[] lengths = {1500, 300, 600, 300, 500, 300, 600, 300, 600};
        for (int phase = 0; phase < signal.length; phase++) for (int elapsed = 0; elapsed < lengths[phase]; elapsed += 25) {
            ms += 25; long ns = ms * 1000000L;
            original.gyro(ns, 0, 0, 0); dynamic.gyro(ns, 0, 0, 0);
            boolean a = original.accel(ns, 0, 0, (float)(8.86 + signal[phase]));
            boolean b = dynamic.accel(ns, 0, 0, (float)(8.86 + signal[phase]));
            same &= a == b && original.count() == dynamic.count() && original.state().equals(dynamic.state())
                    && Double.doubleToLongBits(original.signal()) == Double.doubleToLongBits(dynamic.signal());
        }
        check(same && original.count() == 2, "unknown identity replays the complete Pull-up engine identically for two synthetic cycles");
        check(clone.change(MODE, 1), "unknown exercise can select the installed settling detector independently");
        Profile manual = new Profile(clone.exercise, clone); manual.change(MODE, 0);
        MotionEngine engine = new MotionEngine(manual); engine.setRunning(true);
        for (int i = 1; i < 100; i++) engine.accel(i * 25000000L, 12, -13, 8);
        check(engine.count() == 0 && engine.state().equals("MANUAL") && !engine.waitingForStillness(), "manual capture ignores sensor motion and needs no sensor warmup");
        engine.correct(5); engine.setRunning(false); engine.setRunning(true);
        check(engine.count() == 5, "manual pause/resume retains the entered count");
        engine.correct(Integer.MAX_VALUE); check(engine.count() == Integer.MAX_VALUE, "manual correction cannot overflow the counter");
        engine.reset(); check(engine.count() == 0, "manual reset clears the counter");
    }
    public static void main(String[] args) throws Exception {
        final File files = new File(args[0], "dynamic-" + UUID.randomUUID()); files.mkdirs();
        final Map<String,Integer> legacy = new HashMap<String,Integer>();
        legacy.put("PULL_UP.TRIGGER", 95); legacy.put("PULL_UP.RELEASE", 25);
        legacy.put("SQUAT.TRIGGER", 70); legacy.put("PUSH_UP.AMPLITUDE", 225);
        ProfileStore store = new ProfileStore(files, new ProfileStore.Legacy() {
            public int get(String key, int fallback) { return legacy.containsKey(key) ? legacy.get(key) : fallback; }
        });
        Profile pull = store.load(Exercise.PULL_UP);
        check(pull.get(TRIGGER) == 95 && store.load(Exercise.SQUAT).get(TRIGGER) == 70
                && store.load(Exercise.PUSH_UP).get(AMPLITUDE) == 225, "first-use migration preserves all three legacy preference calibrations");
        pull.change(DIRECTION, -1); pull.change(FILTER_MS, 175); pull.change(PREPARATION_MS, 350);
        pull.change(VARIANCE, 45); pull.change(PREPARATION_TIMEOUT, 1500); pull.change(MODE, 1); pull.change(RESET_SENSORS, 0);
        store.save(pull);
        Exercise dips = new Exercise("dips", "Dips", "Parallel bars", 1);
        Profile cloned = store.load(dips); boolean equal = true;
        for (Profile.Setting setting : Profile.Setting.values()) equal &= cloned.get(setting) == pull.get(setting);
        check(equal && cloned.experimental && cloned != pull && cloned.exercise.id.equals("dips"), "new exercise clones every saved Pull-up parameter and is experimental");
        cloned.change(TRIGGER, 120); store.save(cloned);
        check(store.load(Exercise.PULL_UP).get(TRIGGER) == 95, "editing Dips never changes Pull-ups");
        pull.change(TRIGGER, 110); store.save(pull);
        Profile reloaded = new ProfileStore(files, null).load(dips);
        check(reloaded.get(TRIGGER) == 120 && reloaded.get(FILTER_MS) == 175 && reloaded.get(MODE) == 1,
                "restart retains existing Dips calibration instead of recloning Pull-ups");
        legacy.put("PULL_UP.TRIGGER", 200);
        check(store.load(Exercise.PULL_UP).get(TRIGGER) == 110, "migrated saved profile is never overwritten by legacy preferences");
        File profileFile = new File(files, "exercise-profiles/dips.profile"); byte[] stable = SyncFiles.read(profileFile, 1024);
        File backup = new File(profileFile.getPath() + ".bak"); check(profileFile.renameTo(backup), "simulate interrupted calibration transaction");
        new FileOutputStream(profileFile).close();
        check(store.load(dips).get(TRIGGER) == 120 && Arrays.equals(stable, SyncFiles.read(profileFile, 1024)), "calibration restart recovers the last committed backup");
        cloned.change(RELEASE, 30); File blocked = new File(files, "exercise-profiles/dips.profile.bak"); blocked.mkdir();
        final ProfileStore selectedStore = store; final Profile selected = cloned;
        rejects(new Operation() { public void run() throws Exception { selectedStore.save(selected); } }, "calibration commit failure is reported");
        check(Arrays.equals(stable, SyncFiles.read(profileFile, 1024)), "failed calibration save preserves committed bytes"); blocked.delete();
        final String valid = bundle(5);
        final WorkoutTemplate template = DynamicRoutine.parse(valid.getBytes("UTF-8"));
        check(template.dynamic() && template.exercises().length == 5 && template.exercises()[3].id.equals("dips"), "versioned definition/routine bundle creates five runtime exercises");
        check(template.exercises()[4].label.equals("Mystery, \"Lift\""), "uncompiled display name is data, including quotes and commas");
        check(template.targets(template.exercises()[3])[1] == 8, "per-set 10/8 targets are retained independently");
        rejects(new Operation() { public void run() throws Exception { WorkoutTemplate.parse(valid.getBytes("UTF-8")); } }, "legacy TEMPLATE parser continues rejecting the dynamic contract");
        String[] invalid = {
            valid.replace("\"local-profile-v1\"", "\"download-java\""), valid.replace("\"repetitions\"", "\"duration\""),
            valid.replace("\"definitionVersion\":1", "\"definitionVersion\":2").replace("\"name\":\"dips\"", "\"name\":\"dips\""),
            valid.replace("\"repsTarget\":10", "\"repsTarget\":101"), valid.replace("\"setId\":\"dips-1\"", "\"setId\":\"dips-3\""),
            valid.replace("\"catalogRevision\":1", "\"catalogRevision\":2"), bundle(9),
            valid.replace("\"exerciseId\":\"dips\"", "\"exerciseId\":\"../escape\""),
            valid.replace("\"routine\":{", "\"routine\":{\"unexpected\":1,"),
            valid.replace("\"catalogId\":\"test-catalog\",\"catalogRevision\":1", "\"catalogId\":\"missing\",\"catalogRevision\":1")
        };
        // A valid all-version-2 definition/routine is supported; only mismatched references are invalid.
        invalid[2] = valid.replace("\"exerciseId\":\"dips\",\"definitionVersion\":1,\"sets\"", "\"exerciseId\":\"dips\",\"definitionVersion\":2,\"sets\"");
        for (int i = 0; i < invalid.length; i++) { final String bad = invalid[i];
            rejects(new Operation() { public void run() throws Exception { DynamicRoutine.parse(bad.getBytes("UTF-8")); } }, "strict dynamic rejection case " + i);
        }
        TemplateStore templates = new TemplateStore(files); TemplateService service = new TemplateService(templates, store);
        WorkoutWire incoming = new WorkoutWire(321, "com.recom3.snow3", WorkoutWire.ROUTINE_V2, template.hash, template.bytes());
        WorkoutWire receipt = service.receive(incoming, 1000);
        check(receipt.action.equals(WorkoutWire.ROUTINE_V2_STORED) && receipt.info.equals(template.hash)
                && receipt.request == 321 && templates.load().dynamic(), "v2 receipt follows durable bundle/definition/profile storage with a distinct action");
        check(new File(files, "exercise-definitions/dips-1.definition").isFile()
                && new ProfileStore(files, null).load(template.exercises()[3]).get(TRIGGER) == 120, "receipt initializes missing profiles and preserves already calibrated IDs");
        check(RoutineConsent.needsReview(template, ""), "new dynamic bundle still requires explicit exact-digest review");
        final WorkoutSession session = create(template, store);
        check(session.exercises().length == 5 && session.exercise("dips").label.equals("dips") && !session.legacy(), "session owns its ordered runtime definitions");
        check(session.indexOf(session.exercises()[4]) == 4 && WorkoutNavigation.wrap(5, 5) == 0
                && WorkoutNavigation.wrap(-1, 5) == 4 && WorkoutNavigation.pages(5) == 2, "HUD navigation wraps five entries and allocates two summary pages");
        session.target(session.exercise("dips"), 2, 9); session.resize(session.exercise("dips"), 3);
        check(session.target(session.exercise("dips"), 1) == 10 && session.target(session.exercise("dips"), 2) == 9
                && session.target(session.exercise("dips"), 3) == 9, "set editing/resizing does not flatten other goals");
        check(WorkoutSession.read(session.csv()).csv().equals(session.csv()), "unstarted generic CSV round trip is byte-stable and self-contained");
        final String brokenCsv = session.csv().substring(0, session.csv().length() - 5);
        rejects(new Operation() { public void run() throws Exception { WorkoutSession.read(brokenCsv); } }, "truncated v2 CSV is rejected instead of inventing missing plans");
        long timestamp = 100000;
        for (Exercise exercise : session.exercises()) for (int number = 1; number <= session.plannedSets(exercise); number++)
            session.record(exercise, number, session.target(exercise, number), ++timestamp, "MANUAL");
        check(session.complete() && session.nextExercise(session.exercise("dips")) == null, "completion considers exactly the routine's N exercises");
        String csv = session.csv(); WorkoutSession restored = WorkoutSession.read(csv);
        check(restored.id.equals(session.id) && restored.csv().equals(csv) && restored.totalReps() == session.totalReps(), "completed generic recovery preserves UUID, goals, definitions, profiles and actual counts");
        rejects(new Operation() { public void run() throws Exception { WorkoutJson.encode(session); } }, "dynamic result cannot leak through SESSION v1 encoder");
        final SyncQueue queue = new SyncQueue(files);
        rejects(new Operation() { public void run() throws Exception { queue.enqueue(session); } }, "legacy queue refuses a generic result");
        String json = DynamicWorkoutJson.encode(session);
        check(json.contains("\"schemaVersion\":2") && json.contains("\"exerciseId\":\"dips\"") && json.contains("\"profileVersion\":1"), "separate local v2 JSON retains unknown ID and calibration provenance");
        check(json.equals(DynamicWorkoutJson.encode(restored)), "v2 result bytes are stable across CSV recovery");
        SyncFiles.write(new File(files, "dynamic-example.json"), json.getBytes("UTF-8"));
        SessionStore sessions = new SessionStore(files, new File(files, "exports")); sessions.save(session); sessions.export(session);
        check(sessions.load().id.equals(session.id) && sessions.load().csv().equals(csv), "active generic session survives a fresh store instance");
        byte[] before = SyncFiles.read(new File(files, "workouts/workout-" + session.id + ".csv"), 262144);
        check(SyncRecovery.recover(files, queue, new SyncBatch.Observer() { public void state(String id, SyncQueue.State state) {} }) == 0
                && queue.entries().isEmpty(), "recovery archives dynamic JSON without placing it on the v1 transport");
        check(Arrays.equals(before, SyncFiles.read(new File(files, "workouts/workout-" + session.id + ".csv"), 262144)), "recovery never rewrites the historical CSV");
        final DynamicResults results = new DynamicResults(files); results.enqueue(session);
        check(!results.confirmed(session) && !results.send(session, null), "stored v2 results remain unconfirmed with no receiver capability");
        final int[] calls = {0};
        DynamicResults.Receiver oldPeer = new DynamicResults.Receiver() {
            public boolean advertisesSessionV2() { return false; }
            public String[] sendAndAwait(String id, String hash, byte[] json) { calls[0]++; return new String[] {id, hash}; }
        };
        check(!results.send(session, oldPeer) && calls[0] == 0, "legacy receiver never receives a v2 result");
        DynamicResults.Receiver wrongPeer = new DynamicResults.Receiver() {
            public boolean advertisesSessionV2() { return true; }
            public String[] sendAndAwait(String id, String hash, byte[] json) { return new String[] {id, "bad"}; }
        };
        check(!results.send(session, wrongPeer) && !results.confirmed(session), "wrong digest receipt never confirms a v2 result");
        DynamicResults.Receiver capablePeer = new DynamicResults.Receiver() {
            public boolean advertisesSessionV2() { return true; }
            public String[] sendAndAwait(String id, String hash, byte[] json) { return new String[] {id, hash}; }
        };
        check(results.send(session, capablePeer) && results.confirmed(session), "only matching durable receipt from explicitly capable test peer confirms v2");
        session.record(session.exercise("dips"), 1, 11, session.result(session.exercise("dips"), 1).completedAt, "CORRECTED");
        results.enqueue(session); check(!results.confirmed(session), "a corrected v2 payload cannot inherit an earlier confirmation");
        final String prior = session.csv();
        WorkoutTemplate update = DynamicRoutine.parse(valid.replace("\"workoutId\":\"dynamic-test\",\"revision\":1", "\"workoutId\":\"dynamic-test\",\"revision\":2").getBytes("UTF-8"));
        service.receive(new WorkoutWire(322, "com.recom3.snow3", WorkoutWire.ROUTINE_V2, update.hash, update.bytes()), 2000);
        check(session.csv().equals(prior), "receiving a new routine leaves active definition/target/count snapshots unchanged");
        final String conflicting = valid.replace("\"name\":\"dips\"", "\"name\":\"Changed dips\"");
        final TemplateService currentService = service;
        rejects(new Operation() { public void run() throws Exception {
            WorkoutTemplate changed = DynamicRoutine.parse(conflicting.getBytes("UTF-8"));
            currentService.receive(new WorkoutWire(323, "com.recom3.snow3", WorkoutWire.ROUTINE_V2, changed.hash, changed.bytes()), 3000);
        } }, "changing a remembered definition without a version increment cannot be acknowledged");
        check(templates.load().hash.equals(update.hash), "definition conflict preserves the committed routine");
        final File unavailable = new File(files, "blocked-receiver"); unavailable.mkdirs();
        new File(unavailable, "exercise-profiles").createNewFile();
        final TemplateService failing = new TemplateService(new TemplateStore(unavailable), new ProfileStore(unavailable, null));
        rejects(new Operation() { public void run() throws Exception {
            failing.receive(new WorkoutWire(324, "com.recom3.snow3", WorkoutWire.ROUTINE_V2, template.hash, template.bytes()), 4000);
        } }, "missing calibration persistence prevents a dynamic success receipt");
        check(new TemplateStore(unavailable).load() == null, "failed profile initialization never publishes an executable routine");
        WorkoutTemplate single = DynamicRoutine.parse(bundle(1).replace("squats", "never-compiled").getBytes("UTF-8"));
        WorkoutSession singleSession = create(single, store);
        singleSession.record(single.exercises()[0], 1, 10, 1000, "MANUAL"); singleSession.record(single.exercises()[0], 2, 8, 2000, "MANUAL");
        check(singleSession.complete() && singleSession.exercises().length == 1, "one unknown exercise can finish without requiring the three legacy exercises");
        final DynamicResults noReceipt = new DynamicResults(new File(files, "no-receipt"));
        check(!noReceipt.send(singleSession, new DynamicResults.Receiver() {
            public boolean advertisesSessionV2() { return true; }
            public String[] sendAndAwait(String id, String hash, byte[] bytes) { return null; }
        }) && !noReceipt.confirmed(singleSession), "capability plus successful submission without a receipt stays pending");
        check(!noReceipt.send(singleSession, new DynamicResults.Receiver() {
            public boolean advertisesSessionV2() { return true; }
            public String[] sendAndAwait(String id, String hash, byte[] bytes) { return new String[] {"wrong-session", hash}; }
        }), "v2 receipt for a different session is rejected");
        motionParity();
        System.out.println(passed + " dynamic checks passed");
    }
}
