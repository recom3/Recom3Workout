# Recom3Workout

An offline workout HUD for the original Recon Jet. It counts candidate pull-ups, squats and push-ups from the goggles' motion. Each exercise has its own locally saved profile. The included profiles are experimental and unvalidated against real exercises.

## HUD preview

![Recom3 Workout HUD on a Recon Jet showing squats, the rep counter, the target, set progress and live sensors](docs/recom3-workout-hud.png)

## Build and install

The project inherits Recom3Ghost's Java 7 source level, Gradle 4.1, Android Gradle Plugin 3.0.1, compile SDK 27, Build Tools 27.0.3 and minimum and target API 16. Use JDK 8. Set `JAVA_HOME` and `ANDROID_HOME`.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.recom3.workout/.WorkoutActivity
```

On Windows, `.\build-apk.ps1` also builds, signs and verifies the APK at the same path using the local SDK. Use that route if the legacy Gradle plugin cannot be resolved. Retain the signing keystore for updates. The package is `com.recom3.workout`.

The web catalog files are `build/installer/Recom3Workout.rif` and `build/installer/recom3workout.png`. The RIF packages the signed APK and the 122 × 68 PNG is a thumbnail of the HUD preview.

## Controls

The app accepts the same Select/OK key events as Recom3Ghost. Use the Jet's directional touchpad swipes or equivalent D-pad inputs. [Recon Jet owner manual](https://fccid.io/m/895259af0d76fda203311f4ed5b2657f051e1a39543cc581d18ee9abe695fe49.pdf)

| Screen | Controls |
| --- | --- |
| Workout | Left/Right selects an exercise while paused and starts it at set 1. OK starts or pauses. Up/Down opens the menu and pauses detection. Back pauses a running workout or exits from the paused HUD. |
| Menu | Move with the directions and press OK to select. Add or subtract one rep, reset the current set's count, advance to the next set, open Diagnostics, or open Calibration. Back returns to the workout. |
| Diagnostics | OK starts a recording, then adds one ground-truth marker per press. Right saves and stops. Back saves and returns to the menu. |
| Calibration | Up/Down selects a field, Left/Right adjusts it, and Back returns to the menu. Changes save immediately for the current exercise. |

Set completion pauses counting automatically. Use **Next set** to advance and **OK** to resume. The default is three sets with targets of 8 pull-ups, 12 squats or 10 push-ups per set. Set and rep progress is session state. The selected exercise and settings survive relaunch. Keep the app in the foreground while detecting or recording.

## Sensors and detection

- `WorkoutActivity` registers available Android sensors at a requested 25 ms interval, computes delivered rates from sensor timestamps, and handles controls, lifecycle and local preferences. The fullscreen, scaled Canvas HUD and Recon UI font module come from Recom3Ghost's design.
- `MotionEngine` estimates gravity in goggles coordinates from all three acceleration axes. Fresh gyroscope samples propagate the gravity direction through head rotations. A slow accelerometer correction also provides the fallback when the gyro is missing or stale. The engine projects acceleration onto the gravity direction, subtracts a measured resting baseline and low-pass filters the result. Hold still briefly when opening Diagnostics, starting an exercise after changing it, or recovering from a sensor interruption.
- `RepDetector` uses an exercise-specific movement direction, trigger, neutral band, peak-to-peak amplitude, cycle time and debounce. After neutral arming, it looks for outbound acceleration, reversal, return braking and settling. It clears partial cycles on reset, pause and sample gaps. No position estimate is integrated from acceleration.
- Without a working accelerometer, manual count and workout controls remain available. Diagnostics uses a separate detector so trial runs leave the workout count intact.

## Record and calibrate

1. Choose the exercise, open **Diagnostics**, let the sensor readings settle, then press **OK** to record.
2. Press **OK** once per completed rep to mark its approximate completion time. Include stillness, head turns, incomplete reps, and varied tempos in separate trials. Marker presses can themselves introduce motion.
3. Save with **Right**. Pull the recordings directory over USB. The save message in `adb logcat -d -s Recom3Workout:I` includes the exact file path.

```powershell
adb pull /sdcard/Android/data/com.recom3.workout/files/recordings ./recordings
```

Each CSV starts with a `CONFIG` row containing the exercise and active thresholds. `ACCEL` and `GYRO` rows preserve each stream's raw XYZ, timestamp in nanoseconds, elapsed receipt time in milliseconds, latest filtered vertical signal, detector state and count. Accelerometer fields use m/s² and gyro fields use rad/s. `MARK` rows share the elapsed receipt timeline and have sequential marker numbers. A difference in receipt time can occur across the two asynchronous streams. Exiting the screen or backgrounding the app closes an active recording.

Compare detection transitions against the marked cycles and tune one profile at a time. The trigger sets the signed phase-crossing magnitude. The neutral band and debounce govern settling and rearming. Peak-to-peak is total signal swing in m/s². Cycle times are in milliseconds. Long pauses at turnarounds, closely spaced reps, head motion and small vertical displacements can produce misses or false counts. Head motion is an indirect measure of each exercise.

## Verification

```powershell
.\checks\run.ps1
```

The 27 dependency-free JVM checks passed with JDK 8. They cover full and partial movements, minimum amplitude, noise, orientation, fast and slow cycles, state transitions, debounce, pause/resume, reset and gaps.

The standalone Windows build produced a signed debug APK whose v1 signature verified for API 16. The offline Gradle build could not resolve the legacy Android Gradle Plugin 3.0.1.

The APK installed and launched on an original Recon Jet reporting API 16. A stationary test measured 38.9 Hz on each sensor and exported raw samples and one manual marker. The detector armed and reported zero reps during that capture. ADB-injected D-pad events verified exercise selection, start/pause, manual correction, set advancement and navigation to calibration and diagnostics. Physical touchpad input and rep accuracy during exercises still need validation.

## Licenses

- Recom3Workout and its Recom3Ghost base are distributed under the [MIT License](LICENSE).
- The bundled Recon UI code is covered by the [Artistic License 2.0](reconUI/LICENSE).
- The bundled Open Sans fonts are covered by the [Apache License 2.0](reconUI/LICENSE-OpenSans.txt).
