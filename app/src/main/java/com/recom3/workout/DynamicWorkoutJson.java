package com.recom3.workout;

import java.io.IOException;

/** Independent, local-only result schema; never serialized as SESSION v1. */
final class DynamicWorkoutJson {
    static String encode(WorkoutSession session) throws IOException {
        if (session.legacy() || !session.complete() || !session.started()) throw new IOException("Not a complete v2 session");
        StringBuilder out = new StringBuilder("{\"schemaVersion\":2,\"sessionId\":");
        out.append(JsonText.quote(session.id)).append(",\"startedAt\":").append(JsonText.quote(WorkoutJson.utc(session.startedAt())))
                .append(",\"completedAt\":").append(JsonText.quote(WorkoutJson.utc(WorkoutJson.completedAt(session))))
                .append(",\"status\":\"completed\",\"source\":\"recom3-workout\",\"routineSha256\":")
                .append(JsonText.quote(session.routineHash)).append(",\"exercises\":[");
        boolean first = true;
        for (Exercise exercise : session.exercises()) {
            if (!first) out.append(','); first = false;
            out.append("{\"exerciseId\":").append(JsonText.quote(exercise.id))
                    .append(",\"name\":").append(JsonText.quote(exercise.label))
                    .append(",\"definitionVersion\":").append(exercise.definitionVersion)
                    .append(",\"measurement\":\"repetitions\",\"sets\":[");
            boolean firstSet = true;
            for (int number = 1; number <= 20; number++) {
                WorkoutSession.SetResult result = session.result(exercise, number); if (result == null) continue;
                if (!firstSet) out.append(','); firstSet = false;
                Profile profile = Profile.unpack(exercise, result.calibration);
                out.append("{\"setId\":").append(JsonText.quote(exercise.id + "-" + number))
                        .append(",\"repsTarget\":").append(result.target).append(",\"repsCompleted\":").append(result.reps)
                        .append(",\"setsTargetAtCompletion\":").append(result.plannedSets)
                        .append(",\"completedAt\":").append(JsonText.quote(WorkoutJson.utc(result.completedAt)))
                        .append(",\"completionReason\":").append(JsonText.quote(result.reason))
                        .append(",\"captureMode\":").append(JsonText.quote(profile.manual() ? "manual" : "local-profile-v1"))
                        .append(",\"experimental\":").append(profile.experimental)
                        .append(",\"profileVersion\":1,\"profile\":").append(JsonText.quote(result.calibration)).append('}');
            }
            out.append("]}");
        }
        String json = out.append("]}").toString();
        if (json.getBytes("UTF-8").length > 65536) throw new IOException("V2 result size limit");
        return json;
    }
}
