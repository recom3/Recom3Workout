package com.recom3.workout;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.IOException;

import static com.recom3.workout.Profile.Setting.SETS;
import static com.recom3.workout.Profile.Setting.TARGET;

public final class WorkoutActivity extends Activity implements SensorEventListener {
    private enum Screen { HUD, MENU, DIAGNOSTICS, CALIBRATION }
    private static final int SAMPLE_US = 25000;
    private static final String TAG = "Recom3Workout";
    private static final String[] ACTIONS = {
            "Add a rep", "Subtract a rep", "Reset reps", "Next set", "Diagnostics", "Calibration"
    };
    private static final Profile.Setting[] SETTINGS = Profile.Setting.values();
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 125);
        }
    };

    private WorkoutHud hud;
    private SensorManager sensors;
    private SensorStream accel, gyro;
    private SharedPreferences preferences;
    private Profile profile;
    private MotionEngine workout, diagnostic;
    private Screen screen = Screen.HUD;
    private CsvRecording recording;
    private int exerciseIndex, set = 1, menuIndex, settingIndex;
    private long flashUntil;
    private String message = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("workout", MODE_PRIVATE);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = stream(Sensor.TYPE_ACCELEROMETER);
        gyro = stream(Sensor.TYPE_GYROSCOPE);
        loadExercise(preferences.getInt("exercise", 0));
        if (state != null) {
            set = Math.max(1, Math.min(state.getInt("set"), profile.get(SETS)));
            workout.correct(state.getInt("reps"));
        }
        hud = new WorkoutHud(this);
        setContentView(hud);
        render();
    }

    private SensorStream stream(int type) {
        return new SensorStream(sensors == null ? null : sensors.getDefaultSensor(type));
    }

    private String key(Profile.Setting setting) {
        return profile.exercise.name() + "." + setting.name();
    }

    private void loadExercise(int index) {
        Exercise[] exercises = Exercise.values();
        exerciseIndex = (index + exercises.length) % exercises.length;
        profile = new Profile(exercises[exerciseIndex]);
        // Load bounds in an order that also permits a saved pair above or below both defaults.
        for (int pass = 0; pass < 2; pass++) {
            for (Profile.Setting setting : SETTINGS) {
                profile.change(setting, preferences.getInt(key(setting), profile.get(setting)));
            }
        }
        workout = new MotionEngine(profile);
        set = 1;
        flashUntil = 0;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("set", set);
        state.putInt("reps", workout.count());
    }

    @Override protected void onResume() {
        super.onResume();
        register(accel);
        register(gyro);
        if (diagnostic != null) diagnostic.setRunning(true);
        handler.post(refresh);
    }

    private void register(SensorStream stream) {
        stream.clear();
        stream.registered = stream.sensor != null && sensors.registerListener(this, stream.sensor, SAMPLE_US);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if (sensors != null) sensors.unregisterListener(this);
        accel.registered = gyro.registered = false;
        stopRecording();
        workout.setRunning(false);
        workout.clearSensors();
        if (diagnostic != null) {
            diagnostic.setRunning(false);
            diagnostic.clearSensors();
        }
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        SensorStream stream = event.sensor.getType() == Sensor.TYPE_ACCELEROMETER ? accel : gyro;
        long now = SystemClock.elapsedRealtime();
        if (!stream.registered || !stream.update(now, event.timestamp, event.values)) return;
        MotionEngine engine = screen == Screen.DIAGNOSTICS ? diagnostic : workout;
        if (stream == gyro) {
            engine.gyro(event.timestamp, stream.x, stream.y, stream.z);
        } else if (engine.accel(event.timestamp, stream.x, stream.y, stream.z) && engine == workout) {
            flashUntil = now + 550;
            if (complete()) workout.setRunning(false);
            render();
        }
        if (recording != null) {
            try {
                recording.sensor(stream == accel ? "ACCEL" : "GYRO", now, event.timestamp, stream, engine);
            } catch (IOException failure) {
                recordingFailed(failure);
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean complete() {
        return workout.count() >= profile.get(TARGET);
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP;
        boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean back = code == KeyEvent.KEYCODE_BACK;
        if (!select && !left && !right && !up && !down && !back) return super.onKeyUp(code, event);
        if (event.isCanceled()) return true;
        if (back && screen == Screen.HUD && !workout.running()) return super.onKeyUp(code, event);
        switch (screen) {
            case HUD:
                message = "";
                if (back) workout.setRunning(false);
                else if (select) toggleWorkout();
                else if (up || down) {
                    workout.setRunning(false);
                    menuIndex = 0;
                    screen = Screen.MENU;
                } else if (workout.running()) message = "Pause to change exercise";
                else {
                    loadExercise(exerciseIndex + (right ? 1 : -1));
                    preferences.edit().putInt("exercise", exerciseIndex).apply();
                }
                break;
            case MENU:
                message = "";
                if (back) screen = Screen.HUD;
                else if (select) selectAction();
                else menuIndex = (menuIndex + (up || left ? ACTIONS.length - 1 : 1)) % ACTIONS.length;
                break;
            case DIAGNOSTICS:
                if (back) {
                    stopRecording();
                    workout.clearSensors();
                    screen = Screen.MENU;
                    diagnostic = null;
                } else if (select) {
                    if (recording == null) startRecording();
                    else mark();
                } else if (right) stopRecording();
                break;
            case CALIBRATION:
                message = "";
                if (back) screen = Screen.MENU;
                else if (up || down || select) {
                    settingIndex = (settingIndex + (up ? SETTINGS.length - 1 : 1)) % SETTINGS.length;
                } else {
                    Profile.Setting setting = SETTINGS[settingIndex];
                    int value = profile.get(setting) + (right ? setting.step : -setting.step);
                    if (profile.change(setting, value)) {
                        preferences.edit().putInt(key(setting), value).apply();
                        set = Math.min(set, profile.get(SETS));
                    } else message = "Limit reached";
                }
                break;
        }
        render();
        return true;
    }

    private void toggleWorkout() {
        if (workout.running()) workout.setRunning(false);
        else if (complete()) message = "Open Menu for the next set";
        else if (!accel.live(SystemClock.elapsedRealtime())) message = "Waiting for accelerometer";
        else workout.setRunning(true);
    }

    private void selectAction() {
        switch (menuIndex) {
            case 0: workout.correct(1); break;
            case 1: workout.correct(-1); break;
            case 2: workout.reset(); break;
            case 3:
                if (set < profile.get(SETS)) {
                    set++;
                    workout.reset();
                } else message = "Already on the last set";
                break;
            case 4:
                screen = Screen.DIAGNOSTICS;
                diagnostic = new MotionEngine(profile);
                diagnostic.setRunning(true);
                return;
            case 5:
                screen = Screen.CALIBRATION;
                settingIndex = 0;
                return;
            default: return;
        }
        flashUntil = 0;
        screen = Screen.HUD;
    }

    private void startRecording() {
        try {
            recording = new CsvRecording(getExternalFilesDir("recordings"), profile, SystemClock.elapsedRealtime());
            diagnostic.reset();
            diagnostic.setRunning(true);
            message = "";
        } catch (IOException failure) {
            recordingFailed(failure);
        } catch (SecurityException failure) {
            recordingFailed(failure);
        }
    }

    private void mark() {
        try {
            recording.mark(SystemClock.elapsedRealtime(), diagnostic);
        } catch (IOException failure) {
            recordingFailed(failure);
        }
    }

    private void stopRecording() {
        if (recording == null) return;
        try {
            recording.close();
            message = "CSV saved · " + recording.markers() + " marks";
            Log.i(TAG, "CSV saved: " + recording.file);
        } catch (IOException failure) {
            recordingFailed(failure);
        } finally {
            recording = null;
        }
    }

    private void recordingFailed(Exception failure) {
        Log.e(TAG, "CSV export failed", failure);
        if (recording != null) {
            try { recording.close(); } catch (IOException ignored) {}
        }
        recording = null;
        message = "CSV write failed";
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        switch (screen) {
            case HUD:
                hud.workout(profile, workout.count(), set, workout.running(), complete(),
                        now < flashUntil, accel.status(now), gyro.status(now), message);
                break;
            case MENU:
                hud.menu(ACTIONS, menuIndex);
                break;
            case DIAGNOSTICS:
                hud.diagnostics(profile, accel, gyro, diagnostic, recording, now, message);
                break;
            case CALIBRATION:
                hud.calibration(profile, SETTINGS[settingIndex], message);
                break;
        }
    }
}
