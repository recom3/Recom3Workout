package com.recom3.workout;

enum Exercise {
    PULL_UP("Pull-ups", 1, 55, 20, 85, 700, 6500, 120, 8),
    SQUAT("Squats", -1, 45, 18, 70, 650, 6500, 100, 12),
    PUSH_UP("Push-ups", -1, 40, 16, 65, 550, 5500, 100, 10);

    final String label;
    final int direction;
    final int[] defaults;

    Exercise(String label, int direction, int... defaults) {
        this.label = label;
        this.direction = direction;
        this.defaults = defaults;
    }
}
