package com.recom3.workout;

import java.io.*;

/** Queue-only atomic writes with a recoverable previous committed version. */
final class SyncFiles {
    static byte[] read(File file, int limit) throws IOException {
        InputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > limit - count) throw new IOException("File exceeds limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { input.close(); }
    }

    static void recover(File file) throws IOException {
        File backup = new File(file.getPath() + ".bak");
        if (!backup.exists()) return;
        if (!backup.isFile()) throw new IOException("Invalid sync backup");
        if (file.exists() && !file.delete()) throw new IOException("Cannot recover sync file");
        if (!backup.renameTo(file)) throw new IOException("Cannot restore sync backup");
    }

    static void write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create sync queue");
        recover(file);
        if (file.exists() && !file.isFile()) throw new IOException("Invalid sync file");
        File backup = new File(file.getPath() + ".bak");
        if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot back up sync file");
        try {
            FileOutputStream output = new FileOutputStream(file);
            try { output.write(bytes); output.getFD().sync(); }
            finally { output.close(); }
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot commit sync file");
        } catch (IOException failure) {
            if (file.exists() && !file.delete()) throw new IOException("Cannot discard partial sync file", failure);
            if (backup.exists() && !backup.renameTo(file)) throw new IOException("Cannot roll back sync file", failure);
            throw failure;
        }
    }
}
