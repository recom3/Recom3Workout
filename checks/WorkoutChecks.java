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
            direction = new Profile(exercise).get(DIRECTION);
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
        check(phases.engine.count() == 2, "complete consecutive cycles count without a forced rest");
        phases.rep(300, 1.2);
        check(phases.engine.count() == 3, "next full cycle after debounce");

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

        Trace startup = new Trace(Exercise.SQUAT, .5);
        startup.engine.clearSensors();
        startup.segment(-.9, 300);
        startup.segment(0, 2500);
        check(startup.engine.state().equals("READY") && Math.abs(startup.engine.signal()) < .1
                && startup.engine.count() == 0, "quiet interval recovers a biased startup baseline");
        startup.rep(300, 1.2);
        check(startup.engine.count() == 1, "full cycle counts after baseline recovery");
        startup.engine.clearSensors();
        startup.gyro = false;
        startup.segment(-.7, 300);
        startup.segment(0, 2500);
        check(startup.engine.state().equals("READY") && Math.abs(startup.engine.signal()) < .1
                && startup.engine.count() == 1, "baseline recovery also works without a gyro");
        startup.segment(0, 10000);
        check(startup.engine.count() == 1, "resting baseline adaptation creates no repetitions");

        Trace positioning = new Trace(Exercise.PUSH_UP, .3);
        positioning.engine.reset();
        for (int i = 0; i < 40; i++) positioning.sample(0, .6);
        check(positioning.engine.state().equals("HOLD_STILL") && positioning.engine.count() == 0,
                "head rotation while positioning keeps the detector unarmed");
        positioning.segment(0, 1200);
        check(positioning.engine.state().equals("READY"), "quiet workout posture arms detection");
        positioning.rep(300, .6);
        check(positioning.engine.count() == 0, "push-up profile rejects small complete motion cycles");
        positioning.rep(300, 1.4);
        check(positioning.engine.count() == 1, "push-up profile accepts a full-amplitude cycle after arming");

        Profile settlingProfile = new Profile(Exercise.SQUAT);
        settlingProfile.change(RELEASE, 18);
        RepDetector settling = new RepDetector(settlingProfile);
        int settledCount = 0;
        for (int ms = 0; ms <= 2600; ms += 25) {
            int phase = (ms - 200) % 1200;
            double movement;
            if (ms < 200) movement = 0;
            else if (phase < 300) movement = 1;
            else if (phase < 800) movement = -1;
            else if (phase < 1100) movement = 1;
            else if (phase == 1100) movement = .1;
            else movement = .30;
            if (settling.sample(ms, -movement)) settledCount++;
        }
        check(settledCount == 2, "neutral-band chatter does not cancel completed returns");

        RepDetector continuous = new RepDetector(settlingProfile);
        int continuousCount = 0;
        for (int ms = 0; ms <= 2500; ms += 25) {
            int phase = (ms - 200) % 1125;
            double movement;
            if (ms < 200) movement = 0;
            else if (phase < 300) movement = 1;
            else if (phase < 800) movement = -1;
            else if (phase < 1100) movement = 1;
            else movement = .1;
            if (continuous.sample(ms, -movement)) continuousCount++;
        }
        check(continuousCount == 2, "brief neutral crossing closes each continuous movement cycle");

        Profile cooldownProfile = new Profile(Exercise.SQUAT);
        cooldownProfile.change(MIN_TIME, 200);
        cooldownProfile.change(DEBOUNCE, 600);
        RepDetector cooldown = new RepDetector(cooldownProfile);
        cooldown.arm();
        int cooldownCount = 0;
        for (int ms = 0; ms < 1000; ms += 25) {
            int phase = ms % 300;
            double movement;
            if (phase < 75) movement = 1;
            else if (phase < 175) movement = -1;
            else if (phase < 250) movement = 1;
            else movement = .1;
            if (cooldown.sample(ms, -movement)) cooldownCount++;
        }
        check(cooldownCount == 2, "configured debounce still limits repeated complete cycles");

        Trace hanging = new Trace(Exercise.PULL_UP, .3);
        hanging.engine.clearSensors();
        for (int i = 0; i < 40; i++) hanging.sample(i % 2 == 0 ? .5 : -.5, .12);
        check(hanging.engine.state().equals("READY") && hanging.engine.count() == 0,
                "pull-up arming tolerates bar vibration without counting it");
        hanging.rep(300, 1.4);
        check(hanging.engine.count() == 1, "pull-up cycle counts after preparing while hanging");
        hanging.rep(300, .6);
        check(hanging.engine.count() == 1, "pull-up amplitude rejects a small complete motion cycle");

        RepDetector linkedPulls = new RepDetector(new Profile(Exercise.PULL_UP));
        linkedPulls.arm();
        int linkedCount = 0;
        for (int ms = 0; ms < 3300; ms += 25) {
            double signal = ms % 1600 < 800 ? 1.5 : -1.5;
            if (linkedPulls.sample(ms, signal)) linkedCount++;
        }
        check(linkedCount == 2, "pull-up braking can flow into the next pull without a neutral pause");

        Trace partialPull = new Trace(Exercise.PULL_UP, .3);
        partialPull.segment(1.4, 300);
        partialPull.segment(-1.4, 600);
        partialPull.segment(0, 1200);
        check(partialPull.engine.count() == 0, "pull-up without return braking stays incomplete");

        RepDetector rapidPulls = new RepDetector(new Profile(Exercise.PULL_UP));
        rapidPulls.arm();
        int rapidCount = 0;
        for (int ms = 0; ms < 2000; ms += 25) {
            double signal = ms % 400 < 200 ? 1.5 : -1.5;
            if (rapidPulls.sample(ms, signal)) rapidCount++;
        }
        check(rapidCount == 0, "rapid alternating motion does not satisfy pull-up cycle duration");

        Trace preparingPull = new Trace(Exercise.PULL_UP, .3);
        preparingPull.engine.reset();
        for (int i = 0; i < 30; i++) preparingPull.sample(i % 2 == 0 ? .5 : -.5, .25);
        check(preparingPull.engine.state().equals("PREPARING"),
                "pull-up preparation waits briefly while hanging is unsteady");
        for (int i = 0; i < 15; i++) preparingPull.sample(i % 2 == 0 ? .5 : -.5, .25);
        check(!preparingPull.engine.waitingForStillness() && preparingPull.engine.count() == 0,
                "pull-up preparation finishes after one second despite head motion");
        preparingPull.rep(300, 1.4);
        check(preparingPull.engine.count() == 1, "pull-up counts after timed median-reference preparation");
        System.out.println(passed + " checks passed");
    }
}
