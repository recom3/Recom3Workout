package com.recom3.workout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Completed sets and exercise goals, independent of sensors and Android views. */
final class WorkoutSession {
    static final String HEADER = "event,workout_id,started_at_ms,exercise,target_reps,total_sets,"
            + "set_number,reps,completed_at_ms,completed_at_utc,reason";

    static final class SetResult {
        final Exercise exercise;
        final int number, reps, target, plannedSets;
        final long completedAt;
        final String reason;
        String calibration;

        SetResult(Exercise exercise, int number, int reps, int target, int plannedSets,
                  long completedAt, String reason) {
            this.exercise = exercise;
            this.number = number;
            this.reps = reps;
            this.target = target;
            this.plannedSets = plannedSets;
            this.completedAt = completedAt;
            this.reason = reason;
        }
    }

    final String id;
    private long startedAt;
    private final LinkedHashMap<Exercise, int[]> plans = new LinkedHashMap<Exercise, int[]>();
    private final Map<Exercise, Integer> sets = new LinkedHashMap<Exercise, Integer>();
    private final Map<Exercise, Profile> profiles = new LinkedHashMap<Exercise, Profile>();
    boolean dynamic;
    String routineHash = "";
    private final List<SetResult> results = new ArrayList<SetResult>();

    WorkoutSession(Profile[] profiles) {
        this(UUID.randomUUID().toString(), 0);
        if (profiles.length < 1 || profiles.length > 8) throw new IllegalArgumentException("Exercise limit");
        for (Profile profile : profiles) {
            if (plans.containsKey(profile.exercise)) throw new IllegalArgumentException("Duplicate exercise");
            this.profiles.put(profile.exercise, profile.copy());
            plan(profile.exercise, profile.get(Profile.Setting.TARGET), profile.get(Profile.Setting.SETS));
        }
        dynamic = !legacyShape();
        if (totalPlannedSets() > 60) throw new IllegalArgumentException("Total set limit");
    }

    WorkoutSession(WorkoutSession other) {
        this(other.id, other.startedAt);
        for (Exercise exercise : other.exercises()) {
            plans.put(exercise, other.plans.get(exercise).clone());
            sets.put(exercise, other.sets.get(exercise));
            profiles.put(exercise, other.profile(exercise));
        }
        dynamic = other.dynamic; routineHash = other.routineHash;
        for (SetResult result : other.results) {
            SetResult copy = new SetResult(result.exercise, result.number, result.reps, result.target, result.plannedSets, result.completedAt, result.reason);
            copy.calibration = result.calibration; results.add(copy);
        }
    }

    static WorkoutSession create(Profile[] fallback, WorkoutTemplate template) {
        if (template != null) {
            Exercise[] order = template.dynamic() ? template.exercises() : Exercise.values();
            Profile[] selected = new Profile[order.length];
            for (int i = 0; i < order.length; i++) {
                for (Profile candidate : fallback) if (candidate.exercise.equals(order[i])) selected[i] = new Profile(order[i], candidate);
                if (selected[i] == null) throw new IllegalArgumentException("Missing local profile");
                int[] goals = template.targets(order[i]);
                selected[i].change(Profile.Setting.TARGET, goals[0]);
                selected[i].change(Profile.Setting.SETS, goals.length);
            }
            fallback = selected;
        }
        WorkoutSession session = new WorkoutSession(fallback);
        if (template != null) {
            if (template.dynamic()) session.dynamic = true;
            session.routineHash = template.hash;
            for (Exercise exercise : template.exercises()) {
                int[] goals = template.targets(exercise);
                session.plan(exercise, goals[0], goals.length);
                for (int number = 1; number <= goals.length; number++) session.target(exercise, number, goals[number - 1]);
            }
        }
        return session;
    }

    WorkoutSession(String id, long startedAt) {
        if (!UUID.fromString(id).toString().equals(id) || startedAt < 0) {
            throw new IllegalArgumentException("Invalid workout identity");
        }
        this.id = id;
        this.startedAt = startedAt;
    }

    Exercise[] exercises() { return plans.keySet().toArray(new Exercise[plans.size()]); }
    int indexOf(Exercise exercise) {
        Exercise[] list = exercises();
        for (int i = 0; i < list.length; i++) if (list[i].equals(exercise)) return i;
        throw new IllegalArgumentException("Exercise absent");
    }
    Exercise exercise(String id) {
        for (Exercise exercise : exercises()) if (exercise.id.equals(id)) return exercise;
        throw new IllegalArgumentException("Exercise absent");
    }
    private int[] goals(Exercise exercise) {
        int[] goals = plans.get(exercise);
        if (goals == null) throw new IllegalArgumentException("Exercise absent");
        return goals;
    }
    Profile profile(Exercise exercise) { return profiles.get(exercise).copy(); }
    void profile(Profile profile) { indexOf(profile.exercise); profiles.put(profile.exercise, profile.copy()); }
    private boolean legacyShape() {
        if (plans.size() != 3) return false;
        for (Exercise exercise : Exercise.values()) if (!plans.containsKey(exercise)) return false;
        return true;
    }
    boolean legacy() { return !dynamic && legacyShape(); }
    private int totalPlannedSets() {
        int count = 0; for (Integer value : sets.values()) count += value;
        return count;
    }

