package com.recom3.workout;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.reconinstruments.ui.FontUtils;

import java.util.Locale;

import static com.recom3.workout.Profile.Setting.*;

final class WorkoutHud extends View {
    private static final int BACKGROUND = Color.rgb(8, 29, 39);
    private static final int PANEL = Color.rgb(15, 45, 58);
    private static final int WHITE = Color.rgb(241, 249, 252);
    private static final int MUTED = Color.rgb(160, 193, 205);
    private static final int GREEN = Color.rgb(76, 227, 155);
    private static final int AMBER = Color.rgb(255, 191, 112);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF(12, 42, 416, 211);
    private final RectF selection = new RectF();
    private final Typeface regular, bold;
    private String title, badge, footer;
    private int badgeColor = MUTED;
    private final String[] lines = new String[6];
    private int selected = -1;
    private String count, summary, status;
    private int countColor, statusColor;

    WorkoutHud(Context context) {
        super(context);
        regular = FontUtils.getFontFromRes(context, com.reconinstruments.ui.R.raw.opensans_regular);
        bold = FontUtils.getFontFromRes(context, com.reconinstruments.ui.R.raw.opensans_bold);
    }

    void workout(Profile profile, int reps, int set, boolean running, boolean complete, boolean flash,
                 String accel, String gyro, String message) {
        screen("RECOM3 WORKOUT", running ? "COUNTING" : "PAUSED", running ? GREEN : MUTED,
                "← → Exercise    OK " + (running ? "Pause" : "Start") + "    ↑ ↓ Menu");
        lines[0] = profile.exercise.label.toUpperCase(Locale.US);
        count = Integer.toString(reps);
        countColor = flash || complete ? GREEN : WHITE;
        summary = "Target " + profile.get(TARGET) + "   ·   Set " + set + "/" + profile.get(SETS);
        status = !message.isEmpty() ? message : complete
                ? (set == profile.get(SETS) ? "Exercise complete" : "Set complete") : flash ? "+1 rep" : "";
        statusColor = message.isEmpty() ? GREEN : AMBER;
        lines[5] = "Accel " + accel + "   ·   Gyro " + gyro;
        invalidate();
    }

    void menu(String[] actions, int index) {
        screen("WORKOUT MENU", (index + 1) + "/" + actions.length, MUTED,
                "↑ ↓ Move    OK Select    Back HUD");
        int first = Math.max(0, index - lines.length + 1);
        int count = Math.min(lines.length, actions.length - first);
        System.arraycopy(actions, first, lines, 0, count);
        selected = index - first;
        invalidate();
    }

    void setup(Profile profile, int field, String message) {
        screen("WORKOUT SETUP", "", MUTED, "↑ ↓ Field    ← → Adjust    Back Menu");
        lines[0] = "Exercise    " + profile.exercise.label;
        lines[2] = "Target reps    " + profile.get(TARGET);
        lines[3] = "Total sets    " + profile.get(SETS);
        lines[5] = message.isEmpty() ? "Saved for this exercise" : message;
        selected = field == 0 ? 0 : field + 1;
        invalidate();
    }

    void summary(WorkoutSession session, boolean csvReady, boolean ice3Synced) {
        screen("CONGRATS!", session.totalReps() + " REPS", GREEN, csvReady ? "OK New workout    Back Review"
                : "OK Retry CSV    Back Review");
        lines[0] = session.totalCompletedSets() + " sets completed";
        int row = 1;
        for (Exercise exercise : Exercise.values()) {
            lines[row++] = exercise.label + "    " + session.reps(exercise) + " reps    ·    "
                    + session.completedSets(exercise) + "/" + session.plannedSets(exercise) + " sets";
        }
        lines[4] = ice3Synced ? "Synced with ICE3" : "ICE3 sync pending";
        lines[5] = csvReady ? "Workout CSV saved" : "Saved locally · CSV export pending";
        invalidate();
    }

