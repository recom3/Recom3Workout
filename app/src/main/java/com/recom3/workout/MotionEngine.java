package com.recom3.workout;

/** Sensor timestamps are in nanoseconds. Acceleration is in m/s² and gyro in rad/s. */
final class MotionEngine {
    private static final long GAP_NS = 200000000L;
    private static final long GYRO_FRESH_NS = 80000000L;
    private static final long WARM_UP_NS = 300000000L;
    private final RepDetector detector;
    private long firstAccel, lastAccel, lastGyro;
    private double gx, gy, gz, wx, wy, wz;
    private double baseline, signal;
    private boolean running;
    private int count;

    MotionEngine(Profile profile) {
        detector = new RepDetector(profile);
    }

    void setRunning(boolean running) {
        if (this.running != running) detector.reset();
        this.running = running;
    }

    boolean running() {
        return running;
    }

    int count() {
        return count;
    }

    void correct(int change) {
        count = Math.max(0, count + change);
        detector.reset();
    }

    void reset() {
        count = 0;
        detector.reset();
    }

    void clearSensors() {
        lastAccel = lastGyro = 0;
        signal = 0;
        detector.reset();
    }

    double signal() {
        return signal;
    }

    String state() {
        if (!running) return "PAUSED";
        if (!warmedUp()) return "WARMING";
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
            signal = 0;
            firstAccel = lastAccel = ns;
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
        double vertical = projected - baseline;
        signal += dt / (0.065 + dt) * (vertical - signal);
        if (!running || !warmedUp() || !detector.sample(ns / 1000000L, signal)) return false;
        count++;
        return true;
    }
}
