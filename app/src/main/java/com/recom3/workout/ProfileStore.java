package com.recom3.workout;

import java.io.*;

/** Per-ID calibration; atomic first-use migration never edits the old preferences. */
final class ProfileStore {
    interface Legacy { int get(String key, int fallback); }
    private final File directory;
    private final Legacy legacy;
    private final DefinitionStore definitions;
    ProfileStore(File files, Legacy legacy) {
        directory = new File(files, "exercise-profiles"); this.legacy = legacy;
        definitions = new DefinitionStore(files);
    }
    void remember(Exercise exercise) throws IOException { definitions.remember(exercise); }
    Profile load(Exercise exercise) throws IOException {
        synchronized (ProfileStore.class) {
            File file = file(exercise); SyncFiles.recover(file);
            if (file.exists()) {
                try { return Profile.unpack(exercise, TemplateJson.utf8(SyncFiles.read(file, 1024))); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved calibration", invalid); }
            }
            Profile profile;
            if (!exercise.legacy()) profile = new Profile(exercise, load(Exercise.PULL_UP));
            else {
                profile = new Profile(exercise);
                if (legacy != null) for (int pass = 0; pass < 2; pass++) for (Profile.Setting setting : Profile.Setting.values())
                    profile.change(setting, legacy.get(exercise.name() + "." + setting.name(), profile.get(setting)));
            }
            save(profile);
            return profile.copy();
        }
    }
    void save(Profile profile) throws IOException {
        synchronized (ProfileStore.class) {
            String packed = profile.packed(); Profile.unpack(profile.exercise, packed);
            SyncFiles.write(file(profile.exercise), packed.getBytes("UTF-8"));
        }
    }
    private File file(Exercise exercise) { return new File(directory, exercise.id + ".profile"); }
}
