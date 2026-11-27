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
    private enum Screen { HUD, MENU, SETUP, DIAGNOSTICS, CALIBRATION, SUMMARY, REPLACE_ROUTINE, ROUTINE_PREVIEW }
    private static final int SAMPLE_US = 25000;
    private static final String TAG = "Recom3Workout";
    private static final String[] ACTIONS = {
            "Workout setup", "Add a rep", "Subtract a rep", "Reset reps", "Next set",
            "Diagnostics", "Calibration", "Export CSV", "New workout", "Replace synced routine", "Review synced routine"
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
    private ProfileStore profiles;
    private int summaryPage;
    private MotionEngine workout, diagnostic;
    private WorkoutSession session;
    private SessionStore sessions;
    private WorkoutSync sync;
    private String syncNotice = "";
    private boolean csvReady;
    private boolean freshTemplate;
    private String freshTemplateHash = "", appliedTemplateHash = "";
    private WorkoutTemplate latestTemplate;
    private WorkoutTemplate previewTemplate;
    private String approvedTemplateHash = "", promptedTemplateHash = "";
    private int previewExercise, previewFirstSet = 1;
    private boolean previewStartNew;
    private Screen previewReturn = Screen.HUD;
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
        profiles = LocalProfiles.open(this);
        approvedTemplateHash = preferences.getString("routine_approved_hash", "");
        sessions = new SessionStore(getFilesDir(), getExternalFilesDir("workouts"));
        try {
            session = sessions.load();
        } catch (IOException failure) {
            message = "Workout history read failed";
            Log.e(TAG, message, failure);
        }
        if (session == null) {
            try {
                session = freshSession();
                sessions.save(session);
                markAppliedRoutine();
            } catch (IOException failure) {
                if (session == null) session = new WorkoutSession(readProfiles());
                message = "Workout history save failed";
                Log.e(TAG, message, failure);
            }
        }
        if (session.id.equals(preferences.getString("routine_session", "")))
            appliedTemplateHash = preferences.getString("routine_hash", "");
        SharedPreferences.Editor goals = preferences.edit();
        for (Exercise exercise : session.exercises()) {
            goals.putInt(exercise.name() + "." + TARGET.name(), session.target(exercise));
            goals.putInt(exercise.name() + "." + SETS.name(), session.plannedSets(exercise));
        }
        goals.apply();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = stream(Sensor.TYPE_ACCELEROMETER);
        gyro = stream(Sensor.TYPE_GYROSCOPE);
        loadExercise(freshTemplate && !session.dynamic ? session.indexOf(Exercise.SQUAT) : preferences.getInt("exercise", 0));
        if (state != null && session.id.equals(state.getString("workout_id"))) {
            set = Math.max(1, Math.min(state.getInt("set"), profile.get(SETS)));
            profile.change(TARGET, session.target(profile.exercise, set));
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
        try { return profiles.load(exercise); }
        catch (IOException failure) {
            message = "Calibration unavailable · manual only";
            Log.w(TAG, "Calibration retained; storage unavailable");
            Profile fallback = new Profile(exercise);
            fallback.change(Profile.Setting.MODE, 0);
            return fallback;
        }
    }

    private Profile[] readProfiles() {
        Exercise[] exercises = Exercise.values();
        Profile[] profiles = new Profile[exercises.length];
        for (int i = 0; i < exercises.length; i++) profiles[i] = readProfile(exercises[i]);
        return profiles;
    }

    private void loadExercise(int index) {
        Exercise[] exercises = session.exercises();
        exerciseIndex = WorkoutNavigation.wrap(index, exercises.length);
        profile = session.dynamic ? session.profile(exercises[exerciseIndex]) : readProfile(exercises[exerciseIndex]);
        profile.change(TARGET, session.target(profile.exercise));
        profile.change(SETS, session.plannedSets(profile.exercise));
        workout = new MotionEngine(profile);
        selectSet(Math.min(session.completedSets(profile.exercise) + 1, profile.get(SETS)));
    }

    private void selectSet(int number) {
        set = number;
        profile.change(TARGET, session.target(profile.exercise, set));
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
        try { latestTemplate = new TemplateStore(getFilesDir()).load(); }
        catch (IOException unreadable) { Log.w(TAG, "Saved routine unavailable; current session retained"); }
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
                    setupIndex = (setupIndex + (up ? 3 : 1)) % 4;
                } else if (setupIndex == 0) {
                    loadExercise(exerciseIndex + (right ? 1 : -1));
                    preferences.edit().putInt("exercise", exerciseIndex).apply();
                } else if (setupIndex == 1) {
                    selectSet(Math.max(1, Math.min(profile.get(SETS), set + (right ? 1 : -1))));
                } else {
                    changeSetting(setupIndex == 2 ? TARGET : SETS, right ? 1 : -1);
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
                } else summaryPage = WorkoutNavigation.wrap(summaryPage + (left || up ? -1 : 1), WorkoutNavigation.pages(session.exercises().length));
                break;
            case REPLACE_ROUTINE:
                if (select) {
                    TemplateReceiver.allowReplacement(this);
                    message = "Send routine from ICE3 within 2 min";
                    screen = Screen.HUD;
                } else if (back) screen = Screen.MENU;
                break;
            case ROUTINE_PREVIEW:
                if (back) {
                    screen = previewReturn;
                    message = "Routine saved for later";
                } else if (select) acceptRoutine();
                else if (left || right) {
                    previewExercise = WorkoutNavigation.wrap(previewExercise + (right ? 1 : -1), previewTemplate.exercises().length);
                    previewFirstSet = 1;
                } else {
                    int total = previewTemplate.targets(previewTemplate.exercises()[previewExercise]).length;
                    previewFirstSet = Math.max(1, Math.min(Math.max(1, total - 3), previewFirstSet + (down ? 1 : -1)));
                }
                break;
        }
        render();
        return true;
    }

    private void toggleWorkout() {
        if (!workout.running() && !session.started() && latestTemplate != null
                && appliedTemplateHash.equals(latestTemplate.hash)
                && RoutineConsent.needsReview(latestTemplate, approvedTemplateHash)) {
            showRoutine(latestTemplate, false);
            return;
        }
        if (workout.running()) workout.setRunning(false);
        else if (complete()) {
            if (session.result(profile.exercise, set) == null) finishSet();
            else advanceAfterSet();
        }
        else if (!profile.manual() && !accel.live(SystemClock.elapsedRealtime())) message = "Waiting for accelerometer";
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
                    next.profile(profile);
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
            case 9:
                screen = Screen.REPLACE_ROUTINE;
                return;
            case 10:
                try {
                    WorkoutTemplate template = new TemplateStore(getFilesDir()).load();
                    if (template == null) message = "No synced routine saved";
                    else { showRoutine(template, false); return; }
                } catch (IOException failure) { message = "Synced routine unreadable"; }
                break;
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
            if (setting == TARGET) next.target(profile.exercise, set, profile.get(TARGET));
            else {
                try { next.resize(profile.exercise, profile.get(SETS)); }
                catch (IllegalArgumentException limit) { profile.change(setting, previous); message = "Total set limit reached"; return; }
            }
            if (!saveSession(next)) {
                profile.change(setting, previous);
                return;
            }
            if (set > profile.get(SETS)) selectSet(profile.get(SETS));
        }
        try {
            profiles.save(profile);
            if (session.dynamic) {
                WorkoutSession next = new WorkoutSession(session); next.profile(profile);
                if (!saveSession(next)) message = "Calibration saved · session retry needed";
            }
            preferences.edit().putInt(key(setting), value).apply();
        } catch (IOException failure) {
            if (setting != TARGET && setting != SETS) profile.change(setting, previous);
            message = "Calibration save failed · retained";
        }
    }

    private void correctCount(int delta) {
        WorkoutSession.SetResult previous = session.result(profile.exercise, set);
        if (previous != null) {
            WorkoutSession next = new WorkoutSession(session);
            next.profile(profile);
            next.record(profile.exercise, set, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) workout.count() + delta)),
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
        next.profile(profile);
        next.record(profile.exercise, set, workout.count(), setCompletedAt, "TARGET");
        if (saveSession(next)) advanceAfterSet();
    }

    private void advanceAfterSet() {
        Exercise next = session.nextExercise(profile.exercise);
        if (next == null) {
            screen = Screen.SUMMARY;
            return;
        }
        boolean sameExercise = next.equals(profile.exercise);
        loadExercise(session.indexOf(next));
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

    private WorkoutSession freshSession() throws IOException {
        WorkoutTemplate template = new TemplateStore(getFilesDir()).load();
        latestTemplate = template;
        return sessionSnapshot(RoutineConsent.needsReview(template, approvedTemplateHash) ? null : template);
    }

    private WorkoutSession sessionSnapshot(WorkoutTemplate template) throws IOException {
        freshTemplate = template != null;
        freshTemplateHash = template == null ? "" : template.hash;
        if (template == null) return WorkoutSession.create(readProfiles(), null);
        Exercise[] exercises = template.exercises();
        Profile[] selected = new Profile[exercises.length];
        for (int i = 0; i < selected.length; i++) selected[i] = profiles.load(exercises[i]);
        return WorkoutSession.create(selected, template);
    }

    private void markAppliedRoutine() {
        appliedTemplateHash = freshTemplateHash;
        preferences.edit().putString("routine_session", session.id).putString("routine_hash", appliedTemplateHash).apply();
    }

    private void newWorkout() {
        try {
            WorkoutTemplate template = new TemplateStore(getFilesDir()).load();
            latestTemplate = template;
            if (RoutineConsent.needsReview(template, approvedTemplateHash)) { showRoutine(template, true); return; }
            startNewWorkout(template);
        }
        catch (IOException failure) {
            message = "Synced routine unreadable";
            Log.w(TAG, "Cannot start a new routine; existing session retained");
        }
    }

    private void startNewWorkout(WorkoutTemplate template) throws IOException {
        WorkoutSession next = sessionSnapshot(RoutineConsent.approved(template, approvedTemplateHash));
        if (saveSession(next)) {
            markAppliedRoutine();
            if (freshTemplate) exerciseIndex = template.dynamic() ? 0 : session.indexOf(Exercise.SQUAT);
            summaryPage = 0;
            loadExercise(exerciseIndex);
            preferences.edit().putInt("exercise", exerciseIndex).apply();
            message = "";
            screen = Screen.HUD;
        }
    }

    private void showRoutine(WorkoutTemplate template, boolean startNew) {
        previewTemplate = template;
        promptedTemplateHash = template.hash;
        previewExercise = 0;
        previewFirstSet = 1;
        previewStartNew = startNew;
        previewReturn = screen == Screen.SUMMARY ? Screen.SUMMARY : Screen.HUD;
        message = "";
        screen = Screen.ROUTINE_PREVIEW;
    }

    private void acceptRoutine() {
        if (!preferences.edit().putString("routine_approved_hash", previewTemplate.hash).commit()) {
            message = "Acceptance save failed · OK retry";
            return;
        }
        approvedTemplateHash = previewTemplate.hash;
        if (previewStartNew || !session.started() || session.complete()) {
            try { startNewWorkout(previewTemplate); }
            catch (IOException failure) { message = "Routine cannot be started"; }
        } else {
            screen = Screen.HUD;
            message = "Accepted for next workout";
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
        WorkoutTemplate received = TemplateReceiver.latest(this);
        if (received != null) latestTemplate = received;
        boolean routineAvailable = latestTemplate != null && !latestTemplate.hash.equals(appliedTemplateHash);
        boolean routineApplied = !appliedTemplateHash.isEmpty();
        if (RoutineConsent.needsReview(latestTemplate, approvedTemplateHash)
                && !latestTemplate.hash.equals(promptedTemplateHash)
                && (screen == Screen.SUMMARY || screen == Screen.MENU || screen == Screen.HUD && !workout.running()))
            showRoutine(latestTemplate, false);
        switch (screen) {
            case HUD:
                hud.workout(profile, workout.count(), set, workout.running(), complete(),
                        now < flashUntil, accel.status(now), gyro.status(now),
                        workout.waitingForStillness() ? "Hold still to start" : message, routineAvailable, routineApplied);
                break;
            case MENU:
                ACTIONS[8] = routineAvailable ? "New workout · synced routine" : "New workout";
                hud.menu(ACTIONS, menuIndex);
                break;
            case SETUP:
                hud.setup(profile, set, setupIndex, message.isEmpty() && routineAvailable
                        ? "New routine ready · Menu > New workout" : message);
                break;
            case DIAGNOSTICS:
                hud.diagnostics(profile, accel, gyro, diagnostic, recording, now, message);
                break;
            case CALIBRATION:
                hud.calibration(profile, SETTINGS[settingIndex], message);
                break;
            case SUMMARY:
                SyncQueue.State syncState = sync.state(session.id);
                hud.summary(session, csvReady, syncState == SyncQueue.State.SYNCED, routineAvailable, routineApplied, summaryPage);
                String notice = session.id + ":" + syncState;
                if (!notice.equals(syncNotice) && syncState != SyncQueue.State.PENDING) {
                    syncNotice = notice;
                    android.widget.Toast.makeText(this, syncState == SyncQueue.State.SYNCED
                            ? "Workout synced with ICE3." : "Workout saved. ICE3 sync pending.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                break;
            case REPLACE_ROUTINE:
                hud.replaceRoutine();
                break;
            case ROUTINE_PREVIEW:
                hud.routinePreview(previewTemplate, previewExercise, previewFirstSet, message);
                break;
        }
    }
}
