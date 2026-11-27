package com.recom3.workout;

import java.io.*;

/** Immutable definition versions, retained independently of the current routine and calibration. */
final class DefinitionStore {
    private final File directory;
    DefinitionStore(File files) { directory = new File(files, "exercise-definitions"); }
    void remember(Exercise exercise) throws IOException {
        synchronized (DefinitionStore.class) {
            File file = new File(directory, exercise.id + "-" + exercise.definitionVersion + ".definition");
            byte[] bytes = CsvFields.line(exercise.id, exercise.label, exercise.description,
                    Integer.toString(exercise.definitionVersion), exercise.measurement).getBytes("UTF-8");
            SyncFiles.recover(file);
            if (file.exists()) {
                if (!java.util.Arrays.equals(bytes, SyncFiles.read(file, 2048))) throw new IOException("Definition version conflict");
            } else SyncFiles.write(file, bytes);
        }
    }
}
