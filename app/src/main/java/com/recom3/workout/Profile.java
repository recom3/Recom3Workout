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
        SETS("Total sets", 1, 1, 20),
        DIRECTION("Direction", 2, -1, 1),
        FILTER_MS("Filter", 5, 5, 1000),
        PREPARATION_MS("Quiet window", 50, 100, 1500),
        VARIANCE("Quiet variance", 1, 1, 200),
        PREPARATION_TIMEOUT("Prepare timeout", 250, 0, 10000),
        MODE("Detector mode", 1, 0, 2),
        RESET_SENSORS("Reset sensors", 1, 0, 1);

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
    boolean experimental;

    Profile(Exercise exercise) {
        this.exercise = exercise;
        values = new int[Setting.values().length];
        boolean squat = exercise.equals(Exercise.SQUAT), push = exercise.equals(Exercise.PUSH_UP);
        int[] defaults = squat ? new int[] {45,38,70,650,6500,100,12,3,-1,65,500,16,0,1,0}
                : push ? new int[] {40,16,200,550,5500,100,10,3,-1,65,500,16,0,1,0}
                : new int[] {55,20,200,700,6500,120,8,3,1,120,250,36,1000,2,1};
        System.arraycopy(defaults, 0, values, 0, values.length);
        experimental = !exercise.legacy();
    }

    Profile(Exercise exercise, Profile source) {
        this.exercise = exercise; values = source.values.clone();
        experimental = !exercise.legacy() || source.experimental;
    }
    Profile copy() { return new Profile(exercise, this); }
    boolean manual() { return get(Setting.MODE) == 0; }
    boolean linked() { return get(Setting.MODE) == 2; }
    long quietWindowNs() { return get(Setting.PREPARATION_MS) * 1000000L; }
    long preparationTimeoutNs() { return get(Setting.PREPARATION_TIMEOUT) * 1000000L; }
    String packed() {
        StringBuilder text = new StringBuilder(); text.append(experimental ? 1 : 0);
        for (int value : values) text.append(':').append(value);
        return text.toString();
    }
    static Profile unpack(Exercise exercise, String text) {
        String[] cells = text.split(":", -1);
        if (cells.length != Setting.values().length + 1 || !(cells[0].equals("0") || cells[0].equals("1")))
            throw new IllegalArgumentException("Invalid profile snapshot");
        Profile profile = new Profile(exercise);
        for (Setting setting : Setting.values()) {
            int value = Integer.parseInt(cells[setting.ordinal() + 1]);
            if (value < setting.min || value > setting.max || setting == Setting.DIRECTION && value == 0)
                throw new IllegalArgumentException("Invalid profile parameter");
            profile.values[setting.ordinal()] = value;
        }
        if (profile.get(Setting.RELEASE) >= profile.get(Setting.TRIGGER)
                || profile.get(Setting.MIN_TIME) >= profile.get(Setting.MAX_TIME))
            throw new IllegalArgumentException("Invalid profile bounds");
        profile.experimental = cells[0].equals("1") || !exercise.legacy();
        return profile;
    }

    int get(Setting setting) {
        return values[setting.ordinal()];
    }

    double acceleration(Setting setting) {
        return get(setting) / 100.0;
    }

    boolean change(Setting setting, int value) {
        if (value < setting.min || value > setting.max) return false;
        if (setting == Setting.DIRECTION && value == 0) return false;
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
            case FILTER_MS:
            case PREPARATION_MS:
            case PREPARATION_TIMEOUT:
                return get(setting) + " ms";
            case MODE: return manual() ? "Manual" : linked() ? "Linked braking" : "Settling cycle";
            case RESET_SENSORS: return get(setting) == 1 ? "Yes" : "No";
            case VARIANCE: return String.format(Locale.US, "%.2f (m/s²)²", get(setting) / 100.0);
            default:
                return Integer.toString(get(setting));
        }
    }
}
