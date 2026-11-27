package com.recom3.workout;

/** Immutable data identity. Constants below adapt historical data; they are not a runtime registry. */
final class Exercise {
    static final Exercise PULL_UP = new Exercise("pull-ups", "Pull-ups", "", 1);
    static final Exercise SQUAT = new Exercise("squats", "Squats", "", 1);
    static final Exercise PUSH_UP = new Exercise("push-ups", "Push-ups", "", 1);
    final String id, label, description;
    final String measurement = "repetitions";
    final int definitionVersion;
    Exercise(String id, String label, String description, int version) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}") || label == null
                || label.trim().length() == 0 || label.length() > 32 || description == null
                || description.length() > 160 || version < 1 || !plain(label) || !plain(description))
            throw new IllegalArgumentException("Invalid exercise definition");
        this.id = id; this.label = label; this.description = description; definitionVersion = version;
    }
    private static boolean plain(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    boolean legacy() { return id.equals("pull-ups") || id.equals("squats") || id.equals("push-ups"); }
    static Exercise[] values() { return new Exercise[] {PULL_UP, SQUAT, PUSH_UP}; }
    String name() { return id.equals("pull-ups") ? "PULL_UP" : id.equals("squats") ? "SQUAT"
            : id.equals("push-ups") ? "PUSH_UP" : id; }
    static Exercise valueOf(String name) {
        for (Exercise exercise : values()) if (exercise.name().equals(name)) return exercise;
        throw new IllegalArgumentException("Unknown historical exercise");
    }
    int ordinal() {
        Exercise[] legacy = values();
        for (int i = 0; i < legacy.length; i++) if (equals(legacy[i])) return i;
        throw new IllegalArgumentException("Dynamic exercises have no ordinal");
    }
    @Override public boolean equals(Object value) { return value instanceof Exercise && id.equals(((Exercise) value).id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return id; }
}
