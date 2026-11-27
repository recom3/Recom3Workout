package com.recom3.workout;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/** Repairs the CSV-to-queue crash window without writing to the CSV archive. */
final class SyncRecovery {
    static int recover(File files, SyncQueue queue, SyncBatch.Observer observer) {
        File directory = new File(files, "workouts");
        File[] archive = directory.listFiles();
        if (archive == null) return 0;
        Set<String> names = new TreeSet<String>();
        for (File file : archive) {
            String name = file.getName();
            if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            if (name.matches("workout-[a-f0-9-]{36}\\.csv")) names.add(name);
        }
        int failed = 0;
        for (String name : names) {
            try {
                File committed = new File(directory, name + ".bak");
                if (!committed.isFile()) committed = new File(directory, name);
                WorkoutSession session = WorkoutSession.read(new String(SyncFiles.read(committed, 262144), "UTF-8"));
                if (session.complete() && session.started()) {
                    if (!session.legacy()) {
                        new DynamicResults(files).enqueue(session);
                        observer.state(session.id, SyncQueue.State.PENDING);
                        continue;
                    }
                    SyncQueue.Entry entry = queue.enqueue(session);
                    observer.state(entry.id, entry.state);
                }
            } catch (IOException failure) { failed++; }
            catch (SecurityException failure) { failed++; }
        }
        return failed;
    }
}
