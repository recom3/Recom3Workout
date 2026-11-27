# Dynamic exercises and independent local calibration

Dynamic runtime introduced in **0.1.14 / code 15**; result delivery added in **0.1.15 / code 16** on `main` after PR #5. Its TEMPLATE receiver, review/consent, variable set targets and result sync are reused rather than reimplemented. This change is confined to Recom3Workout.

## What works on the Jet

`Exercise` is now an immutable data definition with stable ID, display name, optional description, definition version and repetition measurement. Its three named constants and historical-name/ordinal helpers are adapters for v1 files, preferences and transport only. Runtime sessions, completion, setup, HUD navigation and review use their own ordered list, not a Java enum or global registry. A routine may contain 1–8 distinct exercises, 1–20 sets per exercise, at most 60 planned sets in total and individual targets of 1–100 repetitions. The three built-in exercises remain the fallback without a synced routine.

On first receipt of a new ID, `ProfileStore` loads its existing calibration if present. Otherwise it deep-copies **the user's currently saved Pull-ups profile**, including all fifteen settings: trigger, release, amplitude, minimum/maximum cycle, debounce, default target/sets, direction, filter, preparation window/variance/timeout, detector mode and sensor-reset policy. The new profile is marked experimental and saved atomically under its own stable ID. Its settings never share an array, object or preference namespace with Pull-ups. Later downloads and Pull-up edits do not overwrite it.

Legacy preference keys are read once when creating each original exercise's profile file; the original preferences are retained. Unreadable saved calibration is reported and never overwritten with defaults. The HUD permits only manual capture if a legacy session's calibration is unavailable; starting a new downloaded routine fails rather than silently accepting missing local calibration. File transactions use descriptor sync and recoverable backups; no stronger power-loss guarantee than the existing API 16 primitives is claimed.

`MotionEngine` and `RepDetector` now select behavior from the profile. Installed modes are **Manual**, **Settling cycle** and **Linked braking**. Preparation, filtering, direction and sensor reset are editable on **Menu → Calibration**. Existing defaults and algorithms for Squats, Push-ups and Pull-ups are preserved. New exercises initially inherit the saved Pull-ups mode; the user may switch their own profile to Manual. Manual mode ignores sensor events and does not require a live accelerometer. Add/subtract/reset and explicit Next set still work; reaching the entered target can complete the set. No automatic calibration or calibration upload is added.

The HUD marks cloned profiles **EXP** and calibration **UNVALIDATED**. Review displays the actual number of exercises and scrolls each exercise's sets. Congrats uses three exercise rows per page; Left/Right or Up/Down changes pages without overwriting CSV/sync status. Long text is clipped with an ellipsis. Receiving a newer routine never changes an already created session's ordered definitions, targets, counts or UUID. Explicit review/acceptance remains required before creating a new session from it.

**Cloning a Pull-up profile does not demonstrate that Dips are detected correctly.** This is experimental reuse of installed code, not exercise classification. Real ground-truth recordings and physical validation are required for every new automatic exercise/profile. Synthetic parity is reported separately below.

## Experimental Jet import contract

This is the bounded **Jet import contract**, separate from result delivery and from Recom3 API endpoints. No Snow3, iOS, Web, PHP or database changes are included here. Receiver support for results must be negotiated independently; accepting ROUTINE_V2 never implies support for SESSION schema 2.

A new OBJECT action `com.recom3.workout.ROUTINE_V2` carries a single UTF-8 JSON bundle with exactly `schemaVersion`, `catalog` and `routine`. It uses the existing envelope, positive request key, sender `com.recom3.snow3`, exact-byte SHA-256 in info, 25,600-byte maximum frame and **16,402-byte maximum bundle**. The existing v1 TEMPLATE parser remains strict and does not accept this object. Malformed UTF-8, duplicate fields, unknown fields/modes, missing references, duplicate IDs, unsafe IDs/text and unsupported versions/measurements are rejected.