    void begin(long now) {
        if (now < 1) throw new IllegalArgumentException("Invalid workout timestamp");
        if (startedAt == 0) startedAt = now;
    }

    boolean started() {
        return startedAt != 0;
    }

    long startedAt() {
        return startedAt;
    }

    void plan(Exercise exercise, int target, int totalSets) {
        if (target < 1 || target > 100 || totalSets < 1 || totalSets > 20) {
            throw new IllegalArgumentException("Invalid workout goal");
        }
        if (!plans.containsKey(exercise) && plans.size() >= 8) throw new IllegalArgumentException("Exercise limit");
        int oldSets = sets.containsKey(exercise) ? sets.get(exercise) : 0;
        if (totalPlannedSets() - oldSets + totalSets > 60) throw new IllegalArgumentException("Total set limit");
        int[] goals = new int[20]; java.util.Arrays.fill(goals, target);
        plans.put(exercise, goals); sets.put(exercise, totalSets);
        if (!profiles.containsKey(exercise)) profiles.put(exercise, new Profile(exercise));
    }

    int target(Exercise exercise) {
        return goals(exercise)[0];
    }

    int plannedSets(Exercise exercise) {
        if (!sets.containsKey(exercise)) throw new IllegalArgumentException("Exercise absent");
        return sets.get(exercise);
    }

    int target(Exercise exercise, int number) {
        if (number < 1 || number > 20) throw new IllegalArgumentException("Invalid set number");
        return goals(exercise)[number - 1];
    }

    void target(Exercise exercise, int number, int value) {
        if (number < 1 || number > plannedSets(exercise) || value < 1 || value > 100)
            throw new IllegalArgumentException("Invalid set target");
        goals(exercise)[number - 1] = value;
    }

    void resize(Exercise exercise, int totalSets) {
        if (totalSets < 1 || totalSets > 20) throw new IllegalArgumentException("Invalid set count");
        int previous = plannedSets(exercise);
        if (totalPlannedSets() - previous + totalSets > 60) throw new IllegalArgumentException("Total set limit");
        for (int number = previous + 1; number <= totalSets; number++)
            goals(exercise)[number - 1] = target(exercise, previous);
        sets.put(exercise, totalSets);
    }

    SetResult result(Exercise exercise, int number) {
        for (SetResult result : results) {
            if (result.exercise.equals(exercise) && result.number == number) return result;
        }
        return null;
    }

    void record(Exercise exercise, int number, int reps, long now, String reason) {
        record(exercise, number, reps, now, reason, target(exercise, number), plannedSets(exercise));
    }

    private void record(Exercise exercise, int number, int reps, long now, String reason,
                        int target, int planned) {
        if (number < 1 || number > 20 || number > completedSets(exercise) + 1 || reps < 0 || now < 1
                || target < 1 || target > 100 || planned < 1 || planned > 20
                || (!reason.equals("TARGET") && !reason.equals("MANUAL") && !reason.equals("CORRECTED"))) {
            throw new IllegalArgumentException("Invalid set result");
        }
        begin(now);
        SetResult next = new SetResult(exercise, number, reps, target, planned, now, reason);
        next.calibration = profile(exercise).packed();
        for (int i = 0; i < results.size(); i++) {
            SetResult previous = results.get(i);
            if (previous.exercise.equals(exercise) && previous.number == number) {
                if (reason.equals("CORRECTED")) next.calibration = previous.calibration;
                results.set(i, next);
                return;
            }
        }
        results.add(next);
    }

    void resetFrom(Exercise exercise, int number) {
        for (int i = results.size() - 1; i >= 0; i--) {
            SetResult result = results.get(i);
            if (result.exercise.equals(exercise) && result.number >= number) results.remove(i);
        }
    }

    void restore(Exercise exercise, int number, int reps, long now, String reason, int target, int planned, String calibration) {
        Profile.unpack(exercise, calibration);
        record(exercise, number, reps, now, reason, target, planned);
        result(exercise, number).calibration = calibration;
    }

    int completedSets(Exercise exercise) {
        int count = 0;
        for (SetResult result : results) {
            if (result.exercise.equals(exercise)) count++;
        }
        return count;
    }

