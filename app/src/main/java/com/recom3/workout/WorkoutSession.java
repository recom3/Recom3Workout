package com.recom3.workout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
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
    private final int[] targets = new int[Exercise.values().length];
    private final int[] sets = new int[Exercise.values().length];
    private final int[][] setTargets = new int[Exercise.values().length][20];
    private final List<SetResult> results = new ArrayList<SetResult>();

    WorkoutSession(Profile[] profiles) {
        this(UUID.randomUUID().toString(), 0);
        for (Profile profile : profiles) {
            plan(profile.exercise, profile.get(Profile.Setting.TARGET), profile.get(Profile.Setting.SETS));
        }
    }

    WorkoutSession(WorkoutSession other) {
        this(other.id, other.startedAt);
        System.arraycopy(other.targets, 0, targets, 0, targets.length);
        System.arraycopy(other.sets, 0, sets, 0, sets.length);
        for (int i = 0; i < setTargets.length; i++)
            System.arraycopy(other.setTargets[i], 0, setTargets[i], 0, 20);
        results.addAll(other.results);
    }

    static WorkoutSession create(Profile[] fallback, WorkoutTemplate template) {
        WorkoutSession session = new WorkoutSession(fallback);
        if (template != null) {
            for (Exercise exercise : Exercise.values()) {
                int[] goals = template.targets(exercise);
                session.plan(exercise, goals[0], goals.length);
                for (int number = 1; number <= goals.length; number++) session.target(exercise, number, goals[number - 1]);
            }
        }
        return session;
    }

    private WorkoutSession(String id, long startedAt) {
        if (!UUID.fromString(id).toString().equals(id) || startedAt < 0) {
            throw new IllegalArgumentException("Invalid workout identity");
        }
        this.id = id;
        this.startedAt = startedAt;
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
        targets[exercise.ordinal()] = target;
        sets[exercise.ordinal()] = totalSets;
        java.util.Arrays.fill(setTargets[exercise.ordinal()], target);
    }

    int target(Exercise exercise) {
        return targets[exercise.ordinal()];
    }

    int plannedSets(Exercise exercise) {
        return sets[exercise.ordinal()];
    }

    int target(Exercise exercise, int number) {
        if (number < 1 || number > 20) throw new IllegalArgumentException("Invalid set number");
        return setTargets[exercise.ordinal()][number - 1];
    }

    void target(Exercise exercise, int number, int value) {
        if (number < 1 || number > plannedSets(exercise) || value < 1 || value > 100)
            throw new IllegalArgumentException("Invalid set target");
        setTargets[exercise.ordinal()][number - 1] = value;
        if (number == 1) targets[exercise.ordinal()] = value;
    }

    void resize(Exercise exercise, int totalSets) {
        if (totalSets < 1 || totalSets > 20) throw new IllegalArgumentException("Invalid set count");
        int previous = plannedSets(exercise);
        for (int number = previous + 1; number <= totalSets; number++)
            setTargets[exercise.ordinal()][number - 1] = target(exercise, previous);
        sets[exercise.ordinal()] = totalSets;
    }

    SetResult result(Exercise exercise, int number) {
        for (SetResult result : results) {
            if (result.exercise == exercise && result.number == number) return result;
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
        for (int i = 0; i < results.size(); i++) {
            SetResult previous = results.get(i);
            if (previous.exercise == exercise && previous.number == number) {
                results.set(i, next);
                return;
            }
        }
        results.add(next);
    }

    void resetFrom(Exercise exercise, int number) {
        for (int i = results.size() - 1; i >= 0; i--) {
            SetResult result = results.get(i);
            if (result.exercise == exercise && result.number >= number) results.remove(i);
        }
    }

    int completedSets(Exercise exercise) {
        int count = 0;
        for (SetResult result : results) {
            if (result.exercise == exercise) count++;
        }
        return count;
    }

    int reps(Exercise exercise) {
        int count = 0;
        for (SetResult result : results) {
            if (result.exercise == exercise) count += result.reps;
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
        for (Exercise exercise : Exercise.values()) {
            if (completedSets(exercise) < plannedSets(exercise)) return false;
        }
        return true;
    }

    Exercise nextExercise(Exercise current) {
        Exercise[] exercises = Exercise.values();
        for (int offset = 0; offset < exercises.length; offset++) {
            Exercise candidate = exercises[(current.ordinal() + offset) % exercises.length];
            if (completedSets(candidate) < plannedSets(candidate)) return candidate;
        }
        return null;
    }

    String csv() {
        StringBuilder output = new StringBuilder(4096);
        output.append(HEADER).append('\n');
        for (Exercise exercise : Exercise.values()) {
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
        for (Exercise exercise : Exercise.values()) {
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
                if (session.target(exercise) < 1 || session.plannedSets(exercise) < 1)
                    throw new IOException("Missing workout plan");
            }
            return session;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid workout data", failure);
        }
    }
}
