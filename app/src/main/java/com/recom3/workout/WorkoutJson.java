package com.recom3.workout;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Canonical UTF-8 session payload. Only measured/stored values are included. */
final class WorkoutJson {
    static long completedAt(WorkoutSession session) {
        long last = 0;
        for (Exercise exercise : session.exercises()) {
            for (int number = 1; number <= 20; number++) {
                WorkoutSession.SetResult result = session.result(exercise, number);
                if (result != null) last = Math.max(last, result.completedAt);
            }
        }
        return last;
    }

    static String exerciseId(Exercise exercise) {
        if (!exercise.legacy()) throw new IllegalArgumentException("Not a v1 exercise");
        return exercise.id;
    }

    static String utc(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(timestamp));
    }

    static String encode(WorkoutSession session) throws IOException {
        if (!session.legacy() || !session.complete() || !session.started()) throw new IOException("Workout is incomplete");
        StringBuilder json = new StringBuilder(8192);
        json.append("{\"schemaVersion\":1,\"sessionId\":\"").append(session.id)
                .append("\",\"startedAt\":\"").append(utc(session.startedAt()))
                .append("\",\"completedAt\":\"").append(utc(completedAt(session)))
                .append("\",\"status\":\"completed\",\"source\":\"recom3-workout\",\"exercises\":[");
        boolean firstExercise = true;
        for (Exercise exercise : Exercise.values()) {
            if (!firstExercise) json.append(',');
            firstExercise = false;
            String id = exerciseId(exercise);
            json.append("{\"exerciseId\":\"").append(id).append("\",\"repsTarget\":")
                    .append(session.target(exercise)).append(",\"setsTarget\":")
                    .append(session.plannedSets(exercise)).append(",\"sets\":[");
            boolean firstSet = true;
            for (int number = 1; number <= 20; number++) {
                WorkoutSession.SetResult result = session.result(exercise, number);
                if (result == null) continue;
                if (!firstSet) json.append(',');
                firstSet = false;
                json.append("{\"setId\":\"").append(id).append('-').append(number)
                        .append("\",\"setNumber\":").append(number)
                        .append(",\"repsTarget\":").append(result.target)
                        .append(",\"setsTargetAtCompletion\":").append(result.plannedSets)
                        .append(",\"repsCompleted\":").append(result.reps)
                        .append(",\"completedAt\":\"").append(utc(result.completedAt))
                        .append("\",\"completionReason\":\"").append(result.reason).append("\"}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }
}