```json
{
  "schemaVersion": 2,
  "catalog": {
    "schemaVersion": 1,
    "catalogId": "example-catalog",
    "revision": 1,
    "definitions": [
      {
        "exerciseId": "dips",
        "name": "Dips",
        "description": "Parallel-bar dips; experimental local calibration.",
        "measurement": "repetitions",
        "definitionVersion": 1,
        "detection": { "mode": "local-profile-v1" }
      }
    ]
  },
  "routine": {
    "workoutId": "example-routine",
    "revision": 1,
    "catalogId": "example-catalog",
    "catalogRevision": 1,
    "exercises": [
      {
        "exerciseId": "dips",
        "definitionVersion": 1,
        "sets": [
          { "setId": "dips-1", "repsTarget": 10 },
          { "setId": "dips-2", "repsTarget": 8 }
        ]
      }
    ]
  }
}
```

Catalog format version, catalog revision, exercise definition version and routine revision are separate. IDs use `[a-z][a-z0-9-]{0,31}`; names have at most 32 UTF-16 code units, descriptions at most 160, with no control characters or invalid surrogates. Description is optional. Names and positions are not identifiers. Only repetition measurement and the installed `local-profile-v1` selector are currently accepted. Profiles and detector code are not downloaded. The parser remains deliberately smaller than a general JSON configuration language; executable code, scripts, reflection/class names and arbitrary detector graphs are not accepted.

Catalog and routine remain separate models inside one transactional snapshot so an interrupted transfer cannot commit unresolved references. The catalog supports up to eight definitions. Each routine entry references an exact definition version, appears once and has contiguous set IDs `<exerciseId>-1`, etc. Unused definitions may be cached, but the full bundle must fit the byte limit. Immutable definition versions are retained separately in `files/exercise-definitions/`; changing an existing ID/version's metadata is rejected. A changed definition must increment `definitionVersion`. Local calibration remains keyed by exercise ID and is preserved even when the display definition changes.

The complete original bundle is stored with a distinct local format marker in the existing atomic template slot. All definitions and per-ID profiles must be durable/reopenable before committing and acknowledging it. The receipt action is `com.recom3.workout.ROUTINE_V2_STORED`, with the original request key/info digest and exactly:

```json
{"schemaVersion":2,"workoutId":"example-routine","revision":1,"status":"stored"}
```

V1 still returns TEMPLATE_STORED with schema 1. Both flows retain the same explicit local context/revision/handoff policy and exact-payload approval gate. Receipt means local storage, not user consent, detection accuracy or cloud sync. The hash covers the entire resolved definition/routine bundle. Retransmission is idempotent; stale/replaced contexts require the existing local confirmation. The current template includes the dictionary for offline use; current and historical session CSVs additionally carry their own definitions/calibrations, so they do not resolve against a mutable latest catalog. No Internet or connected phone is needed after storage.

## Sessions, CSV and compatibility

V1 workouts retain their existing eleven-column CSV representation, UUIDs, JSON schema, queue format, SESSION/SESSION_STORED and retry/receipt behavior. Historical uniform and numbered-PLAN CSVs remain readable without rewriting the originals. Calibration defaults added in this version are used when replaying old sensor traces lacking the new fields.

Dynamic sessions use an independent `RECOM3_WORKOUT_CSV_V2` format. It contains ordered PLAN rows with per-set targets and local profile snapshots, followed by actual SET rows, and an END SHA-256 footer over the preceding UTF-8 bytes. Definition names/descriptions are RFC 4180 escaped; spreadsheet-active leading characters are prefixed reversibly. Results retain set-specific calibration snapshots, completion timestamps/reasons and their existing session UUID. Corrections retain the original set calibration. Loading validates the footer and never consults a current catalog or overwrites old files. SessionStore's active pointer, archives and external export paths are reused. A downgrade cannot read the new CSV/template/profile formats; do not downgrade during an active dynamic workout. Original v1 archives remain intact.

