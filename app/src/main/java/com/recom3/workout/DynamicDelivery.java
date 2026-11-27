package com.recom3.workout;

import java.io.IOException;

/** One original-byte attempt. Android owns scheduling; the existing outbox owns durable receipts. */
final class DynamicDelivery {
    enum State {
        LOCAL("Saved locally"), PENDING("Saved locally · pending"), SENDING("Sending to ICE3"),
        STORED("Stored in ICE3 · phone local"), INCOMPATIBLE("Receiver incompatible / no v2 reply"),
        TIMEOUT("ICE3 receipt timeout"), ERROR("ICE3 error · saved locally"),
        OVERSIZED("Too large · saved locally");
        final String label;
        State(String label) { this.label = label; }
    }
    static final class Attempt {
        private volatile boolean valid = true;
        volatile String payloadHash; // Assigned only after the latest snapshot is durable; not reconstructed for retries.
        synchronized void cancel() { valid = false; notifyAll(); }
        boolean valid() { return valid; }
    }
    interface Transport {
        State deliver(DynamicResults.Entry entry, Attempt attempt, long timeoutMs) throws IOException, InterruptedException;
    }
    static State send(DynamicResults queue, DynamicResults.Entry entry, Transport transport,
                      Attempt attempt, long timeoutMs) throws IOException, InterruptedException {
        if (!attempt.valid() || !queue.current(entry)) return State.PENDING;
        if (queue.confirmed(entry)) return State.STORED;
        if (entry.size() > 20000) return State.OVERSIZED;
        State result = transport.deliver(entry, attempt, timeoutMs);
        synchronized (attempt) {
            if (!attempt.valid() || !queue.current(entry)) return State.PENDING;
            if (result == State.STORED && !queue.confirm(entry)) return State.PENDING;
            return result;
        }
    }
}
