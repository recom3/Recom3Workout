package com.recom3.workout;

import java.io.*;
import java.util.*;

/** One atomically committed entry per session: payload and delivery state cannot diverge. */
final class SyncQueue {
    enum State { PENDING, FAILED, SYNCED }
    static final class Entry {
        final String id, json, hash;
        final long completedAt;
        final State state;
        Entry(String id, long completedAt, String json, State state) throws IOException {
            try {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid session ID", invalid); }
            if (completedAt < 1 || json.getBytes("UTF-8").length > 20000) throw new IOException("Invalid payload size/time");
            this.id = id; this.completedAt = completedAt; this.json = json; this.state = state;
            hash = WorkoutWire.sha256(json.getBytes("UTF-8"));
        }
    }
    private static final int MAGIC = 0x57535131;
    private final File directory;
    private boolean readFailed;
    SyncQueue(File filesDirectory) { directory = new File(filesDirectory, "ice3-sync"); }
    private File file(String id) { return new File(directory, id + ".queue"); }

    Entry enqueue(WorkoutSession session) throws IOException {
        Entry entry = new Entry(session.id, WorkoutJson.completedAt(session), WorkoutJson.encode(session), State.PENDING);
        File file = file(entry.id);
        SyncFiles.recover(file);
        if (file.exists()) {
            Entry previous = read(file);
            if (previous.hash.equals(entry.hash)) return previous;
        }
        write(entry);
        return entry;
    }

    void mark(Entry entry, State state) throws IOException {
        Entry current = read(file(entry.id));
        if (!current.hash.equals(entry.hash)) throw new IOException("Stale acknowledgement");
        write(new Entry(current.id, current.completedAt, current.json, state));
    }

    List<Entry> entries() throws IOException {
        readFailed = false;
        if (!directory.exists()) return new ArrayList<Entry>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot read sync queue");
        Set<String> names = new TreeSet<String>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            if (name.matches("[a-f0-9-]{36}\\.queue")) names.add(name);
        }
        List<Entry> entries = new ArrayList<Entry>();
        for (String name : names) {
            try { entries.add(read(new File(directory, name))); }
            catch (IOException invalid) { readFailed = true; } // Preserve the file; retry other sessions.
        }
        Collections.sort(entries, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return a.completedAt < b.completedAt ? -1 : a.completedAt > b.completedAt ? 1 : a.id.compareTo(b.id);
            }
        });
        return entries;
    }

    boolean hadReadFailure() { return readFailed; }

    private Entry read(File file) throws IOException {
        SyncFiles.recover(file);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(SyncFiles.read(file, 22000)));
        try {
            if (input.readInt() != MAGIC) throw new IOException("Unknown queue version");
            String id = input.readUTF();
            State state = State.valueOf(input.readUTF());
            long completedAt = input.readLong();
            String hash = input.readUTF();
            int length = input.readInt();
            if (length < 1 || length > 20000 || length != input.available()) throw new IOException("Truncated queue entry");
            byte[] bytes = new byte[length]; input.readFully(bytes);
            Entry entry = new Entry(id, completedAt, new String(bytes, "UTF-8"), state);
            if (!file.getName().equals(id + ".queue") || !hash.equals(entry.hash)) throw new IOException("Queue checksum mismatch");
            return entry;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid queue state", invalid); }
        finally { input.close(); }
    }

    private void write(Entry entry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC); output.writeUTF(entry.id); output.writeUTF(entry.state.name());
        output.writeLong(entry.completedAt); output.writeUTF(entry.hash);
        byte[] json = entry.json.getBytes("UTF-8");
        output.writeInt(json.length); output.write(json); output.close();
        SyncFiles.write(file(entry.id), bytes.toByteArray());
    }
}
