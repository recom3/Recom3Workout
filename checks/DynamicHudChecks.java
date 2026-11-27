package com.recom3.workout;

import java.io.*;
import java.lang.reflect.Field;
import android.content.*;

/** Runs the actual HUD with host-only drawing boundaries; no claim about physical readability. */
public final class DynamicHudChecks {
    private static int passed;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); passed++; System.out.println("PASS " + label); }
    private static Object field(WorkoutHud hud, String name) throws Exception { Field field = WorkoutHud.class.getDeclaredField(name); field.setAccessible(true); return field.get(hud); }
    public static void main(String[] args) throws Exception {
        final File files = new File(args[0], "hud-" + java.util.UUID.randomUUID()); files.mkdirs();
        Context context = new Context() {
            public File getFilesDir() { return files; }
            public void sendBroadcast(Intent intent) {}
        };
        WorkoutTemplate template = DynamicRoutine.parse(DynamicChecks.bundle(8).getBytes("UTF-8"));
        WorkoutSession session = DynamicChecks.create(template, new ProfileStore(files, null));
        WorkoutHud hud = new WorkoutHud(context);
        for (int i = 0; i < 8; i++) {
            hud.routinePreview(template, i, 1, "");
            String[] rows = (String[])field(hud, "lines");
            check(field(hud, "badge").equals((i + 1) + "/8") && rows[0].contains(template.exercises()[i].label)
                    && rows[1].contains("10 reps") && rows[2].contains("8 reps"), "actual HUD preview renders dynamic exercise " + (i + 1) + " of eight");
        }
        for (int page = 0; page < 3; page++) {
            hud.summary(session, true, false, false, true, page);
            String[] rows = (String[])field(hud, "lines");
            check(rows[1].contains(session.exercises()[page * 3].label) && rows[4].contains("Saved locally")
                    && rows[5].contains("CSV saved"), "actual summary page " + (page + 1) + " preserves status rows without array overflow");
            hud.onDraw(new android.graphics.Canvas());
        }
        Profile profile = session.profile(session.exercise("dips"));
        hud.calibration(profile, Profile.Setting.MODE, "");
        check(((String[])field(hud,"lines"))[0].contains("UNVALIDATED"), "dynamic calibration visibly identifies the unvalidated cloned profile");
        hud.workout(profile, 4, 1, false, false, false, "missing", "missing", "", false, true);
        check(field(hud,"count").equals("4") && ((String[])field(hud,"lines"))[0].contains("EXP"), "dynamic HUD keeps actual manual corrections and experimental identity visible");
        System.out.println(passed + " dynamic HUD checks passed (host drawing boundaries)");
    }
}
