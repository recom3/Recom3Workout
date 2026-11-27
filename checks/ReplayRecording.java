package com.recom3.workout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Replays raw sensor rows. Manual MARK rows do not change the expected count. */
public final class ReplayRecording {
    public static void main(String[] args) throws Exception {
        int expected = Integer.parseInt(args[1]);
        long quietAfterMs = Long.parseLong(args[2]);
        BufferedReader reader = new BufferedReader(new FileReader(args[0]));
        int quietDetections = 0;
        int originalCount = 0;
        MotionEngine engine;
        try {
            reader.readLine();
            String[] config = reader.readLine().split(",", -1);
            Exercise exercise;
            try { exercise = Exercise.valueOf(config[10]); }
            catch (IllegalArgumentException unknown) { exercise = new Exercise(config[10], config[10], "", 1); }
            Profile profile = new Profile(exercise);
            for (int pass = 0; pass < 2; pass++) {
                for (Profile.Setting setting : Profile.Setting.values()) {
                    if (11 + setting.ordinal() >= config.length) continue;
                    double recorded = Double.parseDouble(config[11 + setting.ordinal()]);
                    int value = setting.ordinal() <= Profile.Setting.AMPLITUDE.ordinal()
                            ? (int) Math.round(recorded * 100) : (int) recorded;
                    if (!profile.change(setting, value) && pass == 1) {
                        throw new IllegalArgumentException("Invalid recorded setting " + setting);
                    }
                }
            }
            if (args.length > 3) {
                double amplitude = Double.parseDouble(args[3]);
                if (amplitude >= 0 && !profile.change(Profile.Setting.AMPLITUDE,
                        (int) Math.round(amplitude * 100))) {
                    throw new IllegalArgumentException("Invalid amplitude override");
                }
            }
            if (args.length > 4 && !profile.change(Profile.Setting.TRIGGER,
                    (int) Math.round(Double.parseDouble(args[4]) * 100))) {
                throw new IllegalArgumentException("Invalid trigger override");
            }
            engine = new MotionEngine(profile);
            engine.setRunning(true);
            String row;
            while ((row = reader.readLine()) != null) {
                String[] cells = row.split(",", -1);
                if (!cells[0].equals("ACCEL") && !cells[0].equals("GYRO")) continue;
                long timestamp = Long.parseLong(cells[2]);
                float x = Float.parseFloat(cells[3]);
                float y = Float.parseFloat(cells[4]);
                float z = Float.parseFloat(cells[5]);
                if (cells[0].equals("GYRO")) {
                    engine.gyro(timestamp, x, y, z);
                } else {
                    boolean detected = engine.accel(timestamp, x, y, z);
                    long elapsed = Long.parseLong(cells[1]);
                    if (detected) {
                        System.out.println("Rep " + engine.count() + " at " + elapsed + " ms");
                        if (quietAfterMs >= 0 && elapsed >= quietAfterMs) quietDetections++;
                    }
                    originalCount = Integer.parseInt(cells[8]);
                }
            }
        } finally {
            reader.close();
        }
        if (engine.count() != expected || quietDetections != 0) {
            throw new AssertionError("Expected " + expected + " reps with zero quiet detections, got "
                    + engine.count() + " and " + quietDetections);
        }
        System.out.println("PASS " + new File(args[0]).getName() + ": recorded count " + originalCount
                + ", raw replay " + engine.count() + ", quiet detections " + quietDetections);
    }
}
