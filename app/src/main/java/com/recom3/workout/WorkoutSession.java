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
        results.addAll(other.results);
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
        if (target < 1 || target > 99 || totalSets < 1 || totalSets > 20) {
            throw new IllegalArgumentException("Invalid workout goal");
        }
        targets[exercise.ordinal()] = target;
        sets[exercise.ordinal()] = totalSets;
    }

    int target(Exercise exercise) {
        return targets[exercise.ordinal()];
    }

    int plannedSets(Exercise exercise) {
        return sets[exercise.ordinal()];
    }

    SetResult result(Exercise exercise, int number) {
        for (SetResult result : results) {
            if (result.exercise == exercise && result.number == number) return result;
        }
        return null;
    }

    void record(Exercise exercise, int number, int reps, long now, String reason) {
        if (number < 1 || number > 20 || number > completedSets(exercise) + 1 || reps < 0 || now < 1
                || (!reason.equals("TARGET") && !reason.equals("MANUAL") && !reason.equals("CORRECTED"))) {
            throw new IllegalArgumentException("Invalid set result");
        }
        begin(now);
        SetResult next = new SetResult(exercise, number, reps, target(exercise),
                plannedSets(exercise), now, reason);
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
                    session.plan(Exercise.valueOf(cells[3]), Integer.parseInt(cells[4]),
                            Integer.parseInt(cells[5]));
                } else if (cells[0].equals("SET")) {
                    Exercise exercise = Exercise.valueOf(cells[3]);
                    int target = session.target(exercise);
                    int sets = session.plannedSets(exercise);
                    session.plan(exercise, Integer.parseInt(cells[4]), Integer.parseInt(cells[5]));
                    session.record(exercise, Integer.parseInt(cells[6]), Integer.parseInt(cells[7]),
                            Long.parseLong(cells[8]), cells[10]);
                    session.plan(exercise, target, sets);
                } else if (!cells[0].equals("SUMMARY") && !cells[0].equals("TOTAL")) {
                    throw new IOException("Unknown workout event");
                }
            }
            if (session == null) throw new IOException("Empty workout");
            for (Exercise exercise : Exercise.values()) {
                session.plan(exercise, session.target(exercise), session.plannedSets(exercise));
            }
            return session;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid workout data", failure);
        }
    }
}
