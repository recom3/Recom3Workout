package com.recom3.workout;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class TemplateChecks {
    static final String VECTOR = "{\"schemaVersion\":1,\"workoutId\":\"web-routine-1\",\"revision\":7,\"exercises\":[{\"exerciseId\":\"squats\",\"name\":\"Squats\",\"sets\":[{\"setId\":\"squats-1\",\"repsTarget\":10},{\"setId\":\"squats-2\",\"repsTarget\":12}]},{\"exerciseId\":\"push-ups\",\"name\":\"Push-ups\",\"sets\":[{\"setId\":\"push-ups-1\",\"repsTarget\":5}]},{\"exerciseId\":\"pull-ups\",\"name\":\"Pull-ups\",\"sets\":[{\"setId\":\"pull-ups-1\",\"repsTarget\":4}]}]}";
    static final String HASH = "f7d144e1a68f253981ab4459059014ef3bed31f04bbd8457d1e903b74ef3e4ed";
    private static int passed;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static void rejected(Action action, String label) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException expected) { failed = true; }
        check(failed, label);
    }
    static WorkoutWire message(String text, int request) throws IOException {
        byte[] bytes = text.getBytes("UTF-8");
        return new WorkoutWire(request, "com.recom3.snow3", WorkoutWire.TEMPLATE, WorkoutWire.sha256(bytes), bytes);
    }
    private static File root(File base, String label) throws IOException {
        File folder = new File(base, label + "-" + UUID.randomUUID());
        if (!folder.mkdirs()) throw new IOException("Test directory unavailable");
        return folder;
    }
    private static Profile[] profiles() {
        Profile[] profiles = new Profile[Exercise.values().length];
        for (Exercise exercise : Exercise.values()) profiles[exercise.ordinal()] = new Profile(exercise);
        return profiles;
    }
    private static void invalidJson(final String value, String label) throws Exception {
        rejected(new Action() { public void run() throws Exception { WorkoutTemplate.parse(value.getBytes("UTF-8")); } }, label);
    }
    private static String manySets(int count) {
        String[] ids = {"squats", "push-ups", "pull-ups"}, names = {"Squats", "Push-ups", "Pull-ups"};
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"workoutId\":\"maximum\",\"revision\":1,\"exercises\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) json.append(',');
            json.append("{\"exerciseId\":\"").append(ids[i]).append("\",\"name\":\"").append(names[i]).append("\",\"sets\":[");
            for (int j = 1; j <= count; j++) {
                if (j > 1) json.append(',');
                json.append("{\"setId\":\"").append(ids[i]).append('-').append(j).append("\",\"repsTarget\":100}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }
    public static void main(String[] args) throws Exception {
        final byte[] bytes = VECTOR.getBytes("UTF-8");
        check(Arrays.equals(bytes, SyncFiles.read(new File(args[1], "workout-template-v1.json"), 16402)),
                "checked-in Snow3 vector fixture is byte-exact without a final newline");
        check(bytes.length == 379 && WorkoutWire.sha256(bytes).equals(HASH), "Snow3 exact 379-byte JSON and SHA-256 vector");
        final WorkoutWire frame = message(VECTOR, 123);
        byte[] binary = frame.encode();
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 32; i++) prefix.append(String.format(Locale.US, "%02x", binary[i] & 255));
        check(binary.length == 506 && prefix.toString().equals("000001fa0000007b00000010636f6d2e7265636f6d332e736e6f77330000001b"),
                "Snow3 request 123 exact 506-byte big-endian envelope and first 32 bytes");
        WorkoutWire decoded = WorkoutWire.decode(binary);
        check(decoded.request == 123 && decoded.sender.equals(frame.sender) && Arrays.equals(decoded.data, bytes),
                "full OBJECT frame decodes without changing payload bytes");
        WorkoutWire.Decoder fragments = new WorkoutWire.Decoder();
        check(fragments.append(Arrays.copyOfRange(binary, 0, 2), 100).isEmpty()
                && fragments.append(Arrays.copyOfRange(binary, 2, 171), 101).isEmpty()
                && fragments.append(Arrays.copyOfRange(binary, 171, binary.length), 102).get(0).info.equals(HASH),
                "fragmented OBJECT delivery waits for the complete validated frame");
        byte[] pair = new byte[binary.length * 2]; System.arraycopy(binary, 0, pair, 0, binary.length);
        System.arraycopy(binary, 0, pair, binary.length, binary.length);
        check(new WorkoutWire.Decoder().append(pair, 1).size() == 2, "coalesced OBJECT frames remain separate messages");
        WorkoutWire.Decoder expired = new WorkoutWire.Decoder(); expired.append(Arrays.copyOf(binary, 8), 1);
        check(expired.append(binary, 6002).size() == 1, "expired partial delivery cannot contaminate a fresh attempt");
        final byte[] malformed = binary.clone(); malformed[12] = (byte) 0xff;
        rejected(new Action() { public void run() throws Exception { WorkoutWire.decode(malformed); } }, "invalid UTF-8 envelope sender is rejected");
        for (final String bad : new String[]{"0", "-1", "1.0", "1e1", "\"7\"", "true", "null", "9007199254740992", "99999999999999999999999"})
            invalidJson(VECTOR.replace("\"revision\":7", "\"revision\":" + bad), "reject revision type/range " + bad);
        invalidJson(VECTOR.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "unsupported schema rejected");
        invalidJson(VECTOR.replace("\"revision\":7", "\"revision\":7,\"revision\":7"), "duplicate root member rejected");
        invalidJson(VECTOR.replace("\"revision\":7", "\"revision\":7,\"revi\\u0073ion\":7"), "escaped duplicate member rejected");
        invalidJson(VECTOR.replace("\"repsTarget\":10", "\"repsTarget\":10,\"repsTarget\":10"), "duplicate set member rejected");
        invalidJson(VECTOR.replace("\"name\":\"Squats\"", "\"name\":\"Squats\",\"extra\":1"), "unexpected nested member rejected");
        invalidJson(VECTOR.substring(0, VECTOR.length() - 1) + ",\"extra\":1}", "unexpected root member rejected");
        invalidJson(VECTOR + "{}", "trailing JSON document rejected");
        invalidJson(VECTOR.replace("squats-2", "squats-3"), "noncontiguous set ID rejected");
        invalidJson(VECTOR.replace("\"exerciseId\":\"squats\"", "\"exerciseId\":\"push-ups\""), "incorrect exercise order/identity rejected");
        invalidJson(VECTOR.replace("\"name\":\"Squats\"", "\"name\":\"Squat\""), "incorrect exercise name rejected");
        invalidJson(VECTOR.replace("web-routine-1", "owner/routine"), "non-ASCII-alphanumeric-hyphen workout ID rejected");
        invalidJson(VECTOR.replace("web-routine-1", ""), "empty workout ID rejected");
        invalidJson(VECTOR.replace("\"repsTarget\":10", "\"repsTarget\":101"), "target above 100 rejected");
        invalidJson(VECTOR.replace("\"repsTarget\":10", "\"repsTarget\":0"), "zero target rejected");
        invalidJson(VECTOR.replace("\"repsTarget\":10", "\"repsTarget\":\"10\""), "string target rejected");
        final byte[] badUtf = bytes.clone(); badUtf[badUtf.length - 2] = (byte) 0xff;
        rejected(new Action() { public void run() throws Exception { WorkoutTemplate.parse(badUtf); } }, "invalid UTF-8 JSON rejected");
        final byte[] oversized = new byte[WorkoutTemplate.MAX_WIRE + 1];
        rejected(new Action() { public void run() throws Exception { WorkoutTemplate.parse(oversized); } }, "oversized payload rejected before parse");
        boolean allTruncated = true;
        for (int length = 0; length < bytes.length; length++) {
            try { WorkoutTemplate.parse(Arrays.copyOf(bytes, length)); allTruncated = false; }
            catch (IOException expected) {}
        }
        check(allTruncated, "all truncated cross-vector prefixes rejected");
        WorkoutTemplate maximumRevision = WorkoutTemplate.parse(VECTOR.replace("\"revision\":7", "\"revision\":9007199254740991").getBytes("UTF-8"));
        check(maximumRevision.revision == 9007199254740991L, "maximum revision remains an exact long");
        check(WorkoutTemplate.parse(manySets(20).getBytes("UTF-8")).targets(Exercise.SQUAT).length == 20,
                "twenty sets of one hundred reps per exercise are supported");
        invalidJson(manySets(21), "twenty-one sets rejected");
        invalidJson(manySets(0), "empty set arrays rejected");
        invalidJson("\ufeff" + VECTOR, "UTF-8 BOM is not treated as JSON whitespace");
        invalidJson(VECTOR.replace("web-routine-1", "\\uD800"), "unpaired escaped surrogate rejected");
        String hundredId = new String(new char[100]).replace('\0', 'a');
        check(WorkoutTemplate.parse(VECTOR.replace("web-routine-1", hundredId).getBytes("UTF-8")).id.length() == 100,
                "one hundred ASCII ID characters supported");
        invalidJson(VECTOR.replace("web-routine-1", hundredId + "a"), "one hundred and one ID characters rejected");

        File base = root(new File(args[0]), "templates");
        final File files = root(base, "store");
        final TemplateStore store = new TemplateStore(files);
        TemplateService service = new TemplateService(store);
        WorkoutWire ack = service.receive(frame, 1000);
        Map<String, Object> receipt = TemplateJson.parse(ack.data, 512);
        check(ack.request == 123 && ack.sender.equals("com.recom3.workout") && ack.action.equals(WorkoutWire.TEMPLATE_STORED)
                && ack.info.equals(HASH) && receipt.size() == 4 && receipt.get("revision").equals(7L)
                && receipt.get("status").equals("stored") && receipt.get("workoutId").equals("web-routine-1"),
                "durable readback yields the exact Snow3 TEMPLATE_STORED receipt");
        check(new TemplateStore(files).load().hash.equals(HASH), "template survives a new store instance/restart");
        final WorkoutTemplate saved = store.load();
        check(RoutineConsent.needsReview(saved, ""), "a durable receipt does not imply user approval to train");
        rejected(new Action() { public void run() throws Exception { RoutineConsent.approved(saved, ""); } },
                "new workout cannot use unreviewed routine targets");
        check(RoutineConsent.approved(saved, saved.hash) == saved, "explicit consent applies the exact reviewed snapshot");
        check(RoutineConsent.approved(null, "") == null, "manual fallback does not require routine consent");
        WorkoutTemplate unseenUpdate = WorkoutTemplate.parse(VECTOR.replace("\"revision\":7", "\"revision\":8").getBytes("UTF-8"));
        check(RoutineConsent.needsReview(unseenUpdate, saved.hash), "an update needs new consent even with the same workout ID");
        String padded = new String(new char[WorkoutTemplate.MAX_WIRE - bytes.length]).replace('\0', ' ') + VECTOR;
        WorkoutWire paddedFrame = message(padded, 100);
        TemplateService paddedService = new TemplateService(new TemplateStore(root(base, "original-bytes")));
        check(paddedService.receive(paddedFrame, 1).info.equals(paddedFrame.info) && !paddedFrame.info.equals(HASH),
                "maximum-sized valid payload receipt hashes original bytes rather than reserialized JSON");
        final File record = new File(files, "workout-template/active.template"); record.setLastModified(100000);
        ack = service.receive(message(VECTOR, 124), 1001);
        check(ack.request == 124 && record.lastModified() == 100000, "identical retransmission ACKs the new key without rewriting");
        final TemplateService handler = service;
        rejected(new Action() { public void run() throws Exception {
            handler.receive(new WorkoutWire(125, frame.sender, frame.action, "0" + HASH.substring(1), bytes), 1002);
        } }, "bad hash has no receipt");
        rejected(new Action() { public void run() throws Exception {
            handler.receive(new WorkoutWire(125, "unrelated", frame.action, HASH, bytes), 1002);
        } }, "unexpected sender has no receipt");
        rejected(new Action() { public void run() throws Exception {
            handler.receive(new WorkoutWire(125, frame.sender, WorkoutWire.SESSION, HASH, bytes), 1002);
        } }, "another action cannot store a routine");
        rejected(new Action() { public void run() throws Exception { handler.receive(message(VECTOR, 0), 1002); } }, "nonpositive request rejected");
        final String newer = VECTOR.replace("\"revision\":7", "\"revision\":8").replace("\"repsTarget\":12", "\"repsTarget\":14");
        WorkoutSession active = WorkoutSession.create(profiles(), store.load()); active.begin(10000);
        active.record(Exercise.SQUAT, 1, 10, 10001, "TARGET");
        service.receive(message(newer, 126), 1003);
        check(active.target(Exercise.SQUAT, 2) == 12 && active.startedAt() == 10000 && active.result(Exercise.SQUAT, 1).reps == 10,
                "received update cannot mutate an active session snapshot");
        WorkoutSession fresh = WorkoutSession.create(profiles(), store.load());
        check(fresh.target(Exercise.SQUAT, 1) == 10 && fresh.target(Exercise.SQUAT, 2) == 14,
                "next session takes each set target from the latest routine");
        String variedPush = VECTOR.replace("{\"setId\":\"push-ups-1\",\"repsTarget\":5}",
                "{\"setId\":\"push-ups-1\",\"repsTarget\":8},{\"setId\":\"push-ups-2\",\"repsTarget\":10}");
        WorkoutSession allVaried = WorkoutSession.create(profiles(), WorkoutTemplate.parse(variedPush.getBytes("UTF-8")));
        check(allVaried.target(Exercise.SQUAT, 2) == 12 && allVaried.target(Exercise.PUSH_UP, 1) == 8
                && allVaried.target(Exercise.PUSH_UP, 2) == 10 && allVaried.target(Exercise.PULL_UP, 1) == 4,
                "Squats 10/12, Push-ups 8/10 and Pull-ups 4 remain independent per-set goals");
        rejected(new Action() { public void run() throws Exception { handler.receive(frame, 1004); } }, "old revision rejected");
        rejected(new Action() { public void run() throws Exception { handler.receive(message(newer.replace(":14", ":15"), 127), 1004); } }, "changed content at the same revision rejected");
        final String other = VECTOR.replace("web-routine-1", "another-routine").replace("\"revision\":7", "\"revision\":1");
        rejected(new Action() { public void run() throws Exception { handler.receive(message(other, 128), 1004); } }, "different context requires local confirmation");
        service.allowReplacement(2000); service.receive(message(other, 129), 2001);
        check(store.load().revision == 1 && store.load().id.equals("another-routine"), "explicit local handoff permits another owner's lower revision");
        rejected(new Action() { public void run() throws Exception { handler.receive(frame, 2002); } }, "handoff authorization is consumed once");
        service.allowReplacement(3000);
        rejected(new Action() { public void run() throws Exception { handler.receive(frame, 123000); } }, "handoff authorization expires after two minutes");
        final TemplateService restarted = new TemplateService(store);
        service.allowReplacement(200000);
        rejected(new Action() { public void run() throws Exception { restarted.receive(frame, 200001); } }, "restart does not retain replacement authorization");

        File failureRoot = root(base, "readback-failure");
        new TemplateStore(failureRoot).accept(WorkoutTemplate.parse(bytes), false);
        final TemplateStore failReadback = new TemplateStore(failureRoot) {
            @Override void verifyStored(WorkoutTemplate expected) throws IOException {
                super.verifyStored(expected); throw new IOException("Synthetic readback failure");
            }
        };
        rejected(new Action() { public void run() throws Exception {
            new TemplateService(failReadback).receive(message(newer, 130), 1);
        } }, "readback failure emits no ACK");
        check(new TemplateStore(failureRoot).load().hash.equals(HASH), "failed replacement restores the previous valid template");
        File pending = new File(files, "workout-template/pending.template");
        if (!pending.mkdir()) throw new IOException("Cannot simulate full/unwritable storage");
        rejected(new Action() { public void run() throws Exception {
            handler.receive(message(other.replace("\"revision\":1", "\"revision\":2"), 131), 200002);
        } }, "write failure emits no ACK");
        check(store.load().revision == 1 && pending.delete(), "write failure preserves the valid active routine");
        File backup = new File(record.getPath() + ".bak");
        if (!record.renameTo(backup)) throw new IOException("Cannot simulate interrupted replacement");
        FileOutputStream corrupt = new FileOutputStream(record); corrupt.write(1); corrupt.close();
        check(new TemplateStore(files).load().revision == 1 && !backup.exists(), "restart recovers an interrupted replacement from the committed backup");

        WorkoutSession restored = WorkoutSession.read(active.csv());
        check(restored.target(Exercise.SQUAT, 2) == 12 && restored.result(Exercise.SQUAT, 1).target == 10,
                "numbered PLAN rows retain different future targets across restart");
        restored.record(Exercise.SQUAT, 2, 12, 10002, "TARGET");
        restored.record(Exercise.PUSH_UP, 1, 5, 10003, "TARGET");
        restored.record(Exercise.PULL_UP, 1, 4, 10004, "TARGET");
        String results = WorkoutJson.encode(restored);
        check(results.contains("\"repsTarget\":12") && !results.contains("workoutId") && !results.contains("templateRevision")
                && results.contains("\"schemaVersion\":1"), "results schema 1 retains per-set targets without new template fields");
        check(WorkoutSession.read(restored.csv()).csv().equals(restored.csv()), "completed variable-target CSV round trip is lossless");
        WorkoutSession fallback = WorkoutSession.create(profiles(), null), oldStyle = new WorkoutSession(profiles());
        check(fallback.csv().replace(fallback.id, oldStyle.id).equals(oldStyle.csv()), "no-template fallback retains the original uniform CSV output");
        String hundred = VECTOR.replace("\"repsTarget\":10", "\"repsTarget\":100");
        WorkoutSession hundredSession = WorkoutSession.create(profiles(), WorkoutTemplate.parse(hundred.getBytes("UTF-8")));
        Profile profile = new Profile(Exercise.SQUAT);
        check(profile.change(Profile.Setting.TARGET, 100) && !profile.change(Profile.Setting.TARGET, 101)
                && hundredSession.target(Exercise.SQUAT, 1) == 100, "model and HUD profile accept 100 without clamping");
        MotionEngine engine = new MotionEngine(profile); engine.correct(100);
        hundredSession.record(Exercise.SQUAT, 1, engine.count(), 11000, "TARGET");
        check(engine.count() == 100 && WorkoutSession.read(hundredSession.csv()).result(Exercise.SQUAT, 1).target == 100,
                "counter and CSV preserve one hundred actual and target repetitions");
        WorkoutSession manual = new WorkoutSession(active);
        manual.target(Exercise.SQUAT, 1, 11); manual.resize(Exercise.SQUAT, 3);
        check(manual.target(Exercise.SQUAT, 2) == 12 && manual.target(Exercise.SQUAT, 3) == 12,
                "manual edits and added sets do not flatten other existing targets");
        System.out.println(passed + " template checks passed");
    }
}
