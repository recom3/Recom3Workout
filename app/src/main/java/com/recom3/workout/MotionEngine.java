package com.recom3.workout;

import java.util.Arrays;

/** Sensor timestamps are in nanoseconds. Acceleration is in m/s² and gyro in rad/s. */
final class MotionEngine {
    private static final long GAP_NS = 200000000L;
    private static final long GYRO_FRESH_NS = 80000000L;
    private static final long WARM_UP_NS = 300000000L;
    private static final long REST_WINDOW_NS = 800000000L;
    private final Profile profile;
    private final RepDetector detector;
    private final long[] restTimes = new long[64];
    private final double[] restValues = new double[64];
    private final double[] restRotation = new double[64];
    private int restIndex, restSamples;
    private long firstAccel, lastAccel, lastGyro;
    private double gx, gy, gz, wx, wy, wz;
    private double baseline, signal;
    private boolean running;
    private boolean armed;
    private long armStarted;
    private int count;

    MotionEngine(Profile profile) {
        this.profile = profile;
        detector = new RepDetector(profile);
    }

    void setRunning(boolean running) {
        if (this.running != running) {
            detector.reset();
            armed = false;
            armStarted = lastAccel;
            if (running && profile.get(Profile.Setting.RESET_SENSORS) == 1) clearSensors();
        }
        this.running = running;
    }

    boolean running() {
        return running;
    }

    boolean waitingForStillness() {
        return running && !profile.manual() && !armed;
    }

    int count() {
        return count;
    }

    void correct(int change) {
        count = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) count + change));
        detector.reset();
    }

    void reset() {
        count = 0;
        detector.reset();
        armed = false;
        armStarted = lastAccel;
        if (profile.get(Profile.Setting.RESET_SENSORS) == 1) clearSensors();
    }

    void clearSensors() {
        lastAccel = lastGyro = 0;
        restSamples = restIndex = 0;
        armed = false;
        armStarted = 0;
        signal = 0;
        detector.reset();
    }

    double signal() {
        return signal;
    }

    String state() {
        if (!running) return "PAUSED";
        if (profile.manual()) return "MANUAL";
        if (!warmedUp()) return "WARMING";
        if (!armed) return profile.get(Profile.Setting.RESET_SENSORS) == 1 ? "PREPARING" : "HOLD_STILL";
        return detector.state().name();
    }

    private boolean warmedUp() {
        return lastAccel != 0 && lastAccel - firstAccel >= WARM_UP_NS;
    }

    void gyro(long ns, float x, float y, float z) {
        if (ns <= lastGyro) return;
        lastGyro = ns;
        wx = x;
        wy = y;
        wz = z;
    }

    boolean accel(long ns, float x, float y, float z) {
        if (profile.manual()) return false;
        long delta = ns - lastAccel;
        if (delta <= 0) return false;
        double length = Math.sqrt(x * (double) x + y * (double) y + z * (double) z);
        if (lastAccel == 0 || delta > GAP_NS) {
            // Seed gravity during a quiet interval after startup or a sampling gap.
            if (length < 1) {
                clearSensors();
                return false;
            }
            gx = x / length;
            gy = y / length;
            gz = z / length;
            baseline = length;
            restSamples = restIndex = 0;
            signal = 0;
            firstAccel = lastAccel = ns;
            armed = false;
            armStarted = ns;
            detector.reset();
            return false;
        }
        lastAccel = ns;
        double dt = delta / 1e9;
        boolean gyroFresh = lastGyro != 0 && Math.abs(ns - lastGyro) <= GYRO_FRESH_NS;
        if (gyroFresh) {
            // Gravity is fixed in world space. It rotates opposite the goggles in sensor space.
            double dx = (gy * wz - gz * wy) * dt;
            double dy = (gz * wx - gx * wz) * dt;
            double dz = (gx * wy - gy * wx) * dt;
            gx += dx;
            gy += dy;
            gz += dz;
        }
        if (length > 1 && Math.abs(length - baseline) < 1.5) {
            double tau = gyroFresh ? 1.0 : 0.32;
            double alpha = dt / (tau + dt);
            gx += alpha * (x / length - gx);
            gy += alpha * (y / length - gy);
            gz += alpha * (z / length - gz);
        }
        double norm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        gx /= norm;
        gy /= norm;
        gz /= norm;

        // Jet firmware can report a resting magnitude different from standard gravity.
        double projected = x * gx + y * gy + z * gz;
        if (!warmedUp()) baseline += dt / (0.15 + dt) * (projected - baseline);
        updateRestBaseline(ns, projected, gyroFresh ? wx * wx + wy * wy + wz * wz : 0, dt);
        double vertical = projected - baseline;
        signal += dt / ((profile.get(Profile.Setting.FILTER_MS) / 1000.0) + dt) * (vertical - signal);
        if (!running || !warmedUp()) return false;
        if (!armed) {
            // Establish the workout posture before accepting an outbound phase.
            double quiet = quietMean(ns, profile.quietWindowNs(), (profile.get(Profile.Setting.VARIANCE) / 100.0));
            if (Double.isNaN(quiet) && profile.preparationTimeoutNs() > 0
                    && ns - armStarted >= profile.preparationTimeoutNs()) {
                quiet = medianReference(ns);
            }
            if (ns - armStarted >= profile.quietWindowNs()
                    && !Double.isNaN(quiet)) {
                baseline = quiet;
                signal = 0;
                detector.arm();
                armed = true;
            }
            return false;
        }
        if (!detector.sample(ns / 1000000L, signal)) return false;
        count++;
        return true;
    }

    private double medianReference(long ns) {
        double[] samples = new double[restSamples];
        int count = 0;
        for (int i = 0; i < restSamples; i++) {
            if (ns - restTimes[i] <= REST_WINDOW_NS) samples[count++] = restValues[i];
        }
        if (count == 0) return Double.NaN;
        Arrays.sort(samples, 0, count);
        return (samples[(count - 1) / 2] + samples[count / 2]) / 2;
    }

    private void updateRestBaseline(long ns, double projected, double rotation, double dt) {
        restTimes[restIndex] = ns;
        restValues[restIndex] = projected;
        restRotation[restIndex] = rotation;
        restIndex = (restIndex + 1) % restTimes.length;
        restSamples = Math.min(restSamples + 1, restTimes.length);

        RepDetector.State state = detector.state();
        if (state != RepDetector.State.WAIT_NEUTRAL && state != RepDetector.State.READY
                && state != RepDetector.State.DEBOUNCE) return;

        double mean = quietMean(ns, REST_WINDOW_NS, 0.04);
        if (Double.isNaN(mean)) return;

        // Relearn the measured resting magnitude after a noisy startup or a posture change.
        // Keep the filter in the same reference frame without creating an acceleration pulse.
        double correction = dt / (0.30 + dt) * (mean - baseline);
        baseline += correction;
        signal -= correction;
    }

    private double quietMean(long ns, long windowNs, double maximumVariance) {
        double total = 0, squared = 0, rotationTotal = 0;
        long oldest = ns;
        int samples = 0;
        for (int i = 0; i < restSamples; i++) {
            if (ns - restTimes[i] > windowNs) continue;
            double value = restValues[i];
            total += value;
            squared += value * value;
            rotationTotal += restRotation[i];
            oldest = Math.min(oldest, restTimes[i]);
            samples++;
        }
        if (ns - oldest < windowNs - 50000000L) return Double.NaN;
        double mean = total / samples;
        double variance = Math.max(0, squared / samples - mean * mean);
        if (variance > maximumVariance || rotationTotal / samples > 0.04) return Double.NaN;
        return mean;
    }
}