Dynamic results use **schema 2**, generated by the unchanged `DynamicWorkoutJson`, with the real stable ID, definition version, name, repetition values, completion provenance, exact routine bundle digest and profile-format version/snapshot. The exact encoder bytes are the ICE3 result contract and are never projected into v1. Phone-to-server upload remains independent. They are retained in `files/workout-results-v2/`; startup recovery fills this separate outbox from the self-contained CSV archives. The old v1 encoder/queue refuses a dynamic session. Existing queued v1 JSON bytes, digests, identities and confirmation state are not migrated or regenerated as v2.

Version 0.1.15 wires this same outbox and its `<UUID>.json` / `<UUID>.receipt` files to the existing Recon OBJECT broadcasts. It probes exact receiver capabilities on every result attempt and requires an exact request/sender/digest/UUID receipt within a bounded deadline. The HUD distinguishes local, pending, sending, stored on the ICE3 phone, incompatible, timeout/error and oversized results. **Menu → Retry results** sends saved results without another workout. Corrections keep the UUID, save a new digest and invalidate earlier queued/in-flight attempts before editing the CSV. Results and CSVs remain after receipts. See [the exact negotiation, recovery and validation contract](results-v2-sync.md). No cloud success is inferred.

V2 JSON may be retained locally up to 65,536 bytes; sending is additionally refused above the existing 20,000-byte result transport budget. N/total-set limits do not imply that every result can be sent within that budget. Oversized results remain local and unconfirmed. Chunking or increased wire bounds require a separate negotiated change. The existing v1 downstream 99-target limitation described in `template-sync.md` is unchanged; goals of 100 are never forged as 99.

## Validation and remaining work

Run the dependency-free Java 7 host checks with the existing `checks/run.ps1` workflow. The added tests cover full calibration copying, independence, preference migration, restart/backup/failure recovery, unknown names/IDs, strict bundle validation, exact receipts, definition immutability, N-exercise completion, variable set targets, CSV/UUID recovery, independent v2 output and capability/receipt gating. Actual WorkoutHud and TemplateReceiver code are exercised with **host-only** Android/drawing boundaries; these test classes are never packaged in the APK.

All original motion, session, TEMPLATE/consent and SESSION sync checks remain in the suite. A synthetic raw trace verifies that an unknown ID with a cloned Pull-up profile produces the same signal, state transitions and two counts sample-for-sample as the original profile. This is regression evidence about installed code, not physical validation of Dips. API 16 compilation, APK metadata/signature inspection and host checks are reported separately in the PR. No device installation, live Bluetooth/cloud test, merge or deployment is performed by this task.

Historical runtime validation for 0.1.14 (delivery validation is recorded in [results-v2-sync.md](results-v2-sync.md)):

- **283 host checks passed:** 47 motion, 34 historical/session, 30 v1 result sync, 78 v1 template/consent, 62 dynamic model/calibration/storage, 13 actual-HUD checks and 19 production-broadcast checks.
- All application, bundled Recon UI and generated resource Java sources compiled against the actual API 16 Android SDK using Java 7 source/target with JDK 8. The APK contains Dex/resources and no native libraries, so it adds no ABI-specific dependency for the original ARMv7 Jet. This does not replace a physical runtime test.
- Final APK identifies `com.recom3.workout`, version 0.1.14 / code 15, minimum/target SDK 16, no testOnly/debuggable manifest attributes. API-16 v1 signature verification passed, and the signing certificate matches the previous 0.1.13 APK.
- Local test APK: `build/installer/Recom3Workout-0.1.14-api16.apk`, **305,169 bytes**, SHA-256 `a98413c259e7380178df96e5c869067bf745408fa46b27a6545ff3d20926d130`. Generated binaries and local validation tooling are excluded from Git/PR.

Remaining work: coordinate live phone/Jet delivery and independent server verification; test API 16 resource/lifecycle behavior; collect actual Dips recordings before claiming recognition accuracy. Timed measurements, automatic tuning and calibration upload remain out of scope.
