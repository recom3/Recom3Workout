# Web routines on Recon Jet

Version 0.1.13 / code 14. Implements [Workout issue #2](https://github.com/recom3/Recom3Workout/issues/2) against the exact protocol from [Snow3 PR #8](https://github.com/recom3/snow3/pull/8), commit `a1ed0577ddd893f1d3ec671fa5e4c1c4e58728fd`. Only Recom3Workout changes.

## Receive, commit and acknowledge

The manifest registers an exported `TemplateReceiver` for `com.recom3.workout.TEMPLATE`. The stock Recon OBJECT owner delivers the established big-endian envelope in the `message` byte-array extra. The receiver uses `goAsync()` and a single bounded `WorkoutTemplateRx` worker; validation, file sync/readback and response submission run off the HUD thread. Its small queue rejects excess submissions without acknowledging them. It adds no socket, Wi-Fi connection, service, permission or SDK dependency.

Frames are bounded to 25,600 bytes, payloads to 16,402 bytes. The existing 20-byte Recon framing is unchanged: total byte count, request key, UTF-8 sender/action/info lengths and strings, remaining payload bytes. The sender must be `com.recom3.snow3`, action `com.recom3.workout.TEMPLATE`, request key positive, and info exactly 64 lowercase hexadecimal SHA-256 characters matching the original payload bytes. UTF-8 is decoded strictly. Bounded assembly supports split/coalesced frames; incomplete input expires after five seconds. Stock Recon normally assembles the Bluetooth stream before broadcasting it.

The payload root has exactly `schemaVersion`, `workoutId`, `revision`, `exercises`. Validation matches Snow3:

- Integer schema version 1; ASCII alphanumeric/hyphen workout ID of 1–100 characters.
- Integer revision 1–9007199254740991 stored in a Java `long`; strings, booleans, decimal/exponent notation and overflow fail closed.
- Exactly Squats, Push-ups, Pull-ups, in that order, using the IDs `squats`, `push-ups`, `pull-ups` and exact corresponding names.
- Exactly `exerciseId`, `name`, `sets` per exercise; 1–20 sets, each with exactly `setId`, `repsTarget`.
- Continuous IDs `<exerciseId>-1`, `<exerciseId>-2`, etc.; integer targets 1–100 individually per set.
- Malformed/oversized/truncated JSON, missing/extra members, duplicates including escaped aliases, invalid UTF-8/surrogates and trailing documents are rejected.

The complete original JSON bytes and their digest live in app-private `files/workout-template/active.template`. The record includes a format marker, digest and bounded payload length. An update is file-synced, validated before replacement, renamed with the previous committed record retained as backup, then re-read and validated in its final location before commit. Any readback/write/revision failure returns no ACK and preserves the last committed routine. Startup recovers an interrupted replacement from its validated backup. An interrupted pending file is not treated as stored. Android API 16 exposes no general public directory-fsync API; this uses file-descriptor sync, same-directory rename, a recoverable backup and readback, and does not claim power-loss guarantees beyond those primitives.

Only after that commit does the receiver submit, through the existing stock OBJECT broadcast:

```text
requestKey: original TEMPLATE key
sender:     com.recom3.workout
action:     com.recom3.workout.TEMPLATE_STORED
info:       original TEMPLATE SHA-256 (not a hash of the receipt)
data:       UTF-8 JSON, at most 512 bytes
```

```json
{"schemaVersion":1,"workoutId":"web-routine-1","revision":7,"status":"stored"}
```

The receipt uses the received ID/revision. The original payload is never reserialized for its digest. Identical retransmissions are read-verified and acknowledged with the new request key without rewriting the record. A local response submission is not proof that ICE3 received it; a lost response is handled by sending again from ICE3. Snow3 owns its socket/account/deadline checks and displays CONFIRMED only for its matching receipt.

## Routine context and changing user/device

Protocol 1 contains **no account identity**. Workout does not treat `workoutId` as proof of account ownership. It maintains one explicit local routine context:

1. With no saved routine, the first fully valid routine can establish the context.
2. Within that context, the same ID may advance to a higher revision. An older revision, a changed ID, or different bytes at the same revision is rejected. Identical ID/revision/bytes is idempotent.
3. For another ICE3 account/device, choose **Menu → Replace synced routine**, then confirm with OK. This authorizes one fully validated/committed incoming routine during the next two minutes, including a lower revision or the same ID with different contents. Resend from the intended ICE3 account within that window.
4. The previous routine is kept until replacement succeeds. Invalid messages/storage failures do not consume authorization. A successful commit consumes it. The permission expires and is not retained across process restart.

This explicit handoff starts a new local revision context; it does not compare another owner's revisions globally or require permanently increasing them. It cannot automatically detect an account switch when another account supplies the same ID and a higher revision. The paired phone/local OS remains the trust boundary, and the user must use the handoff when changing accounts. Hashes provide content integrity, not remote-account authentication. No protocol fields or pairing bypass are invented.

## Apply to a new workout

Receiving a routine never edits the current session, its UUID, goals, counts or saved results. Durable storage/CONFIRMED is separate from user approval to use it. A new, unapproved routine opens **NEW ROUTINE · REVIEW** when the HUD is paused, the menu is open or Congrats is visible; it never interrupts running detection. Left/Right changes the exercise (1/3 means the first of three exercises), Up/Down scrolls up to twenty sets, OK accepts the complete frozen snapshot and Back/Later keeps the current workout. Approval is persisted by exact payload digest; an update needs fresh approval. Menu → Review synced routine can open it again. Closing/reopening shows an unapproved saved routine again. No unapproved routine can be used by New workout. A fresh installation can show local goals until approval.

OK on the review screen starts the reviewed routine when creating a new workout, when the current workout has not started, or when it is complete. If a workout is already underway, OK approves only the next workout and leaves its goals/counts unchanged. **Menu → New workout** then creates an independent snapshot from that approved routine. A newer arrival during review cannot silently replace the snapshot being accepted. With no stored routine, the existing locally configured goals are used. If the saved routine is unreadable, New workout preserves the current session and reports that problem instead of silently flattening it. On a fresh install with no recoverable session, the app can still show local goals with a storage warning.

A synced new workout starts at Squats and uses each exercise's complete target array. For example, Squats 10/12, Push-ups 8/10 and Pull-ups 4 remain five separate set goals. Automatic progression selects the correct next target and waits for OK. Targets of 100 are supported in the model, profile, counter and HUD. Motion thresholds, filtering and repetition state machines are unchanged.

**Workout setup** now has Exercise, Set, Target reps, Total sets. Choose a set before viewing/editing its target; changing one target does not flatten the others. Adding sets copies the last existing target for the new sets. Manual corrections continue to affect actual counts. Local edits apply to the active session only; New workout reloads the saved Web routine.

After a routine is stored, a brief `New routine saved. Review it in Workout.` notice accompanies the persistent review screen; acceptance never depends on noticing a toast. When the saved payload differs from the current session's applied payload, the paused HUD shows **NEW ROUTINE** and **Menu > New workout**; the menu and Congrats also indicate the pending routine. On relaunch, the stored routine and private applied-session/hash preference restore this advisory indication. Accepting and starting the new snapshot clears it and shows a permanent **WEB** label beside the exercise (and in Congrats), identifying the session's origin even after closing/reopening the app. Local edits can still change that session's targets. The preference is advisory: losing it in an abrupt process death can show the notice again, but cannot change goals or lose results. The final Congrats screen still offers OK New workout; Back returns to the HUD for review.

## CSV and results compatibility

No CSV column or header changes. Uniform sessions retain their original byte-for-byte CSV representation. Historical CSVs remain readable and are not rewritten by template receipt.

To persist different **future** targets across app restart, new nonuniform sessions use additional numbered `PLAN` rows in the same 11-column format. The existing set-number column identifies the override; set number 0 remains the exercise's base/first-set goal. Only differing targets need override rows. Existing `SET` rows continue to snapshot each completed set's actual target and repetitions. SessionStore paths/export/retention are unchanged. Older app versions do not understand these numbered PLAN overrides: avoid downgrading during a variable-target workout.

The results JSON remains schema 1 with exactly the existing fields. The per-exercise `repsTarget` remains the first/base target; each completed set's `repsTarget` is authoritative. No `workoutId`, `templateRevision`, account ID or other new field is added to results. UUID identity, SESSION/SESSION_STORED, queue files, retries and recovery remain unchanged. Templates and receipts are independent of the results queue and of production API upload.

## Known downstream 100-target limitation

The Jet accepts, displays, counts and exports a target of 100 without clamping or changing result fields. However, Snow3 PR #8 and the inspected current master `workout/WorkoutSession.java` still validate both result `exercises[].repsTarget` and `sets[].repsTarget` in the range 1–99. That phone receiver therefore cannot accept a completed result containing a 100 target yet. The Jet retains its CSV and retryable JSON; it must not forge a 99 target or mark an unacknowledged session synced. Existing 1–99 result flows retain their contract.

A separate authorized Snow3 change must permit 100 in those existing result fields and verify downstream API acceptance; no new result field or schema version is proposed. This PR cannot claim end-to-end 100-target result upload or fully close that integration dependency, and does not modify Snow3/PHP to work around it. TEMPLATE_STORED means the complete routine is safely stored on the Jet, not that a subsequent cloud upload has happened.

## Exact Snow3 vector

The synthetic vector in `checks/workout-template-v1.json` is one line without a final newline, copied from Snow3's pinned contract:

- JSON length 379 bytes.
- SHA-256 `f7d144e1a68f253981ab4459059014ef3bed31f04bbd8457d1e903b74ef3e4ed`.
- Request key 123 yields a 506-byte frame.
- First 32 frame bytes: `000001fa0000007b00000010636f6d2e7265636f6d332e736e6f77330000001b`.

The checks execute this vector, strict rejection cases, persistence/reopen and rollback, revision/handoff rules, active-session isolation, per-set/100-rep goals, CSV round trips, result-schema regression and the production receiver/ACK broadcast path with host-only Android boundaries. Test boundaries live under `checks/host-android`; they are never APK sources. Host tests do not establish live Bluetooth, Android power-loss durability or production API acceptance.

## Install and real end-to-end acceptance

The test APK is `build/installer/Recom3Workout-0.1.13-api16.apk`, package `com.recom3.workout`, versionCode 14. It contains Java/Dex and resources, no native libraries; the code is compiled against API 16 and runs on ARMv7 Recon Jet without a native ABI dependency. Retain the existing application/signing identity and update in place:

```powershell
adb -s JET_SERIAL install -r build/installer/Recom3Workout-0.1.13-api16.apk
adb -s JET_SERIAL shell am start -n com.recom3.workout/.WorkoutActivity
```

Open the app once after installation. An Android force-stop suppresses manifest delivery until the app is opened again; leaving the app normally is different from force-stop. Do not uninstall or clear application data. The glasses' existing Recon Bluetooth connection must already work with the intended ICE3 Android phone.

1. Change and save goals in Recom3 Web, including distinct targets for successive sets.
2. In ICE3 Android, open **Trips → Workout sync → Download routine**. Check the displayed revision and individual targets. Press **Send to Recon Jet**.
3. Verify ICE3 reaches **CONFIRMED**, not merely PENDING. The Jet logs a sanitized stored/receipt-submitted event; no template bytes/account information are logged.
4. On the Jet, review the complete routine, using Left/Right for exercises and Up/Down for sets, then press OK to accept. With an in-progress workout, start **New workout** afterwards; its goals must not be replaced by receipt alone. Confirm Squats' first target, then inspect each subsequent set in Workout setup or complete them and use automatic advancement. Confirm all targets match the Web routine, including 100 if configured.
5. Send a newer routine while a set is underway; that current session must retain its original goals. Start a new workout afterwards and confirm the newer goals.
6. Leave/reopen Workout, and separately reboot the glasses, then start a new workout and verify the saved targets.
7. Repeat an unchanged send and test disconnect/retry. ICE3 must not show CONFIRMED without the receipt. Test another account's lower revision through the local replacement confirmation.
8. Complete a real routine, verify CSVs remain available, and verify SESSION → ICE3 → production API still completes through the existing phone workflow. Keep receipt confirmation separate from cloud upload confirmation.

Compilation, host tests, installation/launch and live end-to-end acceptance are reported separately. No Web/API/Snow3/iOS code is modified, and no PR is merged or release automatically published by this work.
