package com.recom3.workout;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CsvRecording implements Closeable {
    final File file;
    private final BufferedWriter writer;
    private final long started;
    private final StringBuilder row = new StringBuilder(160);
    private long lastFlush;
    private int markers;

    CsvRecording(File directory, Profile profile, long now) throws IOException {
        if (directory == null) throw new IOException("External storage is unavailable");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export directory");
        String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        file = File.createTempFile(profile.exercise.name() + "-" + date + "-", ".csv", directory);
        started = lastFlush = now;
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
        try {
            writer.write("event,elapsed_ms,sensor_ns,x,y,z,vertical_m_s2,state,detected,marker,exercise");
            for (Profile.Setting setting : Profile.Setting.values()) {
                writer.write("," + setting.name().toLowerCase(Locale.US));
            }
            writer.newLine();
            row.append("CONFIG,0,,,,,,,,,").append(profile.exercise.name());
            for (Profile.Setting setting : Profile.Setting.values()) {
                row.append(',');
                if (setting.ordinal() <= Profile.Setting.AMPLITUDE.ordinal()) {
                    row.append(profile.acceleration(setting));
                } else {
                    row.append(profile.get(setting));
                }
            }
            writer.write(row.toString());
            writer.newLine();
        } catch (IOException failure) {
            try { writer.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }

    void sensor(String type, long now, long ns, SensorStream stream, MotionEngine engine) throws IOException {
        row.setLength(0);
        row.append(type).append(',').append(now - started).append(',').append(ns).append(',')
                .append(stream.x).append(',').append(stream.y).append(',').append(stream.z);
        write(now, engine, "");
    }

    void mark(long now, MotionEngine engine) throws IOException {
        row.setLength(0);
        row.append("MARK,").append(now - started).append(",,,,");
        write(now, engine, Integer.toString(++markers));
    }

    int markers() {
        return markers;
    }

    private void write(long now, MotionEngine engine, String marker) throws IOException {
        row.append(',').append(engine.signal()).append(',').append(engine.state()).append(',')
                .append(engine.count()).append(',').append(marker);
        for (int i = 0; i <= Profile.Setting.values().length; i++) row.append(',');
        writer.write(row.toString());
        writer.newLine();
        if (now - lastFlush >= 1000) {
            writer.flush();
            lastFlush = now;
        }
    }

    @Override public void close() throws IOException {
        writer.close();
    }
}
