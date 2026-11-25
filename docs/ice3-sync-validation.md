# Workout ICE3 sender validation

25 November 2026 (Europe/Madrid). Version **0.1.12**, versionCode **13**, package `com.recom3.workout`.

## Completed checks

- **111 dependency-free JVM checks passed** with JDK 8 / Java 7 source and target: 47 motion, 34 workout-session, 30 sync checks. Command: `checks/run.ps1 -Jdk PATH_TO_JDK8`.
- The new checks cover a fake durable receiver success, disconnected receiver, transfer exception, process reopen/retry, chronological multiple-session delivery, wrong/missing/lost/duplicate receipts, corrected payloads, stable IDs, finite attempts, interrupted record writes, backup-only records, corrupt-entry retention, crash-window repair, unchanged internal/exported CSV bytes, and maximum supported 60-set payload size.
- The production `SyncBatch` was executed on a background test thread. The Android adapter uses the `WorkoutIce3Sync` single-thread executor and rejects calls from the Android main looper. No network or receipt wait runs in the HUD code.
- All application, generated R and bundled Recon UI Java sources compiled against the actual **Android API 16 android.jar**, with Java 7 source/target. Build SDK/Gradle/dependencies were preserved.
- Existing standalone signed APK build passed. Final manifest checked with `aapt dump xmltree`: package unchanged, min/target SDK 16, code 13, version 0.1.12, no `testOnly` or `debuggable` attribute.
- `apksigner verify --min-sdk-version 16 --verbose --print-certs` passed with v1 signing true and the existing Workout certificate. Certificate SHA-256: `3b8a65af7b06fc8e8eec80e6afcc92c33e9a3ba91dee6a0086451d1b72cf2586`.
- Additional local distribution-artifact validation passed. No Uplink synchronization is added.
- SessionStore, CsvRecording, MotionEngine, RepDetector, Exercise and Profile file hashes matched the baseline taken before this change. Sync tests independently verify identical internal and exported CSV contents before/after sync.
- JSON example decoded successfully; public documentation/security review found no account information or credentials in the new source, tests or docs. Generated artifacts and signing material remain ignored. `git diff --check` passed.

## Artifact

`build/installer/Recom3Workout-0.1.12-api16.apk`

SHA-256: `540E592E0080261FA9FAE177855FD86ECCF42BE158200E6C8D663947EEB262A6`

The APK is also the existing `app/build/outputs/apk/uplink/app-uplink.apk` output. No application code was changed after this APK was built; subsequent changes only documented the inspected iOS receiver.

## iOS inspection and acceptance limit

Read-only inspection of `recom3/Ice3iosInside`, master commit `779c398724964346066794a44a3eef60fcdb7f50`, confirmed matching big-endian framing, the 25,600-byte parser limit, partial/multiple-frame assembly and the existing per-session OBJECT output pump. Its current dispatcher handles time-sync and A-GPS, and has no workout-storage branch or durable workout ACK. Receiver extension points are specified in [the protocol handoff](ice3-sync.md).

No iOS repository was modified. No APK was installed on the Jet or a phone. No real phone transfer, iPhone local persistence, physical restart/reconnect, actual firmware dispatch of this new action or end-to-end sync is claimed. Those tests require the separately authorized iOS receiver implementation and real devices. Current ICE3 will leave these workouts pending; accepting bytes alone cannot change that state.

No Git commit, push, cloud deployment, database change or automatic publication was performed. Existing unrelated working-tree changes were preserved.
