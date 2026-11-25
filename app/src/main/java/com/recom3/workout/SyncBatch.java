package com.recom3.workout;

import java.io.IOException;
import java.security.SecureRandom;

/** A single bounded pass; scheduling belongs to the Android worker, never the HUD. */
final class SyncBatch {
    interface Transport {
        boolean sendAndAwait(SyncQueue.Entry entry, int request, long timeoutMs) throws IOException, InterruptedException;
    }
    interface Observer { void state(String id, SyncQueue.State state); }
    private final SyncQueue queue;
    private final Transport transport;
    private final Observer observer;
    SyncBatch(SyncQueue queue, Transport transport, Observer observer) {
        this.queue = queue; this.transport = transport; this.observer = observer;
    }
    void run() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 30000000000L;
        int attempts = 0;
        SecureRandom random = new SecureRandom();
        for (SyncQueue.Entry entry : queue.entries()) {
            observer.state(entry.id, entry.state);
            if (entry.state == SyncQueue.State.SYNCED) continue;
            long remainingMs = (deadline - System.nanoTime()) / 1000000L;
            if (remainingMs <= 0 || attempts++ >= 20) break;
            boolean confirmed = false;
            try { confirmed = transport.sendAndAwait(entry, random.nextInt() & 0x7fffffff, Math.min(5000L, remainingMs)); }
            catch (IOException failed) { /* Keep an explicit retryable state. */ }
            SyncQueue.State state = confirmed ? SyncQueue.State.SYNCED : SyncQueue.State.FAILED;
            queue.mark(entry, state);
            observer.state(entry.id, state);
        }
    }
}