    void diagnostics(Profile profile, SensorStream accel, SensorStream gyro, MotionEngine engine,
                     CsvRecording recording, long now, String message) {
        screen("DIAGNOSTICS", recording == null ? profile.exercise.label : "REC · " + recording.markers(),
                recording == null ? MUTED : AMBER, recording == null ? "OK Record    Back Menu"
                        : "OK Mark rep    → Save CSV    Back Save");
        lines[0] = reading("ACC", accel, now);
        lines[1] = reading("GYR", gyro, now);
        lines[2] = String.format(Locale.US, "Vertical %+5.2f m/s²    Detected %d", engine.signal(), engine.count());
        lines[3] = engine.state() + "   ·   " + profile.exercise.label;
        lines[4] = String.format(Locale.US, "Trigger %.2f   Neutral %.2f   P–P %.2f", profile.acceleration(TRIGGER),
                profile.acceleration(RELEASE), profile.acceleration(AMPLITUDE));
        lines[5] = message.isEmpty() ? "Cycle " + profile.get(MIN_TIME) + "–" + profile.get(MAX_TIME)
                + " ms   ·   Debounce " + profile.get(DEBOUNCE) + " ms" : message;
        invalidate();
    }

    private String reading(String label, SensorStream sensor, long now) {
        if (!sensor.live(now)) return label + "   " + sensor.status(now);
        return String.format(Locale.US, "%s  X %+.2f  Y %+.2f  Z %+.2f   %.1f Hz",
                label, sensor.x, sensor.y, sensor.z, sensor.rate());
    }

    void calibration(Profile profile, Profile.Setting setting, String message) {
        screen("CALIBRATION", profile.exercise.label, MUTED, "↑ ↓ Field    ← → Adjust    Back Menu");
        lines[0] = "Experimental profiles";
        lines[2] = setting.label + "    " + profile.display(setting);
        lines[4] = (setting.ordinal() + 1) + " / " + Profile.Setting.values().length;
        lines[5] = message.isEmpty() ? "Changes saved locally" : message;
        selected = 2;
        invalidate();
    }

    private void screen(String title, String badge, int badgeColor, String footer) {
        this.title = title;
        this.badge = badge;
        this.badgeColor = badgeColor;
        this.footer = footer;
        count = null;
        selected = -1;
        for (int i = 0; i < lines.length; i++) lines[i] = "";
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BACKGROUND);
        if (title == null) return;
        float scale = Math.min(getWidth() / 428f, getHeight() / 240f);
        canvas.save();
        canvas.translate((getWidth() - 428f * scale) / 2, (getHeight() - 240f * scale) / 2);
        canvas.scale(scale, scale);
        text(canvas, title, 17, 27, 17, WHITE, true, Paint.Align.LEFT);
        text(canvas, badge, 411, 27, 13, badgeColor, true, Paint.Align.RIGHT);
        paint.setColor(PANEL);
        canvas.drawRoundRect(panel, 13, 13, paint);
        if (count != null) {
            text(canvas, lines[0], 25, 68, 16, MUTED, true, Paint.Align.LEFT);
            text(canvas, count, 23, 151, 72, countColor, true, Paint.Align.LEFT);
            text(canvas, summary, 402, 114, 19, WHITE, true, Paint.Align.RIGHT);
            text(canvas, status, 402, 146, 17, statusColor, true, Paint.Align.RIGHT);
            text(canvas, lines[5], 25, 195, 17, MUTED, false, Paint.Align.LEFT);
        } else {
            for (int i = 0; i < lines.length; i++) {
                int y = 67 + 25 * i;
                if (i == selected) {
                    selection.set(19, y - 18, 409, y + 5);
                    paint.setColor(BACKGROUND);
                    canvas.drawRoundRect(selection, 5, 5, paint);
                }
                text(canvas, lines[i], 25, y, 16, i == selected ? GREEN : WHITE,
                        i == selected, Paint.Align.LEFT);
            }
        }
        text(canvas, footer, 214, 230, 15, WHITE, false, Paint.Align.CENTER);
        canvas.restore();
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color,
                      boolean strong, Paint.Align align) {
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(strong ? bold : regular);
        paint.setTextAlign(align);
        canvas.drawText(value, x, y, paint);
    }
}
