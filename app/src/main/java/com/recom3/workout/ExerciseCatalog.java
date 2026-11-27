package com.recom3.workout;

import java.io.IOException;
import java.util.*;

/** Bounded data definitions, deliberately separate from local sensor calibration and goals. */
final class ExerciseCatalog {
    final String id;
    final long revision;
    private final LinkedHashMap<String, Exercise> definitions = new LinkedHashMap<String, Exercise>();
    private ExerciseCatalog(String id, long revision) { this.id = id; this.revision = revision; }
    Exercise get(String id) throws IOException {
        Exercise exercise = definitions.get(id);
        if (exercise == null) throw new IOException("Missing exercise definition");
        return exercise;
    }
    Exercise[] exercises() { return definitions.values().toArray(new Exercise[definitions.size()]); }
    static ExerciseCatalog parse(Object value) throws IOException {
        try {
            Map<String,Object> root = TemplateJson.object(value);
            TemplateJson.fields(root, "schemaVersion", "catalogId", "revision", "definitions");
            TemplateJson.number(root.get("schemaVersion"), 1);
            String id = (String) root.get("catalogId");
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}")) throw TemplateJson.invalid();
            ExerciseCatalog catalog = new ExerciseCatalog(id, TemplateJson.number(root.get("revision"), WorkoutTemplate.MAX_REVISION));
            List<?> definitions = TemplateJson.array(root.get("definitions"));
            if (definitions.isEmpty() || definitions.size() > 8) throw TemplateJson.invalid();
            for (Object item : definitions) {
                Map<String,Object> definition = TemplateJson.object(item);
                boolean description = definition.containsKey("description");
                if (description) TemplateJson.fields(definition, "exerciseId", "name", "description", "measurement", "definitionVersion", "detection");
                else TemplateJson.fields(definition, "exerciseId", "name", "measurement", "definitionVersion", "detection");
                if (!"repetitions".equals(definition.get("measurement"))) throw TemplateJson.invalid();
                Map<String,Object> detection = TemplateJson.object(definition.get("detection"));
                TemplateJson.fields(detection, "mode");
                if (!"local-profile-v1".equals(detection.get("mode"))) throw TemplateJson.invalid();
                Exercise exercise = new Exercise((String) definition.get("exerciseId"), (String) definition.get("name"),
                        description ? (String) definition.get("description") : "",
                        (int) TemplateJson.number(definition.get("definitionVersion"), Integer.MAX_VALUE));
                if (catalog.definitions.put(exercise.id, exercise) != null) throw TemplateJson.invalid();
            }
            return catalog;
        } catch (ClassCastException invalid) { throw TemplateJson.invalid(); }
          catch (IllegalArgumentException invalid) { throw TemplateJson.invalid(); }
    }
}