    int reps(Exercise exercise) {
        int count = 0;
        for (SetResult result : results) {
            if (result.exercise.equals(exercise)) count += result.reps;
        }
        return count;
    }

    int totalReps() {
        int count = 0;
        for (SetResult result : results) count += result.reps;
        return count;
    }

    int totalCompletedSets() {
        return results.size();
    }

    boolean complete() {
        if (plans.isEmpty()) return false;
        for (Exercise exercise : exercises()) {
            if (completedSets(exercise) < plannedSets(exercise)) return false;
        }
        return true;
    }

    Exercise nextExercise(Exercise current) {
        Exercise[] exercises = exercises();
        for (int offset = 0; offset < exercises.length; offset++) {
            Exercise candidate = exercises[(indexOf(current) + offset) % exercises.length];
            if (completedSets(candidate) < plannedSets(candidate)) return candidate;
        }
        return null;
    }

    String csv() {
        if (!legacy()) return DynamicSessionCsv.write(this);
        StringBuilder output = new StringBuilder(4096);
        output.append(HEADER).append('\n');
        for (Exercise exercise : exercises()) {
            row(output, "PLAN", exercise, target(exercise), plannedSets(exercise), 0, 0, 0, "");
            for (int number = 1; number <= plannedSets(exercise); number++) {
                if (target(exercise, number) != target(exercise))
                    row(output, "PLAN", exercise, target(exercise, number), plannedSets(exercise), number, 0, 0, "");
            }
        }
        long last = 0;
        for (SetResult result : results) {
            row(output, "SET", result.exercise, result.target, result.plannedSets,
                    result.number, result.reps, result.completedAt, result.reason);
            last = Math.max(last, result.completedAt);
        }
        int totalPlannedSets = 0;
        for (Exercise exercise : exercises()) {
            row(output, "SUMMARY", exercise, target(exercise), plannedSets(exercise),
                    completedSets(exercise), reps(exercise), 0, "");
            totalPlannedSets += plannedSets(exercise);
        }
        row(output, "TOTAL", null, 0, totalPlannedSets, totalCompletedSets(), totalReps(),
                complete() ? last : 0, complete() ? "COMPLETE" : "IN_PROGRESS");
        return output.toString();
    }

    private void row(StringBuilder output, String event, Exercise exercise, int target, int planned,
                     int number, int reps, long completedAt, String reason) {
        String utc = "";
        if (completedAt != 0) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            utc = format.format(completedAt);
        }
        output.append(event).append(',').append(id).append(',').append(startedAt).append(',')
                .append(exercise == null ? "ALL" : exercise.name()).append(',')
                .append(target).append(',').append(planned).append(',').append(number).append(',')
                .append(reps).append(',').append(completedAt).append(',').append(utc).append(',')
                .append(reason).append('\n');
    }

    static WorkoutSession read(String csv) throws IOException {
        if (csv.startsWith(DynamicSessionCsv.HEADER + "\n")) return DynamicSessionCsv.read(csv);
        try {
            BufferedReader reader = new BufferedReader(new StringReader(csv));
            if (!HEADER.equals(reader.readLine())) throw new IOException("Unknown workout format");
            WorkoutSession session = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1);
                if (cells.length != 11) throw new IOException("Incomplete workout row");
                long startedAt = Long.parseLong(cells[2]);
                if (session == null) session = new WorkoutSession(cells[1], startedAt);
                if (!session.id.equals(cells[1]) || session.startedAt != startedAt) {
                    throw new IOException("Mixed workout identities");
                }
                if (cells[0].equals("PLAN")) {
                    Exercise exercise = Exercise.valueOf(cells[3]);
                    int number = Integer.parseInt(cells[6]);
                    if (number == 0) session.plan(exercise, Integer.parseInt(cells[4]), Integer.parseInt(cells[5]));
                    else session.target(exercise, number, Integer.parseInt(cells[4]));
                } else if (cells[0].equals("SET")) {
                    Exercise exercise = Exercise.valueOf(cells[3]);
                    session.record(exercise, Integer.parseInt(cells[6]), Integer.parseInt(cells[7]),
                            Long.parseLong(cells[8]), cells[10], Integer.parseInt(cells[4]), Integer.parseInt(cells[5]));
                } else if (!cells[0].equals("SUMMARY") && !cells[0].equals("TOTAL")) {
                    throw new IOException("Unknown workout event");
                }
            }
            if (session == null) throw new IOException("Empty workout");
            for (Exercise exercise : Exercise.values()) {
                if (!session.plans.containsKey(exercise) || session.target(exercise) < 1 || session.plannedSets(exercise) < 1)
                    throw new IOException("Missing workout plan");
            }
            return session;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid workout data", failure);
        }
    }
}
