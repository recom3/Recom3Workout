package com.recom3.workout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Keeps an internal CSV archive and exports copies to the app's external-files directory. */
final class SessionStore {
    private final File directory;
    private final File exports;
    private final File active;

    SessionStore(File filesDirectory, File exports) {
        this.directory = new File(filesDirectory, "workouts");
        this.exports = exports;
        active = new File(directory, "active.txt");
    }

    WorkoutSession load() throws IOException {
        recover(active);
        if (!active.exists()) return null;
        String id = text(active).trim();
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid active workout", failure);
        }
        return WorkoutSession.read(text(new File(directory, "workout-" + id + ".csv")));
    }

    void save(WorkoutSession session) throws IOException {
        atomicWrite(new File(directory, name(session)), session.csv());
        atomicWrite(active, session.id);
    }

    File export(WorkoutSession session) throws IOException {
        if (exports == null) throw new IOException("External storage unavailable");
        File file = new File(exports, name(session));
        atomicWrite(file, session.csv());
        return file;
    }

    int exportAll() throws IOException {
        if (exports == null) throw new IOException("External storage unavailable");
        File[] files = directory.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) {
            if (!file.getName().startsWith("workout-") || !file.getName().endsWith(".csv")) continue;
            WorkoutSession session = WorkoutSession.read(text(file));
            if (session.started()) {
                export(session);
                count++;
            }
        }
        return count;
    }

    private static String name(WorkoutSession session) {
        return "workout-" + session.id + ".csv";
    }

    private static String text(File file) throws IOException {
        recover(file);
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return new String(output.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }

    private static void recover(File file) throws IOException {
        File backup = new File(file.getPath() + ".bak");
        if (!backup.exists()) return;
        if (!backup.isFile()) throw new IOException("Invalid workout backup");
        if (file.exists() && !file.delete()) throw new IOException("Cannot recover workout");
        if (!backup.renameTo(file)) throw new IOException("Cannot restore workout backup");
    }

    private static void atomicWrite(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create workout directory");
        if (file.exists() && !file.isFile()) throw new IOException("Invalid workout file");
        recover(file);
        File backup = new File(file.getPath() + ".bak");
        if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot back up workout");
        try {
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(content.getBytes("UTF-8"));
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot commit workout");
        } catch (IOException failure) {
            if (file.exists() && !file.delete()) throw new IOException("Cannot remove incomplete workout", failure);
            if (backup.exists() && !backup.renameTo(file)) {
                throw new IOException("Cannot restore workout", failure);
            }
            throw failure;
        }
    }
}
