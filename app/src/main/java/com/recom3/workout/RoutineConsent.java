package com.recom3.workout;

import java.io.IOException;

/** Stored/ACKed is distinct from the user's approval to use those targets. */
final class RoutineConsent {
    static boolean needsReview(WorkoutTemplate template, String approvedHash) {
        return template != null && !template.hash.equals(approvedHash);
    }
    static WorkoutTemplate approved(WorkoutTemplate template, String approvedHash) throws IOException {
        if (needsReview(template, approvedHash)) throw new IOException("routine_review_required");
        return template;
    }
}
