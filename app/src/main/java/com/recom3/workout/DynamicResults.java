package com.recom3.workout;

import java.io.*;

/** Original-byte v2 outbox and digest receipts; CSVs and confirmed payloads are retained. */
final class DynamicResults {
    interface Receiver {
        boolean advertisesSessionV2();
        // Must be scoped to a negotiated live peer; returns exact session ID + payload digest on durable receipt.
        String[] sendAndAwait(String id, String sha256, byte[] json) throws IOException;
    }
    // Recovery, replacement and receipt commit must serialize even across callers/instances.
    private static final Object FILES = new Object();
    static final class Entry {
        final String id, hash;
        private final byte[] json;
        Entry(String id, byte[] json) throws IOException {
            try { if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid result identity", invalid); }
            this.id = id; this.json = json.clone(); hash = WorkoutWire.sha256(json);
        }
        byte[] bytes() { return json.clone(); }
        int size() { return json.length; }
    }
    private final File directory;
    private final java.util.List<String> unreadable = new java.util.ArrayList<String>();
    DynamicResults(File files) { directory = new File(files, "workout-results-v2"); }
    Entry enqueue(WorkoutSession session) throws IOException {
        byte[] bytes = DynamicWorkoutJson.encode(session).getBytes("UTF-8");
        Entry entry = new Entry(session.id, bytes);
        synchronized (FILES) {
            File file = new File(directory, session.id + ".json"); SyncFiles.recover(file);
            if (file.exists() && java.util.Arrays.equals(SyncFiles.read(file, 65536), bytes)) return entry;
            SyncFiles.write(file, bytes);
            return entry;
        }
    }
    java.util.List<Entry> entries() throws IOException {
        synchronized (FILES) {
            unreadable.clear();
            java.util.List<Entry> entries = new java.util.ArrayList<Entry>();
            if (!directory.exists()) return entries;
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Cannot read dynamic results");
            java.util.Set<String> ids = new java.util.TreeSet<String>();
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
                if (name.matches("[a-f0-9-]{36}\\.json")) ids.add(name.substring(0, 36));
            }
            for (String id : ids) {
                try { entries.add(read(id)); }
                catch (IOException invalid) { unreadable.add(id); } // Preserve damaged entries; send healthy ones.
            }
            return entries;
        }
    }
    java.util.List<String> unreadable() {
        synchronized (FILES) { return new java.util.ArrayList<String>(unreadable); }
    }
    private Entry read(String id) throws IOException {
        File file = new File(directory, id + ".json"); SyncFiles.recover(file);
        return new Entry(id, SyncFiles.read(file, 65536));
    }
    boolean current(Entry entry) throws IOException {
        synchronized (FILES) {
            File file = new File(directory, entry.id + ".json"); SyncFiles.recover(file);
            return file.exists() && read(entry.id).hash.equals(entry.hash);
        }
    }
    boolean confirmed(Entry entry) throws IOException {
        synchronized (FILES) {
            File receipt = new File(directory, entry.id + ".receipt"); SyncFiles.recover(receipt);
            return current(entry) && receipt.exists()
                    && TemplateJson.utf8(SyncFiles.read(receipt, 64)).equals(entry.hash);
        }
    }
    boolean confirm(Entry entry) throws IOException {
        synchronized (FILES) {
            if (!current(entry)) return false;
            File receipt = new File(directory, entry.id + ".receipt");
            SyncFiles.write(receipt, entry.hash.getBytes("UTF-8"));
            return confirmed(entry);
        }
    }
    boolean confirmed(WorkoutSession session) throws IOException {
        return confirmed(new Entry(session.id, DynamicWorkoutJson.encode(session).getBytes("UTF-8")));
    }
    // Retained integration boundary for existing model tests and explicitly capable receivers.
    boolean send(WorkoutSession session, Receiver receiver) throws IOException {
        enqueue(session);
        if (receiver == null || !receiver.advertisesSessionV2()) return false;
        Entry entry;
        synchronized (FILES) { entry = read(session.id); }
        if (entry.size() > 20000) return false;
        String[] receipt = receiver.sendAndAwait(entry.id, entry.hash, entry.bytes());
        return receipt != null && receipt.length == 2 && entry.id.equals(receipt[0])
                && entry.hash.equals(receipt[1]) && confirm(entry);
    }
}
