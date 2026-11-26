package com.recom3.workout;

import java.util.Locale;

final class Profile {
    enum Setting {
        TRIGGER("Trigger", 5, 5, 500),
        RELEASE("Neutral band", 2, 2, 200),
        AMPLITUDE("Peak-to-peak", 5, 10, 1000),
        MIN_TIME("Min cycle", 50, 200, 4000),
        MAX_TIME("Max cycle", 250, 500, 15000),
        DEBOUNCE("Debounce", 20, 40, 600),
        TARGET("Target reps", 1, 1, 100),
        SETS("Total sets", 1, 1, 20);

        final String label;
        final int step;
        final int min;
        final int max;

        Setting(String label, int step, int min, int max) {
            this.label = label;
            this.step = step;
            this.min = min;
            this.max = max;
        }
    }

    final Exercise exercise;
    private final int[] values;

    Profile(Exercise exercise) {
        this.exercise = exercise;
        values = new int[Setting.values().length];
        System.arraycopy(exercise.defaults, 0, values, 0, exercise.defaults.length);
        values[Setting.SETS.ordinal()] = 3;
    }

    int get(Setting setting) {
        return values[setting.ordinal()];
    }

    double acceleration(Setting setting) {
        return get(setting) / 100.0;
    }

    boolean change(Setting setting, int value) {
        if (value < setting.min || value > setting.max) return false;
        if (setting == Setting.TRIGGER && value <= get(Setting.RELEASE)
                || setting == Setting.RELEASE && value >= get(Setting.TRIGGER)
                || setting == Setting.MIN_TIME && value >= get(Setting.MAX_TIME)
                || setting == Setting.MAX_TIME && value <= get(Setting.MIN_TIME)) return false;
        values[setting.ordinal()] = value;
        return true;
    }

    String display(Setting setting) {
        switch (setting) {
            case TRIGGER:
            case RELEASE:
            case AMPLITUDE:
                return String.format(Locale.US, "%.2f m/s²", acceleration(setting));
            case MIN_TIME:
            case MAX_TIME:
            case DEBOUNCE:
                return get(setting) + " ms";
            default:
                return Integer.toString(get(setting));
        }
    }
}
