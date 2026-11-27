package com.recom3.workout;

import java.io.*;

/** Self-contained v2 snapshots: recovery never resolves against the latest dictionary/profile. */
final class DynamicSessionCsv {
    static final String HEADER = "RECOM3_WORKOUT_CSV_V2";
    private static final String COLUMNS = "event,session_id,started_at_ms,exercise_id,name,description,definition_version,set_number,reps_target,total_sets,reps_completed,completed_at_ms,reason,profile,routine_hash";
    static String write(WorkoutSession session) {
        StringBuilder text = new StringBuilder(HEADER + "\n" + COLUMNS + "\n");
        for (Exercise exercise : session.exercises()) {
            for (int number = 1; number <= session.plannedSets(exercise); number++)
                row(text, session, exercise, "PLAN", number, session.target(exercise, number), session.plannedSets(exercise), 0, 0, "", session.profile(exercise).packed());
        }
        for (Exercise exercise : session.exercises()) for (int number = 1; number <= 20; number++) {
            WorkoutSession.SetResult result = session.result(exercise, number);
            if (result != null) row(text, session, exercise, "SET", number, result.target, result.plannedSets,
                    result.reps, result.completedAt, result.reason, result.calibration);
        }
        try { return text.toString() + "END," + WorkoutWire.sha256(text.toString().getBytes("UTF-8")) + "\n"; }
        catch (IOException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void row(StringBuilder out, WorkoutSession session, Exercise exercise, String event,
                            int number, int target, int planned, int reps, long time, String reason, String profile) {
        out.append(CsvFields.line(event, session.id, Long.toString(session.startedAt()), exercise.id,
                CsvFields.display(exercise.label), CsvFields.display(exercise.description), Integer.toString(exercise.definitionVersion),
                Integer.toString(number), Integer.toString(target), Integer.toString(planned), Integer.toString(reps),
                Long.toString(time), reason, profile, session.routineHash));
    }
    static WorkoutSession read(String text) throws IOException {
        try {
            if (text.length() > 262144) throw new IOException("Workout too large");
            int footer = text.lastIndexOf("END,");
            if (footer < 1 || !text.substring(footer).matches("END,[a-f0-9]{64}\\n")
                    || !WorkoutWire.sha256(text.substring(0, footer).getBytes("UTF-8")).equals(text.substring(footer + 4, footer + 68)))
                throw new IOException("Incomplete v2 snapshot");
            text = text.substring(0, footer);
            BufferedReader input = new BufferedReader(new StringReader(text));
            if (!HEADER.equals(input.readLine()) || !COLUMNS.equals(input.readLine())) throw new IOException("Invalid v2 header");
            WorkoutSession session = null; String line; int rows = 0;
            while ((line = input.readLine()) != null) {
                if (++rows > 220) throw new IOException("Too many workout rows");
                String[] c = CsvFields.read(line);
                if (c.length != 15) throw new IOException("Incomplete v2 row");
                long start = Long.parseLong(c[2]);
                if (session == null) { session = new WorkoutSession(c[1], start); session.dynamic = true; session.routineHash = c[14]; }
                if (!session.id.equals(c[1]) || session.startedAt() != start || !session.routineHash.equals(c[14])
                        || !(c[14].isEmpty() || c[14].matches("[a-f0-9]{64}"))) throw new IOException("Mixed v2 identities");
                Exercise exercise = new Exercise(c[3], CsvFields.undisplay(c[4]), CsvFields.undisplay(c[5]), Integer.parseInt(c[6]));
                Profile profile = Profile.unpack(exercise, c[13]);
                int number = Integer.parseInt(c[7]), target = Integer.parseInt(c[8]), planned = Integer.parseInt(c[9]);
                if (c[0].equals("PLAN")) {
                    if (number == 1) {
                        if (session.exercises().length >= 8) throw new IOException("Exercise limit");
                        for (Exercise old : session.exercises()) if (old.equals(exercise)) throw new IOException("Duplicate plan");
                        session.plan(exercise, target, planned); session.profile(profile);
                    } else {
                        Exercise saved = session.exercise(exercise.id);
                        if (!saved.label.equals(exercise.label) || saved.definitionVersion != exercise.definitionVersion
                                || session.plannedSets(saved) != planned || !session.profile(saved).packed().equals(profile.packed()))
                            throw new IOException("Mixed plan snapshot");
                        session.target(saved, number, target);
                    }
                } else if (c[0].equals("SET")) {
                    Exercise saved = session.exercise(exercise.id);
                    if (!saved.label.equals(exercise.label) || saved.definitionVersion != exercise.definitionVersion) throw new IOException("Mixed result definition");
                    if (session.result(saved, number) != null) throw new IOException("Duplicate result");
                    session.restore(saved, number, Integer.parseInt(c[10]), Long.parseLong(c[11]), c[12], target, planned, c[13]);
                } else throw new IOException("Unknown v2 row");
            }
            if (session == null || session.exercises().length == 0) throw new IOException("Empty workout");
            return session;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid dynamic workout", invalid); }
    }
}
