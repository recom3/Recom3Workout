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
    private enum Screen { HUD, MENU, SETUP, DIAGNOSTICS, CALIBRATION, SUMMARY }
    private static final int SAMPLE_US = 25000;
    private static final String TAG = "Recom3Workout";
    private static final String[] ACTIONS = {
            "Workout setup", "Add a rep", "Subtract a rep", "Reset reps", "Next set",
            "Diagnostics", "Calibration", "Export CSV", "New workout"
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
    private WorkoutSession session;
    private SessionStore sessions;
    private WorkoutSync sync;
    private String syncNotice = "";
    private boolean csvReady;
    private Screen screen = Screen.HUD;
    private CsvRecording recording;
    private int exerciseIndex, set = 1, menuIndex, settingIndex, setupIndex;
    private long flashUntil;
    private long setCompletedAt;
    private String message = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("workout", MODE_PRIVATE);
        sessions = new SessionStore(getFilesDir(), getExternalFilesDir("workouts"));
        try {
            session = sessions.load();
        } catch (IOException failure) {
            message = "Workout history read failed";
            Log.e(TAG, message, failure);
        }
        if (session == null) {
            session = new WorkoutSession(readProfiles());
            try {
                sessions.save(session);
            } catch (IOException failure) {
                message = "Workout history save failed";
                Log.e(TAG, message, failure);
            }
        }
        SharedPreferences.Editor goals = preferences.edit();
        for (Exercise exercise : Exercise.values()) {
            goals.putInt(exercise.name() + "." + TARGET.name(), session.target(exercise));
            goals.putInt(exercise.name() + "." + SETS.name(), session.plannedSets(exercise));
        }
        goals.apply();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = stream(Sensor.TYPE_ACCELEROMETER);
        gyro = stream(Sensor.TYPE_GYROSCOPE);
        loadExercise(preferences.getInt("exercise", 0));
        if (state != null && session.id.equals(state.getString("workout_id"))) {
            set = Math.max(1, Math.min(state.getInt("set"), profile.get(SETS)));
            workout.reset();
            workout.correct(Math.max(0, state.getInt("reps")));
        }
        hud = new WorkoutHud(this);
        setContentView(hud);
        if (session.complete()) screen = Screen.SUMMARY;
        sync = WorkoutSync.get(this);
        sync.startup();
        render();
    }

    private SensorStream stream(int type) {
        return new SensorStream(sensors == null ? null : sensors.getDefaultSensor(type));
    }

    private String key(Profile.Setting setting) {
        return profile.exercise.name() + "." + setting.name();
    }

    private Profile readProfile(Exercise exercise) {
        Profile loaded = new Profile(exercise);
        // Load bounds in an order that also permits a saved pair above or below both defaults.
        for (int pass = 0; pass < 2; pass++) {
            for (Profile.Setting setting : SETTINGS) {
                loaded.change(setting, preferences.getInt(exercise.name() + "." + setting.name(),
                        loaded.get(setting)));
            }
        }
        return loaded;
    }

    private Profile[] readProfiles() {
        Exercise[] exercises = Exercise.values();
        Profile[] profiles = new Profile[exercises.length];
        for (int i = 0; i < exercises.length; i++) profiles[i] = readProfile(exercises[i]);
        return profiles;
    }

    private void loadExercise(int index) {
        Exercise[] exercises = Exercise.values();
        exerciseIndex = (index + exercises.length) % exercises.length;
        profile = readProfile(exercises[exerciseIndex]);
        profile.change(TARGET, session.target(profile.exercise));
        profile.change(SETS, session.plannedSets(profile.exercise));
        workout = new MotionEngine(profile);
        selectSet(Math.min(session.completedSets(profile.exercise) + 1, profile.get(SETS)));
    }

    private void selectSet(int number) {
        set = number;
        workout.reset();
        WorkoutSession.SetResult result = session.result(profile.exercise, set);
        if (result != null) workout.correct(result.reps);
        setCompletedAt = 0;
        flashUntil = 0;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("set", set);
        state.putInt("reps", workout.count());
        state.putString("workout_id", session.id);
    }

    @Override protected void onResume() {
        super.onResume();
        register(accel);
        register(gyro);
        if (session.started()) exportSession();
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
            if (complete()) finishSet();
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
            case SETUP:
                message = "";
                if (back) screen = session.complete() ? Screen.SUMMARY : Screen.MENU;
                else if (up || down || select) {
                    setupIndex = (setupIndex + (up ? 2 : 1)) % 3;
                } else if (setupIndex == 0) {
                    loadExercise(exerciseIndex + (right ? 1 : -1));
                    preferences.edit().putInt("exercise", exerciseIndex).apply();
                } else {
                    changeSetting(setupIndex == 1 ? TARGET : SETS, right ? 1 : -1);
                }
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
                    changeSetting(SETTINGS[settingIndex], right ? 1 : -1);
                }
                break;
            case SUMMARY:
                if (back) screen = Screen.HUD;
                else if (select) {
                    if (!csvReady) exportSession();
                    else newWorkout();
                }
                break;
        }
        render();
        return true;
    }

    private void toggleWorkout() {
        if (workout.running()) workout.setRunning(false);
        else if (complete()) {
            if (session.result(profile.exercise, set) == null) finishSet();
            else advanceAfterSet();
        }
        else if (!accel.live(SystemClock.elapsedRealtime())) message = "Waiting for accelerometer";
        else {
            if (!session.started()) {
                WorkoutSession next = new WorkoutSession(session);
                next.begin(System.currentTimeMillis());
                if (!saveSession(next)) return;
            }
            setCompletedAt = 0;
            workout.setRunning(true);
        }
    }

    private void selectAction() {
        switch (menuIndex) {
            case 0:
                screen = Screen.SETUP;
                setupIndex = 0;
                return;
            case 1: correctCount(1); break;
            case 2: correctCount(-1); break;
            case 3:
                WorkoutSession reset = new WorkoutSession(session);
                reset.resetFrom(profile.exercise, set);
                if (saveSession(reset)) {
                    workout.reset();
                    setCompletedAt = 0;
                }
                break;
            case 4:
                if (session.result(profile.exercise, set) == null) {
                    WorkoutSession next = new WorkoutSession(session);
                    next.record(profile.exercise, set, workout.count(), System.currentTimeMillis(),
                            complete() ? "TARGET" : "MANUAL");
                    if (!saveSession(next)) break;
                }
                advanceAfterSet();
                break;
            case 5:
                screen = Screen.DIAGNOSTICS;
                diagnostic = new MotionEngine(profile);
                return;
            case 6:
                screen = Screen.CALIBRATION;
                settingIndex = 0;
                return;
            case 7:
                try {
                    int count = sessions.exportAll();
                    csvReady = true;
                    message = count + " workout CSV files exported";
                } catch (IOException failure) {
                    csvReady = false;
                    message = "CSV export pending";
                    Log.e(TAG, message, failure);
                } catch (SecurityException failure) {
                    csvReady = false;
                    message = "CSV export pending";
                    Log.e(TAG, message, failure);
                }
                break;
            case 8:
                newWorkout();
                return;
            default: return;
        }
        flashUntil = 0;
        if (screen != Screen.SUMMARY) screen = Screen.HUD;
    }

    private void changeSetting(Profile.Setting setting, int direction) {
        int previous = profile.get(setting);
        int value = previous + direction * setting.step;
        if (!profile.change(setting, value)) {
            message = "Limit reached";
            return;
        }
        if (setting == TARGET || setting == SETS) {
            WorkoutSession next = new WorkoutSession(session);
            next.plan(profile.exercise, profile.get(TARGET), profile.get(SETS));
            if (!saveSession(next)) {
                profile.change(setting, previous);
                return;
            }
            if (set > profile.get(SETS)) selectSet(profile.get(SETS));
        }
        preferences.edit().putInt(key(setting), value).apply();
    }

    private void correctCount(int delta) {
        WorkoutSession.SetResult previous = session.result(profile.exercise, set);
        if (previous != null) {
            WorkoutSession next = new WorkoutSession(session);
            next.record(profile.exercise, set, Math.max(0, workout.count() + delta),
                    previous.completedAt, "CORRECTED");
            if (!saveSession(next)) return;
        }
        workout.correct(delta);
        if (complete() && previous == null) finishSet();
    }

    private void finishSet() {
        workout.setRunning(false);
        if (setCompletedAt == 0) setCompletedAt = System.currentTimeMillis();
        WorkoutSession next = new WorkoutSession(session);
        next.record(profile.exercise, set, workout.count(), setCompletedAt, "TARGET");
        if (saveSession(next)) advanceAfterSet();
    }

    private void advanceAfterSet() {
        Exercise next = session.nextExercise(profile.exercise);
        if (next == null) {
            screen = Screen.SUMMARY;
            return;
        }
        boolean sameExercise = next == profile.exercise;
        loadExercise(next.ordinal());
        preferences.edit().putInt("exercise", exerciseIndex).apply();
        if (csvReady) message = sameExercise ? "Set saved · OK start" : "Next exercise · OK";
    }

    private boolean saveSession(WorkoutSession next) {
        try {
            sessions.save(next);
            session = next;
            csvReady = !session.started();
            if (session.started()) exportSession();
            if (session.complete()) sync.completed(session);
            return true;
        } catch (IOException failure) {
            message = "Workout save failed · OK retry";
            Log.e(TAG, message, failure);
            return false;
        } catch (SecurityException failure) {
            message = "Workout save failed · OK retry";
            Log.e(TAG, message, failure);
            return false;
        }
    }

    private void exportSession() {
        try {
            sessions.export(session);
            csvReady = true;
            if (message.equals("Workout saved · CSV export pending")) message = "";
        } catch (IOException failure) {
            csvReady = false;
            message = "Workout saved · CSV export pending";
            Log.e(TAG, message, failure);
        } catch (SecurityException failure) {
            csvReady = false;
            message = "Workout saved · CSV export pending";
            Log.e(TAG, message, failure);
        }
    }

    private void newWorkout() {
        WorkoutSession next = new WorkoutSession(readProfiles());
        if (saveSession(next)) {
            loadExercise(exerciseIndex);
            message = "";
            screen = Screen.HUD;
        }
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
            diagnostic.setRunning(false);
        }
    }

    private void recordingFailed(Exception failure) {
        Log.e(TAG, "CSV export failed", failure);
        if (recording != null) {
            try { recording.close(); } catch (IOException ignored) {}
        }
        recording = null;
        diagnostic.setRunning(false);
        message = "CSV write failed";
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        switch (screen) {
            case HUD:
                hud.workout(profile, workout.count(), set, workout.running(), complete(),
                        now < flashUntil, accel.status(now), gyro.status(now),
                        workout.waitingForStillness() ? "Hold still to start" : message);
                break;
            case MENU:
                hud.menu(ACTIONS, menuIndex);
                break;
            case SETUP:
                hud.setup(profile, setupIndex, message);
                break;
            case DIAGNOSTICS:
                hud.diagnostics(profile, accel, gyro, diagnostic, recording, now, message);
                break;
            case CALIBRATION:
                hud.calibration(profile, SETTINGS[settingIndex], message);
                break;
            case SUMMARY:
                SyncQueue.State syncState = sync.state(session.id);
                hud.summary(session, csvReady, syncState == SyncQueue.State.SYNCED);
                String notice = session.id + ":" + syncState;
                if (!notice.equals(syncNotice) && syncState != SyncQueue.State.PENDING) {
                    syncNotice = notice;
                    android.widget.Toast.makeText(this, syncState == SyncQueue.State.SYNCED
                            ? "Workout synced with ICE3." : "Workout saved. ICE3 sync pending.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }
}
