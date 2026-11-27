package com.recom3.workout;

import java.io.IOException;

/** Serialized receipt handler plus an explicit, short-lived local owner/context handoff. */
final class TemplateService {
    private final TemplateStore store;
    private final ProfileStore profiles;
    private long armedAt = -1, generation;
    private volatile WorkoutTemplate latest;
    TemplateService(TemplateStore store) { this(store, null); }
    TemplateService(TemplateStore store, ProfileStore profiles) { this.store = store; this.profiles = profiles; }
    synchronized void allowReplacement(long nowMs) { armedAt = nowMs; generation++; }
    WorkoutTemplate latest() { return latest; }

    WorkoutWire receive(WorkoutWire frame, long nowMs) throws IOException {
        boolean dynamic = WorkoutWire.ROUTINE_V2.equals(frame.action);
        if (frame.request <= 0 || !"com.recom3.snow3".equals(frame.sender)
                || !(WorkoutWire.TEMPLATE.equals(frame.action) || dynamic) || !frame.info.matches("[a-f0-9]{64}")
                || frame.data.length > WorkoutTemplate.MAX_WIRE || !frame.info.equals(WorkoutWire.sha256(frame.data)))
            throw TemplateJson.invalid();
        WorkoutTemplate template = dynamic ? DynamicRoutine.parse(frame.data) : WorkoutTemplate.parse(frame.data);
        if (dynamic) {
            if (profiles == null) throw new IOException("Calibration storage unavailable");
            for (Exercise exercise : template.catalog.exercises()) {
                profiles.remember(exercise);
                profiles.load(exercise);
            }
            // Reopen every calibration before acknowledging a routine which depends on it.
            for (Exercise exercise : template.catalog.exercises()) profiles.load(exercise);
        }
        boolean replace; long token;
        synchronized (this) {
            replace = armedAt >= 0 && nowMs >= armedAt && nowMs - armedAt < 120000;
            token = generation;
        }
        WorkoutTemplate stored = store.accept(template, replace);
        // A retry of an identical record also receives a read-verified receipt, without rewriting.
        if (!stored.hash.equals(template.hash)) throw new IOException("template_readback_failed");
        latest = stored;
        synchronized (this) { if (replace && token == generation) armedAt = -1; }
        return new WorkoutWire(frame.request, "com.recom3.workout", dynamic ? WorkoutWire.ROUTINE_V2_STORED : WorkoutWire.TEMPLATE_STORED,
                frame.info, stored.receipt());
    }
}
