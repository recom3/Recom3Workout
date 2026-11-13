package com.recom3.workout;

import static com.recom3.workout.Profile.Setting.*;

/** Recognizes outbound acceleration, reversal, return braking and settling. */
final class RepDetector {
    enum State { WAIT_NEUTRAL, READY, OUTBOUND, REVERSAL, RETURN, SETTLING, DEBOUNCE }

    private final Profile profile;
    private State state;
    private long started;
    private long neutralSince;
    private double high, low;

    RepDetector(Profile profile) {
        this.profile = profile;
        reset();
    }

    State state() {
        return state;
    }

    void reset() {
        state = State.WAIT_NEUTRAL;
        neutralSince = -1;
    }

    boolean sample(long ms, double signal) {
        double movement = signal * profile.exercise.direction;
        double trigger = profile.acceleration(TRIGGER);
        boolean neutral = Math.abs(movement) <= profile.acceleration(RELEASE);
        if (state == State.WAIT_NEUTRAL || state == State.DEBOUNCE) {
            if (!neutral) neutralSince = -1;
            else if (neutralSince < 0) neutralSince = ms;
            else if (ms - neutralSince >= profile.get(DEBOUNCE)) state = State.READY;
            return false;
        }
        if (state == State.READY) {
            if (movement >= trigger) {
                started = ms;
                high = movement;
                low = 0;
                state = State.OUTBOUND;
            }
            return false;
        }

        long duration = ms - started;
        if (duration > profile.get(MAX_TIME)) {
            reset();
            return false;
        }
        high = Math.max(high, movement);
        low = Math.min(low, movement);
        switch (state) {
            case OUTBOUND:
                if (movement <= -trigger) state = State.REVERSAL;
                break;
            case REVERSAL:
                if (movement >= trigger) state = State.RETURN;
                break;
            case RETURN:
                if (movement <= -trigger) state = State.REVERSAL;
                else if (neutral) {
                    state = State.SETTLING;
                    neutralSince = ms;
                }
                break;
            case SETTLING:
                if (!neutral) state = movement <= -trigger ? State.REVERSAL : State.RETURN;
                else if (ms - neutralSince >= profile.get(DEBOUNCE) / 2) {
                    if (neutralSince - started < profile.get(MIN_TIME)
                            || high - low < profile.acceleration(AMPLITUDE)) {
                        reset();
                        return false;
                    }
                    state = State.DEBOUNCE;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }
}
