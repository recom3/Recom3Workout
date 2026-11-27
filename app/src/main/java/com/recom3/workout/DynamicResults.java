package com.recom3.workout;

import java.io.*;

/** Separate durable v2 outbox. No production sender is wired until a receiver contract exists. */
final class DynamicResults {
    interface Receiver {
        boolean advertisesSessionV2();
        // Must be scoped to a negotiated live peer; returns exact session ID + payload digest on durable receipt.
        String[] sendAndAwait(String id, String sha256, byte[] json) throws IOException;
    }
    private final File directory;
    DynamicResults(File files) { directory = new File(files, "workout-results-v2"); }
    synchronized void enqueue(WorkoutSession session) throws IOException {
        byte[] bytes = DynamicWorkoutJson.encode(session).getBytes("UTF-8");
        File file = new File(directory, session.id + ".json"); SyncFiles.recover(file);
        if (file.exists() && java.util.Arrays.equals(SyncFiles.read(file, 65536), bytes)) return;
        SyncFiles.write(file, bytes);
        // Receipts include a digest; an edited session cannot inherit an old confirmation.
    }
    boolean confirmed(WorkoutSession session) throws IOException {
        File receipt = new File(directory, session.id + ".receipt"); SyncFiles.recover(receipt);
        File queued = new File(directory, session.id + ".json"); SyncFiles.recover(queued);
        String expected = WorkoutWire.sha256(DynamicWorkoutJson.encode(session).getBytes("UTF-8"));
        return receipt.exists() && queued.exists() && TemplateJson.utf8(SyncFiles.read(receipt, 64)).equals(expected)
                && WorkoutWire.sha256(SyncFiles.read(queued, 65536)).equals(expected);
    }
    boolean send(WorkoutSession session, Receiver receiver) throws IOException {
        enqueue(session);
        if (receiver == null || !receiver.advertisesSessionV2()) return false;
        byte[] bytes = SyncFiles.read(new File(directory, session.id + ".json"), 65536);
        // Current OBJECT/result budget stays 20,000 until a future negotiated transport says otherwise.
        if (bytes.length > 20000) return false;
        String hash = WorkoutWire.sha256(bytes);
        String[] receipt = receiver.sendAndAwait(session.id, hash, bytes.clone());
        if (receipt == null || receipt.length != 2 || !session.id.equals(receipt[0]) || !hash.equals(receipt[1])) return false;
        if (!WorkoutWire.sha256(SyncFiles.read(new File(directory, session.id + ".json"), 65536)).equals(hash)) return false;
        SyncFiles.write(new File(directory, session.id + ".receipt"), hash.getBytes("UTF-8"));
        return true;
    }
}
