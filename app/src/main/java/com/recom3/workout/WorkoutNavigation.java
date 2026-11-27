package com.recom3.workout;

/** Shared bounds for the physical-controls HUD, preview and three-row summary pages. */
final class WorkoutNavigation {
    static int wrap(int index, int count) {
        if (count < 1) throw new IllegalArgumentException("Empty exercise navigation");
        return (index % count + count) % count;
    }
    static int pages(int count) { return (count + 2) / 3; }
}
