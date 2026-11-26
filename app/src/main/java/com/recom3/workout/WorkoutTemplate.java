package com.recom3.workout;

import java.io.IOException;
import java.util.*;

/** Exact Snow3 TEMPLATE schema 1, retaining the original bytes for its receipt digest. */
final class WorkoutTemplate {
    static final int MAX_WIRE = 16402;
    static final long MAX_REVISION = 9007199254740991L;
    final String id, hash;
    final long revision;
    private final byte[] bytes;
    private final int[][] targets;
    private WorkoutTemplate(String id, long revision, byte[] bytes, int[][] targets) throws IOException {
        this.id = id; this.revision = revision; this.bytes = bytes.clone(); this.targets = targets;
        hash = WorkoutWire.sha256(bytes);
    }
    byte[] bytes() { return bytes.clone(); }
    int[] targets(Exercise exercise) { return targets[exercise.ordinal()].clone(); }
    static WorkoutTemplate parse(byte[] bytes) throws IOException {
        Map<String, Object> root = TemplateJson.parse(bytes, MAX_WIRE);
        TemplateJson.fields(root, "schemaVersion", "workoutId", "revision", "exercises");
        TemplateJson.number(root.get("schemaVersion"), 1);
        Object id = root.get("workoutId");
        if (!(id instanceof String) || !((String) id).matches("[A-Za-z0-9-]{1,100}")) throw TemplateJson.invalid();
        long revision = TemplateJson.number(root.get("revision"), MAX_REVISION);
        List<?> exercises = TemplateJson.array(root.get("exercises"));
        if (exercises.size() != 3) throw TemplateJson.invalid();
        String[] ids = {"squats", "push-ups", "pull-ups"}, names = {"Squats", "Push-ups", "Pull-ups"};
        Exercise[] order = {Exercise.SQUAT, Exercise.PUSH_UP, Exercise.PULL_UP};
        int[][] targets = new int[3][];
        for (int i = 0; i < 3; i++) {
            Map<String, Object> exercise = TemplateJson.object(exercises.get(i));
            TemplateJson.fields(exercise, "exerciseId", "name", "sets");
            if (!ids[i].equals(exercise.get("exerciseId")) || !names[i].equals(exercise.get("name"))) throw TemplateJson.invalid();
            List<?> sets = TemplateJson.array(exercise.get("sets"));
            if (sets.isEmpty() || sets.size() > 20) throw TemplateJson.invalid();
            int[] goals = new int[sets.size()];
            for (int j = 0; j < goals.length; j++) {
                Map<String, Object> set = TemplateJson.object(sets.get(j));
                TemplateJson.fields(set, "setId", "repsTarget");
                if (!(ids[i] + "-" + (j + 1)).equals(set.get("setId"))) throw TemplateJson.invalid();
                goals[j] = (int) TemplateJson.number(set.get("repsTarget"), 100);
            }
            targets[order[i].ordinal()] = goals;
        }
        return new WorkoutTemplate((String) id, revision, bytes, targets);
    }
    byte[] receipt() throws IOException {
        return ("{\"schemaVersion\":1,\"workoutId\":\"" + id + "\",\"revision\":" + revision
                + ",\"status\":\"stored\"}").getBytes("UTF-8");
    }
}
