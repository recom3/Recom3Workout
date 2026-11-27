package com.recom3.workout;

import android.content.Context;
import android.content.SharedPreferences;

/** Android preference boundary for the pure-Java calibration store. */
final class LocalProfiles {
    static ProfileStore open(Context context) {
        final SharedPreferences preferences = context.getSharedPreferences("workout", Context.MODE_PRIVATE);
        return new ProfileStore(context.getFilesDir(), new ProfileStore.Legacy() {
            public int get(String key, int fallback) { return preferences.getInt(key, fallback); }
        });
    }
}
