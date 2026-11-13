package com.recom3.workout;

import static com.recom3.workout.Profile.Setting.*;

public final class WorkoutChecks {
    private static int passed;

    private static void check(boolean result, String description) {
        if (!result) throw new AssertionError(description);
        passed++;
        System.out.println("PASS " + description);
    }

    private static final class Trace {
        final MotionEngine engine;
        final int direction;
        long time = 1000;
        double tilt;
        boolean gyro = true;

        Trace(Exercise exercise, double tilt) {
            engine = new MotionEngine(new Profile(exercise));
            engine.setRunning(true);
            direction = exercise.direction;
            this.tilt = tilt;
            segment(0, 800);
        }

        void sample(double vertical, double rotation) {
            time += 25;
            tilt -= rotation * .025;
            long ns = time * 1000000;
            if (gyro) engine.gyro(ns, 0, (float) rotation, 0);
            double total = 8.86 + vertical * direction;
            engine.accel(ns, (float) (total * Math.sin(tilt)), 0, (float) (total * Math.cos(tilt)));
        }

        void segment(double vertical, long ms) {
            for (int sample = 0; sample < ms / 25; sample++) sample(vertical, 0);
        }

        void rep(int lobe, double amplitude) {
            segment(amplitude, lobe);
            segment(-amplitude, lobe * 2);
            segment(amplitude, lobe);
            segment(0, 500);
        }
    }

    public static void main(String[] args) {
        for (Exercise exercise : Exercise.values()) {
            Trace trace = new Trace(exercise, .65);
            trace.rep(300, 1.2);
            check(trace.engine.count() == 1, exercise + " full cycle on tilted goggles");
        }

        Trace phases = new Trace(Exercise.SQUAT, 0);
        check(phases.engine.state().equals("READY"), "stillness arms detector");
        phases.segment(1.2, 300);
        check(phases.engine.state().equals("OUTBOUND"), "outbound state");
        phases.segment(-1.2, 600);
        check(phases.engine.state().equals("REVERSAL") && phases.engine.count() == 0, "reversal is incomplete");
        phases.segment(1.2, 300);
        check(phases.engine.state().equals("RETURN") && phases.engine.count() == 0, "return requires settling");
        for (int i = 0; i < 12 && phases.engine.count() == 0; i++) phases.segment(0, 25);
        check(phases.engine.state().equals("DEBOUNCE") && phases.engine.count() == 1, "settling counts once");
        phases.segment(1.2, 300);
        phases.segment(-1.2, 600);
        phases.segment(1.2, 300);
        phases.segment(0, 500);
        check(phases.engine.count() == 1, "debounce rejects motion without rearming");
        phases.rep(300, 1.2);
        check(phases.engine.count() == 2, "next full cycle after debounce");

        Trace incomplete = new Trace(Exercise.PUSH_UP, 0);
        incomplete.segment(1.2, 300);
        incomplete.segment(-1.2, 400);
        incomplete.segment(0, 6000);
        check(incomplete.engine.count() == 0 && incomplete.engine.state().equals("READY"),
                "incomplete cycle times out and rearms");
        incomplete.rep(300, .20);
        check(incomplete.engine.count() == 0, "small movements are rejected");
        for (int i = 0; i < 400; i++) incomplete.sample(i % 2 == 0 ? .65 : -.65, 0);
        check(incomplete.engine.count() == 0, "alternating sensor noise is rejected");
        for (int i = 0; i < 28; i++) incomplete.sample(0, 1.5);
        for (int i = 0; i < 28; i++) incomplete.sample(0, -1.5);
        check(incomplete.engine.count() == 0 && Math.abs(incomplete.engine.signal()) < .1,
                "gyro compensation rejects a head tilt without translation");

        Trace slow = new Trace(Exercise.SQUAT, 0);
        slow.rep(1250, 1.1);
        check(slow.engine.count() == 1, "slow in-range cycle");
        slow.rep(1750, 1.1);
        check(slow.engine.count() == 1, "too slow cycle rejected");
        Trace fast = new Trace(Exercise.PUSH_UP, .7);
        fast.rep(150, 1.6);
        check(fast.engine.count() == 1, "fast in-range cycle");
        fast.rep(75, 1.8);
        check(fast.engine.count() == 1, "too fast cycle rejected");

        Trace lifecycle = new Trace(Exercise.SQUAT, .8);
        lifecycle.gyro = false;
        lifecycle.segment(0, 200);
        lifecycle.rep(300, 1.2);
        check(lifecycle.engine.count() == 1, "accelerometer-only fallback");
        lifecycle.segment(1.2, 300);
        lifecycle.engine.setRunning(false);
        lifecycle.rep(300, 1.2);
        check(lifecycle.engine.count() == 1 && lifecycle.engine.state().equals("PAUSED"), "pause preserves count");
        lifecycle.engine.setRunning(true);
        lifecycle.segment(-1.2, 300);
        lifecycle.segment(1.2, 300);
        lifecycle.segment(0, 500);
        check(lifecycle.engine.count() == 1, "resume discards partial cycle");
        lifecycle.rep(300, 1.2);
        check(lifecycle.engine.count() == 2, "resume counts new full cycle");
        lifecycle.engine.correct(-3);
        check(lifecycle.engine.count() == 0, "manual correction clamps at zero");
        lifecycle.engine.correct(1);
        lifecycle.segment(1.2, 300);
        lifecycle.engine.reset();
        lifecycle.segment(-1.2, 600);
        lifecycle.segment(1.2, 300);
        lifecycle.segment(0, 500);
        check(lifecycle.engine.count() == 0, "reset clears count and any partial cycle");
        lifecycle.segment(1.2, 300);
        lifecycle.time += 300;
        lifecycle.segment(-1.2, 600);
        lifecycle.segment(1.2, 300);
        lifecycle.segment(0, 500);
        check(lifecycle.engine.count() == 0, "sampling gap discards partial cycle");

        Profile custom = new Profile(Exercise.SQUAT);
        check(custom.change(AMPLITUDE, 300) && !custom.change(RELEASE, custom.get(TRIGGER))
                && !custom.change(MAX_TIME, custom.get(MIN_TIME)), "custom thresholds and range validation");
        RepDetector detector = new RepDetector(custom);
        boolean counted = false;
        for (int ms = 0; ms <= 1800; ms += 25) {
            double signal = ms < 200 ? 0 : ms < 500 ? -1 : ms < 1100 ? 1 : ms < 1400 ? -1 : 0;
            counted |= detector.sample(ms, signal);
        }
        check(!counted, "configurable amplitude rejects an otherwise complete cycle");
        System.out.println(passed + " checks passed");
    }
}
