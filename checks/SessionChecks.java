package com.recom3.workout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class SessionChecks {
    private static int passed;

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++;
        System.out.println("PASS " + description);
    }

    private static Profile[] profiles(int sets) {
        Exercise[] exercises = Exercise.values();
        Profile[] profiles = new Profile[exercises.length];
        for (int i = 0; i < profiles.length; i++) {
            profiles[i] = new Profile(exercises[i]);
            profiles[i].change(Profile.Setting.SETS, sets);
        }
        profiles[Exercise.PULL_UP.ordinal()].change(Profile.Setting.TARGET, 4);
        profiles[Exercise.SQUAT.ordinal()].change(Profile.Setting.TARGET, 10);
        profiles[Exercise.PUSH_UP.ordinal()].change(Profile.Setting.TARGET, 5);
        return profiles;
    }

    private static void write(File file, String content) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(content.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    public static void main(String[] args) throws Exception {
        WorkoutSession progression = new WorkoutSession(profiles(2));
        progression.record(Exercise.SQUAT, 1, 10, 1000, "TARGET");
        check(progression.nextExercise(Exercise.SQUAT) == Exercise.SQUAT,
                "finishing a set keeps the same exercise while sets remain");
        progression.record(Exercise.SQUAT, 2, 10, 2000, "TARGET");
        check(progression.nextExercise(Exercise.SQUAT) == Exercise.PUSH_UP,
                "finishing all squat sets advances to push-ups");
        progression.record(Exercise.PUSH_UP, 1, 5, 3000, "TARGET");
        progression.record(Exercise.PUSH_UP, 2, 5, 4000, "TARGET");
        check(progression.nextExercise(Exercise.PUSH_UP) == Exercise.PULL_UP,
                "exercise progression wraps to unfinished pull-ups");
        progression.record(Exercise.PULL_UP, 1, 4, 5000, "TARGET");
        progression.record(Exercise.PULL_UP, 2, 4, 6000, "TARGET");
        check(progression.nextExercise(Exercise.PULL_UP) == null && progression.complete(),
                "last set ends progression at the workout summary");
        progression.resetFrom(Exercise.SQUAT, 2);
        check(progression.nextExercise(Exercise.PUSH_UP) == Exercise.SQUAT,
                "progression skips exercises whose sets are already complete");
        WorkoutSession session = new WorkoutSession(profiles(4));
        session.begin(1000);
        session.begin(2000);
        check(!session.complete() && session.csv().contains(",1000,"), "session begins once and starts incomplete");
        Exercise[] circuit = {Exercise.SQUAT, Exercise.PUSH_UP, Exercise.PULL_UP};
        long now = 2000;
        for (int round = 1; round <= 4; round++) {
            for (Exercise exercise : circuit) {
                session.record(exercise, round, session.target(exercise), now += 1000, "TARGET");
                boolean last = round == 4 && exercise == Exercise.PULL_UP;
                check(session.complete() == last, "complete only after every exercise reaches its set goal");
            }
        }
        check(session.reps(Exercise.SQUAT) == 40 && session.reps(Exercise.PUSH_UP) == 20
                && session.reps(Exercise.PULL_UP) == 16 && session.totalReps() == 76
                && session.totalCompletedSets() == 12, "four rounds produce 40 squats, 20 push-ups and 16 pull-ups");
        session.record(Exercise.PULL_UP, 4, 4, now, "TARGET");
        check(session.totalCompletedSets() == 12 && session.totalReps() == 76, "retrying a set save does not duplicate totals");
        WorkoutSession correction = new WorkoutSession(session);
        correction.record(Exercise.PULL_UP, 4, 3, now, "CORRECTED");
        check(correction.reps(Exercise.PULL_UP) == 15 && session.reps(Exercise.PULL_UP) == 16,
                "corrected counts update one set and preserve the original snapshot");
        correction.resetFrom(Exercise.PULL_UP, 4);
        check(!correction.complete() && correction.completedSets(Exercise.PULL_UP) == 3
                && correction.reps(Exercise.SQUAT) == 40, "resetting a completed set reopens only its exercise");

        session.plan(Exercise.SQUAT, 12, 5);
        check(!session.complete() && session.result(Exercise.SQUAT, 1).target == 10,
                "new goals apply while completed sets retain their recorded targets");
        session.record(Exercise.SQUAT, 5, 11, now += 1000, "MANUAL");
        check(session.complete() && session.reps(Exercise.SQUAT) == 51,
                "manually advanced sets preserve actual repetitions");
        String csv = session.csv();
        WorkoutSession restored = WorkoutSession.read(csv);
        check(restored.csv().equals(csv) && restored.totalReps() == 87,
                "CSV restores plans, corrected counts, timestamps and totals");
        boolean malformedRejected = false;
        try { WorkoutSession.read(csv + "SET,incomplete\n"); }
        catch (IOException expected) { malformedRejected = true; }
        check(malformedRejected, "truncated CSV is rejected");
        boolean gapRejected = false;
        try { new WorkoutSession(profiles(4)).record(Exercise.SQUAT, 2, 10, now, "TARGET"); }
        catch (IllegalArgumentException expected) { gapRejected = true; }
        check(gapRejected, "set numbering cannot skip an unrecorded set");

        File root = new File(args[0], "sessions-" + UUID.randomUUID());
        if (!root.mkdirs()) throw new IOException("Cannot create test directory");
        File exports = new File(root, "exports");
        SessionStore store = new SessionStore(root, exports);
        store.save(session);
        check(store.load().csv().equals(csv), "active workout survives a store reload");
        File exported = store.export(session);
        check(exported.isFile() && exported.length() == csv.getBytes("UTF-8").length,
                "completed workout exports as UTF-8 CSV");
        File internal = new File(root, "workouts/workout-" + session.id + ".csv");
        write(new File(internal.getPath() + ".bak"), csv);
        write(internal, "interrupted write");
        check(store.load().csv().equals(csv), "interrupted internal save recovers its previous CSV");
        File pointer = new File(root, "workouts/active.txt");
        write(new File(pointer.getPath() + ".bak"), session.id);
        write(pointer, "interrupted pointer");
        check(store.load().id.equals(session.id), "interrupted active pointer restores the saved workout");
        WorkoutSession fresh = new WorkoutSession(profiles(4));
        store.save(fresh);
        check(store.load().totalReps() == 0 && store.exportAll() == 1,
                "new workout starts empty and preserves the previous export");
        File blocked = new File(root, "blocked");
        write(blocked, "storage unavailable");
        SessionStore unavailable = new SessionStore(root, blocked);
        unavailable.save(session);
        boolean exportFailed = false;
        try { unavailable.export(session); }
        catch (IOException expected) { exportFailed = true; }
        check(exportFailed && unavailable.load().totalReps() == session.totalReps(),
                "external export failure preserves internal workout progress");
        check(store.exportAll() == 1, "saved workouts can be exported after external storage recovers");
        System.out.println(passed + " session checks passed");
    }
}
