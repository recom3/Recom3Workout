package com.recom3.workout;

import java.io.IOException;
import java.util.*;

/** Jet-local experimental v2 bundle. No existing ICE3/API v1 contract is relaxed. */
final class DynamicRoutine {
    static WorkoutTemplate parse(byte[] bytes) throws IOException {
        try {
            Map<String,Object> root = TemplateJson.parse(bytes, WorkoutTemplate.MAX_WIRE);
            TemplateJson.fields(root, "schemaVersion", "catalog", "routine");
            if (TemplateJson.number(root.get("schemaVersion"), 2) != 2) throw TemplateJson.invalid();
            ExerciseCatalog catalog = ExerciseCatalog.parse(root.get("catalog"));
            Map<String,Object> routine = TemplateJson.object(root.get("routine"));
            TemplateJson.fields(routine, "workoutId", "revision", "catalogId", "catalogRevision", "exercises");
            if (!catalog.id.equals(routine.get("catalogId")) || catalog.revision != TemplateJson.number(routine.get("catalogRevision"), WorkoutTemplate.MAX_REVISION))
                throw TemplateJson.invalid();
            String id = (String) routine.get("workoutId");
            if (id == null || !id.matches("[A-Za-z0-9-]{1,100}")) throw TemplateJson.invalid();
            long revision = TemplateJson.number(routine.get("revision"), WorkoutTemplate.MAX_REVISION);
            List<?> entries = TemplateJson.array(routine.get("exercises"));
            if (entries.isEmpty() || entries.size() > 8) throw TemplateJson.invalid();
            LinkedHashMap<Exercise,int[]> plans = new LinkedHashMap<Exercise,int[]>();
            int total = 0;
            for (Object entry : entries) {
                Map<String,Object> item = TemplateJson.object(entry);
                TemplateJson.fields(item, "exerciseId", "definitionVersion", "sets");
                Exercise exercise = catalog.get((String) item.get("exerciseId"));
                if (exercise.definitionVersion != TemplateJson.number(item.get("definitionVersion"), Integer.MAX_VALUE)) throw TemplateJson.invalid();
                List<?> sets = TemplateJson.array(item.get("sets"));
                if (sets.isEmpty() || sets.size() > 20 || (total += sets.size()) > 60) throw TemplateJson.invalid();
                int[] goals = new int[sets.size()];
                for (int i = 0; i < goals.length; i++) {
                    Map<String,Object> set = TemplateJson.object(sets.get(i));
                    TemplateJson.fields(set, "setId", "repsTarget");
                    if (!(exercise.id + "-" + (i + 1)).equals(set.get("setId"))) throw TemplateJson.invalid();
                    goals[i] = (int) TemplateJson.number(set.get("repsTarget"), 100);
                }
                if (plans.put(exercise, goals) != null) throw TemplateJson.invalid();
            }
            return WorkoutTemplate.dynamic(id, revision, bytes, catalog, plans);
        } catch (ClassCastException invalid) { throw TemplateJson.invalid(); }
          catch (IllegalArgumentException invalid) { throw TemplateJson.invalid(); }
    }
}
