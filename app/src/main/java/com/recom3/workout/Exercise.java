package com.recom3.workout;

enum Exercise {
    PULL_UP("Pull-ups", 1, 250, .36, .12, 55, 20, 200, 700, 6500, 120, 8),
    SQUAT("Squats", -1, 500, .16, .065, 45, 38, 70, 650, 6500, 100, 12),
    PUSH_UP("Push-ups", -1, 500, .16, .065, 40, 16, 200, 550, 5500, 100, 10);

    final String label;
    final int direction;
    final long stillnessWindowNs;
    final double stillnessVariance;
    final double filterTimeSeconds;
    final int[] defaults;

    Exercise(String label, int direction, int stillnessMs, double stillnessVariance,
             double filterTimeSeconds, int... defaults) {
        this.label = label;
        this.direction = direction;
        this.stillnessWindowNs = stillnessMs * 1000000L;
        this.stillnessVariance = stillnessVariance;
        this.filterTimeSeconds = filterTimeSeconds;
        this.defaults = defaults;
    }
}
