package com.recom3.workout;

import android.hardware.Sensor;

final class SensorStream {
    final Sensor sensor;
    boolean registered;
    float x, y, z;
    private long lastArrival, lastSample, windowStart;
    private int intervals;
    private double rate;

    SensorStream(Sensor sensor) {
        this.sensor = sensor;
    }

    void clear() {
        lastArrival = lastSample = windowStart = 0;
        rate = 0;
    }

    boolean update(long arrival, long ns, float[] values) {
        if (ns <= lastSample) return false;
        if (lastSample == 0 || ns - lastSample > 500000000L) {
            windowStart = ns;
            intervals = 0;
            rate = 0;
        } else {
            intervals++;
            long duration = ns - windowStart;
            if (duration >= 500000000L) {
                rate = intervals * 1e9 / duration;
                windowStart = ns;
                intervals = 0;
            }
        }
        lastSample = ns;
        lastArrival = arrival;
        x = values[0];
        y = values[1];
        z = values[2];
        return true;
    }

    boolean live(long now) {
        return registered && lastSample != 0 && now - lastArrival <= 500;
    }

    double rate() {
        return rate;
    }

    String status(long now) {
        if (sensor == null) return "Missing";
        if (!registered) return "Unavailable";
        return live(now) ? "Live" : "Waiting";
    }
}
