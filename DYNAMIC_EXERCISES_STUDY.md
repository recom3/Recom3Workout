# Dynamic, data-defined exercises in Recom3Workout

**Study only — 27 November 2026. No implementation or approved protocol change.**

## Decision

**Manual repetitions: yes, after a coordinated initial upgrade.** A generic runtime can display and execute an exercise such as `dips` without a compiled `DIPS` case, using a stable ID, a data definition and its installed manual counter. Subsequent compatible manual exercises could then be added by data, without another Jet APK. **The current APK cannot do this.** Adding a database/catalog entry alone would fail at several independent validation/model boundaries, including the return path.

**Automatic repetitions: only within a detector family already installed and physically validated for that movement.** Data can select a known implementation and bounded parameters; it cannot supply a missing recognition algorithm. An unknown exercise ID need not require an APK, but a new detector implementation does. Nothing inspected establishes that Dips can be counted accurately by the current detectors.

Android 4.1.2/API 16 is not the fundamental barrier: the application already uses small Java models, lists/maps, bounded JSON, local files, Canvas and Recon OBJECT. The barriers are the closed exercise model, fixed contracts and measurement/detection assumptions. This is a feasibility inference from code, not a new hardware/performance result.

## 1. Actual implementation and inspected baseline

The repository has two materially different source states:

| Reference | State inspected | Relevance |
| --- | --- | --- |
| Recom3Workout `main`, `ef7d44a2008334c6757266ffef90b73b173a598f` | Published source for 0.1.12 / code 13 | Local workouts and outgoing SESSION sync; no incoming template receiver. |
| Recom3Workout [PR #3][jet-pr], `9e15d64e35a32f15dc87133243cb062607a0247d` | Open, not merged at inspection; local implementation identifies itself as 0.1.13 / code 14 | Incoming templates, per-set goals, review/approval and the code references below. It is not silently treated as merged `main`. |
| Snow3 `master`, `1be8791c7d4df7ea5de22107acbc86a610e32529` | Read-only | Current OBJECT template sender and SESSION receiver. |
| Recom3 API `master`, `dab78d07ea1d39052a9c81f816d7d019cc8a7128` | Read-only | Current template/session documentation, validators and database constraints. |

This document can be reviewed independently as a **one-file documentation PR against main**. Its discussion of the newer Jet implementation is pinned to PR #3. No Java, SQL, API, Web, Snow3, iOS, APK, version number or deployment changes are part of this study. No production requests, installation, sensor experiment or Dips validation was performed.

The required contracts were read directly: [Snow3 WORKOUT_TEMPLATE_SYNC.md][phone-contract], [API workout-template.md][api-template-doc] and [API workout-sessions.md][api-session-doc]. Source was also checked because documentation includes historical statements about unfinished integrations. Runtime behavior below follows the inspected implementation rather than assuming those older statements are current.

Phone-side implementation findings refer to Android ICE3/Snow3. The iOS implementation was not audited here; parity must be checked separately before advertising generic-exercise support on iOS. It cannot be inferred from the shared OBJECT transport.

The Jet build declares minimum and target SDK 16 and uses Java 7 ([app/build.gradle:6–20][j-build]). Its [manifest:3–5][j-manifest] declares accelerometer and gyroscope optional, and [WorkoutActivity.stream:96–115][j-sensors] handles missing sensor objects. However, the current normal Start path still requires a live accelerometer. Manual support therefore needs the controller change described below, even though installation itself does not require either sensor.

### Code map: what is variable and what is closed

Line numbers in Jet references refer to commit `9e15d64`, not an evolving branch.

| Area | Concrete class/method/lines | Actual limitation or reusable part |
| --- | --- | --- |
| Exercise identity | [`Exercise`:3–23][j-exercise] | An enum containing exactly PULL_UP, SQUAT and PUSH_UP. It owns labels, direction, preparation/filter settings and defaults. No runtime exercise registry exists. |
| Profiles | [`Profile.Setting`:6–14, constructor:32–36, change:47–54][j-profile] | Typed, bounded threshold controls are reusable ideas, but profiles require an enum and its compiled defaults. Settings model repetition targets, not durations. |
| Session model | [`WorkoutSession`:18–40, create:59–68, plan/target/resize:92–126][j-session] | Completed results carry an `Exercise`; arrays use enum cardinality and ordinal indexes. Per-set goals support 1–20 sets and 1–100 reps in PR #3, but only for compiled exercises. The result list being variable does not make exercise identity dynamic. |
| Completion/navigation | [`WorkoutSession.complete/nextExercise`:192–205][j-completion] | Iterate all enum values, including exercises absent from a hypothetical smaller routine. Generic completion must instead iterate the session's ordered entries. |
| Activity/profile selection | [`WorkoutActivity.readProfile/loadExercise/selectSet`:118–159][j-selection] | Reads preference keys using enum names, constructs `Profile`, cycles `Exercise.values()`, always creates a MotionEngine. The loop length is variable only at compile time. |
| Measurement/control | [`onSensorChanged/complete`:197–221][j-events], [`toggleWorkout`:321–344][j-toggle], [`correctCount/finishSet`:434–451][j-manual] | One motion counter supplies reps. Manual add/subtract is supported, but is a correction to that counter, not a general manual exercise mode. Normal Start waits for a live accelerometer. No timed-set engine exists. |
| Routine creation/approval | [`freshSession/startNewWorkout/acceptRoutine`:501–567][j-start] | Snapshot creation and explicit consent are reusable. They ultimately resolve fixed Exercise/Profile objects. Approval currently covers template bytes, not a separate dictionary. |
| HUD | [`WorkoutHud`:30, workout/setup:41–75, preview:87–99, summary:102–115][j-hud] | Canvas style and scrolling menu can stay. Preview explicitly uses three enum values and `/3`; summary has six text rows with fixed status rows. More exercises would overwrite status rows and eventually exceed the array, rather than automatically paginate. |
| Preview controls | [`WorkoutActivity`:304–317][j-preview-input] | Exercise page navigation explicitly wraps modulo 3. This is separate from the fixed model itself. |
| Template schema | [`WorkoutTemplate.parse`:20–47][j-template] | Requires exactly three exercises with fixed IDs/names/order and exact member sets; builds `new int[3][]` indexed by enum. `measurement`, `description` and `detection` are unexpected fields. |
| Template parser | [`TemplateJson`:16–21, 37–70, 124–130][j-template-json] | Bounded UTF-8/depth/duplicates are useful. Its global positive-integer and array/member limits are specific to this template grammar; it is not a ready-made parser policy for generic measurements/results. |
| OBJECT receipt | [`TemplateService.receive`:14–31][j-template-service], [`TemplateReceiver`:18–64][j-template-receiver] | Existing transport/worker can be reused. Schema validation calls the fixed template parser before durable storage/ACK. A valid envelope/hash cannot make Dips acceptable. |
| Template persistence | [`TemplateStore`:11–44, 66–76][j-template-store] | Atomic replacement/readback and revision/context policy are reusable. Reads validate the fixed template again; there is one active template slot and no independent catalog cache. |
| Session persistence/CSV | [`SessionStore`:22–55][j-session-store], [`WorkoutSession.csv/read`:208–284][j-csv] | Existing files/backups/export can be preserved, but CSV identity is `exercise.name()` and decoding uses `Exercise.valueOf` at lines 265 and 270. Unknown IDs cannot recover. PR #3's numbered PLAN rows solve per-set goals, not dynamic identity. |
| Results JSON | [`WorkoutJson`:10–25, 35–66][j-json] | Iterates the enum and maps only Squats/Push-ups explicitly; the remaining enum case maps to Pull-ups. It emits repetition-specific fields and all three exercises. An arbitrary ID must not be forced into that default branch. |
| Diagnostics | [`CsvRecording`:22–47, 51–74][j-recording] | Uses enum names and fixed Profile settings, plus a MotionEngine for every record. Sensor recording is useful for future calibration but is not an exercise dictionary or timed/manual measurement log. |
| Outbox/recovery | [`SyncQueue`:17–40, 72–95][j-outbox], [`SyncRecovery`:10–28][j-recovery] | UUID/digest/state/atomic retention are useful and the stored JSON is largely opaque. However enqueue calls the fixed WorkoutJson encoder; recovery parses fixed CSV/session models. Bounds remain 20,000 JSON bytes. |
| Wire framing | [`WorkoutWire`:9–45, 62–90][j-wire] | Big-endian envelope, digest and 25,600-byte frame bound do not depend on an exercise ID. They can carry future data contracts, but are not themselves capability negotiation. |

The original `main` is even less capable: its session goals are uniform per exercise and bounded to 99. PR #3 adds per-set targets/100 on the Jet but **does not add N exercises or unknown exercise IDs**.

## 2. What the conceptual Dips object can do today

The proposed object is useful as a design example, not a valid current TEMPLATE or SESSION contract.

| Required behavior | Current code | After one generic-runtime upgrade |
| --- | --- | --- |
| Interpret an unknown `exerciseId` | No: fixed template validation and enum resolution reject it. A standalone exercise object also lacks the required routine envelope. | Yes for an agreed definition schema, stable ID and supported measurement/capture mode. |
| Add it to HUD navigation | No runtime registration or catalog-driven exercise list. | Iterate the routine's ordered entries, with paginated preview/summary. |
| Show its sets and targets | Per-set **values** work for the three known exercises in PR #3; not Dips. | Reuse set-goal/display logic after removing the enum dependency. |
| Count manually | Known exercises can use add/subtract; unknown identity and a manual-only engine are missing. | Built-in manual counter with correction, pause/reset and explicit set completion. Sensors need not run. |
| Persist/recover results | Not for Dips: enum-backed model and CSV decoder fail. | Stable-ID snapshots and a versioned reader/exporter. |
| Send through existing OBJECT framing | The envelope can carry bytes; the current application contract cannot carry this exercise successfully. | Reuse framing/outbox, after phone/API support is negotiated. |
| Complete ICE3/API round trip | No: both independently reject unknown IDs/schema members; database constraints also disallow them. | Requires coordinated Jet, ICE3 and API/storage contracts, not just a Jet display change. |

**Practical answer:** a Dips catalog entry cannot make the current installed application support Dips. After the generic manual path and end-to-end data contract have shipped once, later compatible manual definitions can be data-only. No per-exercise APK would then be necessary, but a definition requiring an unsupported measurement or detector would still be refused or explicitly offered in a permitted manual mode.

Dips means parallel-bar dips here; it must retain its own ID/name/results. Relabelling Push-ups or Pull-ups as Dips would couple the wrong detector and corrupt exercise identity/history. It is not an acceptable compatibility workaround.

## 3. Minimum changes for unknown manual exercises

This is a proposed boundary for future implementation, not code added by this PR.

1. **Separate identity from algorithms.** Replace enum identity in the generic path with a bounded immutable ExerciseDefinition and stable string ID. Keep compiled legacy adapters for the original exercises/detectors. Use ordered lists plus ID lookups; do not persist enum ordinals. Do not merely add another enum entry.
2. **Resolve and freeze a session.** A new routine resolves each `(catalog, exerciseId, definitionVersion)` once into an immutable session snapshot: display label, measurement, capture mode, validated detector configuration and every set target. Creation/approval must cover that resolved subset, not only the routine's bytes. An unrelated catalog addition should not invalidate approval, while a used definition/configuration change should. Current sessions, completed results and pending sends must never resolve against a mutable latest dictionary.
3. **Install a manual engine once.** Route `measurement=repetitions` plus `detection.mode=manual` to an installed counter, independent of MotionEngine. Preserve actual corrections and an explicit finish-set path; no fake sensor detections. Offer readable count entry after a set, rather than requiring a button press during every Dips movement. Record the manual provenance.
4. **Make the controller/HUD list-driven.** Navigation and completion visit only entries in the routine; preserve their order. Reuse existing Canvas, physical controls, set review and consent screens. Paginate long previews/summaries, bound UTF-8 labels, and show Manual/Timer/Auto distinctly. No sensor availability error should block a manual exercise.
5. **Persist definitions and snapshots locally.** Keep a bounded catalog cache separately from the active routine, with immutable versions/digests, atomic commits and readback. Keep used snapshots until all sessions/outbox records referencing them are retained or explicitly removed. Offline startup/recovery cannot require a phone or API lookup. An interrupted catalog/routine transaction must leave the old usable pair intact.
6. **Version results and their consumers.** Generic repetition results need arbitrary valid IDs and a definition/provenance reference; duration results need their own values/units. Change the phone/API validators and database representation alongside the Jet. Existing SESSION schema 1 files, UUIDs, receipts, revisions and retries remain supported unchanged.

For the minimum release, require **one occurrence of each exercise ID per routine**. Allowing repeated blocks of the same exercise later needs a separate routine-entry identity; otherwise `(exerciseId, setNumber)` and `dips-1` collide. This is a deliberate MVP bound, not a reason to invent new exercise IDs for every occurrence.

This is a cross-cutting adaptation of the existing small application, not a wholesale architecture replacement. A registry, ordered routine/session model, built-in measurement dispatcher and versioned serializers are sufficient; a plugin framework or downloaded code is unnecessary.

## 4. Automatic and timed detection feasibility

### What the existing engine actually does

[`MotionEngine`:98–169][j-signal] projects all-axis acceleration onto estimated gravity, uses fresh gyro data for orientation correction, subtracts a resting baseline and filters the vertical signal. [`RepDetector.sample/complete`:35–120][j-detector] recognizes a fixed outbound/reversal/return/settling cycle with amplitude, duration and debounce constraints. This is motion counting for a selected exercise, **not** recognition of an arbitrary exercise category.

Part of the algorithm is generic, but important behavior is selected by identity:

- `Exercise.direction`, filter/preparation constants and defaults are compiled in [Exercise:4–22][j-exercise].
- Pull-up reset, preparation state, timed median-reference fallback and arming conditions branch on `Exercise.PULL_UP` in [MotionEngine:31–63, 79–83, 149–164][j-signal].
- The linked bottom-braking cycle branches on Pull-ups in [RepDetector:69–78][j-detector].
- Profile exposes trigger, neutral band, amplitude, cycle bounds and debounce; it does not expose every compiled preparation/filter/branch decision. Downloaded parameters are not currently supported at all.

| Exercise class | Data-only possibilities after initial support | What remains installed/tested code |
| --- | --- | --- |
| Manual repetitions | New ID/name, sets, targets and manual mode can be accepted without knowing the exercise semantically. | Counter, controller, persistence and compatible result path. No claim of automatic accuracy. |
| Time measured | A new timed exercise can use an installed monotonic timer and a duration target. | A timer engine and typed duration schemas must first exist. Current MIN_TIME/MAX_TIME are repetition-cycle filters, not timed sets. |
| Reuses a detector | A definition can request an allowlisted installed detector family/version. | Suitability must be established by recordings/real exercise trials; similar names or limb motions are insufficient. |
| Generic parametrizable cycle | A fixed installed FSM could accept bounded polarity, thresholds, filtering, preparation, cycle/debounce parameters with fixed units. | The FSM and allowable parameter grammar are code; unsupported parameters/versions are rejected. |
| New motion signature/algorithm | Metadata/manual mode can still be supported if explicitly allowed. | A new automatic recognizer requires a new APK implementing it, plus validation. Downloaded settings cannot manufacture that implementation. |

A future refactoring could assign compiled detector families to the existing settling-cycle and linked-braking behaviors rather than testing exercise identity. Those family IDs would be **new agreed names**, not capabilities already present in the APK.

Safe downloaded configuration would select only a known detector ID/version and validated finite scalar/enumeration parameters. Validate units, coupled ranges (neutral below trigger, minimum below maximum), amplitude, timing, sensor requirements and CPU/memory bounds. Keep device calibration overrides separately versioned so stale local settings cannot silently replace a changed definition. Label unvalidated automatic profiles as experimental. Never accept class names, Java/Dex, reflection targets, scripts, arbitrary expressions or downloadable FSM graphs as an escape hatch.

For Dips, no inspected evidence proves that the goggles' vertical head-motion signal corresponds reliably to a full dip cycle. Body movement, head/neck movement, posture, tempo, incomplete dips and incidental motion must be recorded with ground truth. Use the current diagnostic acquisition as a starting point, then measure false/missed counts across full/incomplete/fast/slow/idle traces and physical runs. **This study supplies neither Dips thresholds nor a Dips recognition algorithm.**

Time support would accumulate observed active duration using an API-16-compatible monotonic clock, with explicit pause/resume/background/interruption policy and recovery. It must not infer set duration from session wall-clock start/end, convert seconds into fake reps, or invent a duration for historical sessions.

## 5. End-to-end protocol and persistence limits

### Exact gates that currently reject N exercises

| Component | Evidence | Required future adaptation |
| --- | --- | --- |
| Jet routine receiver | [WorkoutTemplate.parse:20–45][j-template] | A separately versioned schema resolving bounded arbitrary IDs against definitions; do not relax v1 in place. |
| ICE3 routine/API parser | [Snow3 WorkoutTemplate:19–48][p-template] | Requires three ordered fixed IDs/names and exact fields. Download, owner cache, forwarding and receipts must understand the new schema/catalog references. |
| ICE3 results receiver | [Snow3 WorkoutSession:27–83][p-session], [WorkoutReceiver:39–74][p-receiver] | Requires schema 1, exactly three allowed exercises and repetition-specific fields. It will not safely store/ACK Dips as current SESSION data. |
| API template validator | [php/workout_template.php:39–89][a-template] and [template contract:184–195][api-template-doc] | Closed exercise map/order and exact fields; accept a new generic contract under explicit version negotiation. |
| API template database | [workout template SQL:15–40][a-template-sql] | CHECK fixes ID/name/order to the three exercises; current ID/name columns are case-sensitive ASCII and bounded. Dictionary labels/descriptions need an explicit safe representation/encoding. |
| API results validator | [php/workout_sessions.php:40–78][a-session] and [session contract:198–220][api-session-doc] | Exact schema 1, exactly three IDs, rep-only fields and current bounds; add a generic version rather than accepting arbitrary extras. |
| API results database | [session SQL:19–24, 28–67][a-session-sql] | CHECK fixes schema 1, exercise membership and positions 1–3; repetition target/completion columns cannot represent duration measurements. A PHP-only allowlist change is insufficient. |

The currently inspected phone **and API** still cap result `repsTarget` at **99**, while template schema 1 allows 100 and Jet PR #3 supports it. This existing mismatch must be resolved separately before claiming that a 100-target result completes the return path. A real count may exceed 99; the mismatch concerns the target fields. This study changes neither bound nor schema.

### Reuse transport; version application meaning

Keep the original Recon OBJECT envelope and its 25,600-byte maximum. Current routine JSON is bounded to 16,402 bytes on OBJECT and API input to 16,384; session JSON/outbox/API input is bounded to 20,000. These are real byte limits, not capacities that grow automatically with N. More exercises, longer labels and definition references increase payload size even when each individual set is valid.

The current messages have no general runtime-capability handshake. TEMPLATE_STORED confirms validated local template storage; SESSION_STORED confirms local phone session storage, not cloud upload. They neither advertise a detector nor prove measurement support, user approval or sensor availability. SHA-256 identifies exact bytes and is not authentication.

Future application messages/API resources need an explicitly agreed version for dictionaries, N-exercise routines, typed results and capability exchange. Illustrative new OBJECT actions such as `EXERCISE_CATALOG_V1`, `ROUTINE_V2` and `SESSION_V2` are **design placeholders, not approved or implemented protocol names**. Keep their acknowledgements distinct and correlated to request key, exact digest, schema and stored identity/revision. Do not put v2 objects into existing v1 actions and hope old parsers ignore fields.

## 6. Proposed minimum data model and negotiation

**Every JSON example in this section is a proposal for review. No server, phone or APK accepts it today.** Numeric limits for N and total sets below are illustrative candidates requiring measurement and encoded-size checks, not current advertised capabilities.

### A. Separate dictionary from a user's routine

A dictionary describes reusable exercise meaning and the installed capture functionality it needs. It contains no user series or JWT. Catalog format version, catalog revision, per-definition version and detector implementation version are distinct. A published `(catalogId, exerciseId, definitionVersion)` is immutable; a change creates a new version and digest, never changes historical meaning.

```json
{
  "schemaVersion": 1,
  "catalogId": "recom3-standard",
  "catalogRevision": 1,
  "definitions": [
    {
      "exerciseId": "dips",
      "definitionVersion": 1,
      "name": "Dips",
      "description": "Parallel-bar dips. Enter the completed repetitions manually.",
      "measurement": "repetitions",
      "detection": { "mode": "manual" },
      "requiredCapabilities": ["measurement-repetitions-v1", "capture-manual-v1"]
    }
  ]
}
```

`exerciseId`, definition version, name, measurement, detection and required capabilities are required; description is optional. Bound ID/text/array sizes, restrict IDs to a documented safe namespace, and treat display text as plain UTF-8. Do not make names, translations or list positions identifiers. No URLs/classes/scripts are needed to execute a manual definition.

A routine contains only its chosen definitions and set targets, pinned to a catalog digest/version. It is not the catalog itself:

```json
{
  "schemaVersion": 2,
  "workoutId": "example-routine",
  "revision": 1,
  "catalog": {
    "catalogId": "recom3-standard",
    "catalogRevision": 1,
    "sha256": "<SHA-256 of the exact approved catalog bytes>"
  },
  "exercises": [
    {
      "exerciseId": "dips",
      "definitionVersion": 1,
      "sets": [
        { "setId": "dips-1", "target": { "repetitions": 10 } },
        { "setId": "dips-2", "target": { "repetitions": 8 } }
      ]
    }
  ]
}
```

The hash placeholder denotes a required real digest in a future implementation; it is not a valid digest to transmit. Use an agreed exact-byte/canonicalization rule and test vectors. A timed definition would require `measurement=duration`, an installed timer capability, and a target such as `{"durationMs":30000}` instead of repetitions. Only the measurement's declared target shape is valid. Do not silently change a requested automatic mode to manual; a permitted fallback must be explicit, reviewable and recorded.

### B. A generic result version is also necessary

For example, the new result schema could preserve session UUID/UTC times while using tagged target/actual values and definition provenance:

```json
{
  "schemaVersion": 2,
  "sessionId": "00000000-0000-4000-8000-000000000001",
  "startedAt": "2026-11-27T10:00:00.000Z",
  "completedAt": "2026-11-27T10:05:00.000Z",
  "status": "completed",
  "source": "recom3-workout",
  "routineRef": { "workoutId": "example-routine", "revision": 1 },
  "catalog": {
    "catalogId": "recom3-standard",
    "catalogRevision": 1,
    "sha256": "<SHA-256 of the exact approved catalog bytes>"
  },
  "exercises": [
    {
      "exerciseId": "dips",
      "definitionVersion": 1,
      "measurement": "repetitions",
      "sets": [
        { "setId": "dips-1", "target": { "repetitions": 10 }, "actual": { "repetitions": 10 }, "captureMode": "manual", "completedAt": "2026-11-27T10:02:00.000Z", "completionReason": "MANUAL" },
        { "setId": "dips-2", "target": { "repetitions": 8 }, "actual": { "repetitions": 8 }, "captureMode": "manual", "completedAt": "2026-11-27T10:05:00.000Z", "completionReason": "MANUAL" }
      ]
    }
  ]
}
```

These fields require a new agreed backend contract. They must **not** be added to current schema 1. Duration actuals would be observed `durationMs`, not repetitions; absence of a measured value remains absence, not a fabricated zero. Manual counts are user reports, not sensor measurements. Retain target snapshots, correction provenance and the exact payload bytes for retry. Preserve account ownership and the API's revision/precondition/conflict semantics; session UUID alone does not identify an owner or order corrections.

### C. Negotiate the whole path before selecting the payload

A future capability response could resemble:

```json
{
  "schemaVersion": 1,
  "catalogSchemas": [1],
  "routineSchemas": [1, 2],
  "sessionSchemas": [1, 2],
  "measurements": ["repetitions"],
  "captureModes": ["manual"],
  "detectors": [],
  "limits": {
    "maxExercises": 8,
    "maxSetsPerExercise": 20,
    "maxTotalSets": 60,
    "maxCatalogBytes": 16384,
    "maxRoutineBytes": 16402,
    "maxSessionBytes": 20000,
    "maxObjectFrameBytes": 25600
  }
}
```

This is an example of a **manual-capable future runtime**, not a declaration about existing hardware/APKs. An automatic-capable runtime would also enumerate installed detector IDs, implementation versions, parameter-schema versions/ranges and actual required/optional sensor availability. A duration-capable runtime would advertise its timer separately. Missing capability means unsupported; do not infer it from an exercise name, app label, successful Bluetooth write or receipt for another object.

Negotiation must bind to the current connected peer/session and request, be bounded/expiring, and be re-established after reconnect. ICE3 must intersect **Jet execution**, **phone durable storage/forwarding** and **API result acceptance** capabilities. Sensor availability is runtime state; installed detector support alone does not guarantee a working accelerometer. No capability response can certify motion accuracy.

Validate total encoded catalog/routine/result sizes before claiming a plan executable. Eight exercises times twenty sets is not automatically allowed by the illustrative sixty-total-set bound; even sixty sets may exceed a new result budget depending on metadata. Benchmark peak allocations and worst-case serialized values on API 16; advertise lower verified bounds if needed. The MVP should keep each object within the existing frame cap and send only needed definitions. Larger/chunked dictionaries would need a separate agreed extension, not a silent limit increase.

### D. Durable receipt and user acceptance remain separate

Store/verify the required catalog and resolved routine atomically as a consistent pair, then acknowledge their exact identities/digests. Missing definitions, unsupported required capabilities, invalid parameters, unsafe size or failed persistence must produce **no success receipt**. Valid catalog storage alone does not mean a routine is executable.

User review should show measurement/capture mode, every exercise/set and any permitted fallback. Approval covers the resolved immutable routine/definitions/configuration. A newer catalog or routine must not alter an active session. Keep the existing local account/device handoff principle: protocol data is not proof of the wearer or phone account. Dictionary revisions are independent of the owner's routine revision; do not compare unrelated owners' revisions globally.

## 7. Compatibility, offline storage and migration

| Pairing | Safe behavior |
| --- | --- |
| Existing Jet + existing ICE3/API | Preserve the exact three-exercise v1 contracts and stored bytes. Adding Dips to a catalog has no effect on these installations. |
| Generic Jet + legacy ICE3/API | Keep embedded legacy definitions and use v1 only for a fully representable legacy workout. Unknown manual exercises may be local-only with explicit pending/unsupported sync, never falsely reported uploaded. A legacy phone cannot currently deliver Dips. |
| Legacy Jet + upgraded ICE3 | Send only a supported legacy routine if all IDs/modes/limits match. Otherwise show that an APK update is required; do not silently remove Dips or flatten goals. A timeout alone cannot distinguish an old APK from a disconnected device. |
| Generic Jet + upgraded ICE3/API | Negotiate supported versions, resolve pinned definitions, store/review them, and use the generic result contract and corresponding receipts. |

Legacy projection is possible only when all original IDs and measurement semantics match, every goal fits the receiving versions, and no exercises/sets are lost. Current `main` lacks template reception; PR #3 supports per-set v1 templates but the phone/API result target bound remains 99. Therefore neither `schemaVersion=1` nor a version label alone is a sufficient compatibility test. Never relabel Dips as an existing exercise, insert fake exercises/reps, or downgrade duration to repetitions.

Keep v1 API routes/default responses and parsers unchanged. The API template response currently has exact fields without a root schemaVersion, so merely appending a dictionary/measurement member breaks its phone parser. Introduce an explicitly versioned API resource or agreed content negotiation; its path/media type is not designed or implemented here. Database support needs a reviewed migration or parallel representation for catalog references, N ordered entries and typed actuals, not removal of a PHP allowlist alone. Existing Web editing also needs to select catalog definitions; this study changes no Web code.

On the Jet, ship embedded definitions for the three legacy exercises, then cache approved compatible definitions/routines for standalone use. Maintain versioned generic session snapshots and a versioned CSV/export interpretation beside the unchanged legacy reader. Existing CSVs use enum names; map them explicitly to stable IDs when reading into a new in-memory model, without rewriting historical files. Quote/escape untrusted labels in new exports and avoid spreadsheet-formula interpretation. Do not place dynamic IDs into files that an older `Exercise.valueOf` decoder assumes it understands.

Reuse the outbox's atomic retention, UUID and digest checks where possible, but dispatch serialization/recovery/transfer by a supported schema. Never regenerate already queued v1 JSON, hashes, timestamps or UUIDs as v2 merely to migrate. A new format must preserve pending records through restart and rollback; old software must not delete or reinterpret v2 history it cannot parse. Retain catalog/definition snapshots required by active, completed and unsent sessions; cache cleanup cannot make them unreadable offline.

The OBJECT route continues to confirm local phone storage only; API receipt/conflict handling remains a separate stage. Unsupported result versions must remain retained/retryable with an explicit status. Never claim generic Dips end-to-end support until both local receipt and the API path accept its real identity and measurement.

## 8. Incremental implementation plan, complexity and risks

These are qualitative estimates, not a delivery promise or authorization to implement.

| Phase | Scope and acceptance gate | Complexity | Main risks |
| --- | --- | --- | --- |
| 0. Agree contracts | Ratify dictionary/routine/result versions, bounds, capabilities and storage/receipt semantics across Jet, ICE3 and API. Resolve the existing 100/99 mismatch explicitly. | Medium–high | Incompatible assumptions, false confirmation, account/revision confusion. |
| 1. Generic manual Jet core | Stable IDs, ordered routines, immutable snapshots, manual engine, paged HUD, embedded legacy adapters, versioned recovery/exports. Exercise a local synthetic Dips definition without a DIPS code case. | Medium–high | Enum leakage, completion over absent exercises, accidental sensor counts, history loss. |
| 2. Manual end-to-end path | Add separately reviewed catalog/API storage, phone parsing/cache/forwarding and capability negotiation. Receive/review Dips, record real manual counts offline, resend after restart, and verify API storage by the same ID/version. | High across repositories | A permissive Jet with restrictive phone/SQL, lost-ACK duplicates, oversized objects, stale catalog references. |
| 3. Timed measurements | Install timer engine and typed duration targets/results; define pause/background/process-death semantics. | Medium runtime, high contract impact | Invented elapsed time, unit confusion, lifecycle recovery. |
| 4. Parametrized existing FSMs | Extract identity-independent installed detector families; accept only bounded supported parameters and calibrated overrides. Freeze current three exercise behavior through replay tests. | Medium–high plus physical validation | Regression of working exercises, false counts, unsupported sensors/configuration. |
| 5. New automatic Dips research | Record ground truth and incidental motion. Evaluate an existing detector family first; implement a new family/APK only if evidence requires it. | High/unknown | Head/body ambiguity and generalization across users, not merely parameter tuning. |

Before the first generic release, require tests for arbitrary-but-valid and unknown-required IDs/modes, missing definitions, duplicates, malicious UTF-8/text/parameters, N-exercise completion/order, manual corrections, per-set targets, duration units if supported, bounded pagination, exact-byte receipts, interrupted dictionary/routine commits, active-session isolation, catalog cleanup, pending queues, old histories, capability/reconnect changes and mixed old/new versions. Verify on an actual API 16 Jet: no new Java/Android API dependency, no network needed after caching, acceptable memory/GC/latency and clear physical controls.

Automatic validation must separately cover full/incomplete/idle/head-only movements, pacing, orientation, sensor absence and delivered sample rates, with repeated real recordings. Current success on the original three exercises is not validation of Dips or every exercise sharing a profile.

**Recommended first implementation target:** a bounded manual-repetition engine for N data-defined exercises, preserving the current three through adapters and shipping a compatible end-to-end version. It demonstrates that a genuinely new manual exercise can be added as data. Timers and downloaded parameters can follow through explicit capabilities. Unknown automatic recognition remains a separate algorithm-and-validation problem.

Study verification: the four JSON examples were checked for JSON syntax, reference labels were resolved, and source line anchors were checked against the pinned files. This is static document verification; no application tests, APK build or device operations were run for this documentation-only change.

---

### Pinned references

[jet-pr]: https://github.com/recom3/Recom3Workout/pull/3
[j-build]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/build.gradle#L6-L20
[j-manifest]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/AndroidManifest.xml#L3-L5
[j-sensors]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L96-L115
[j-exercise]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/Exercise.java#L3-L23
[j-profile]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/Profile.java#L6-L54
[j-session]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutSession.java#L18-L126
[j-completion]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutSession.java#L192-L205
[j-selection]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L118-L159
[j-events]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L197-L221
[j-toggle]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L321-L344
[j-manual]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L434-L451
[j-start]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L501-L567
[j-preview-input]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutActivity.java#L304-L317
[j-hud]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutHud.java#L30-L115
[j-template]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutTemplate.java#L20-L47
[j-template-json]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/TemplateJson.java#L16-L130
[j-template-service]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/TemplateService.java#L14-L31
[j-template-receiver]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/TemplateReceiver.java#L18-L64
[j-template-store]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/TemplateStore.java#L11-L76
[j-session-store]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/SessionStore.java#L22-L55
[j-csv]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutSession.java#L208-L284
[j-json]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutJson.java#L10-L66
[j-recording]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/CsvRecording.java#L22-L74
[j-outbox]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/SyncQueue.java#L17-L95
[j-recovery]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/SyncRecovery.java#L10-L28
[j-wire]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/WorkoutWire.java#L9-L90
[j-signal]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/MotionEngine.java#L31-L169
[j-detector]: https://github.com/recom3/Recom3Workout/blob/9e15d64e35a32f15dc87133243cb062607a0247d/app/src/main/java/com/recom3/workout/RepDetector.java#L35-L120
[phone-contract]: https://github.com/recom3/snow3/blob/1be8791c7d4df7ea5de22107acbc86a610e32529/WORKOUT_TEMPLATE_SYNC.md
[p-template]: https://github.com/recom3/snow3/blob/1be8791c7d4df7ea5de22107acbc86a610e32529/app/src/main/java/com/recom3/snow3/workout/WorkoutTemplate.java#L19-L48
[p-session]: https://github.com/recom3/snow3/blob/1be8791c7d4df7ea5de22107acbc86a610e32529/app/src/main/java/com/recom3/snow3/workout/WorkoutSession.java#L27-L83
[p-receiver]: https://github.com/recom3/snow3/blob/1be8791c7d4df7ea5de22107acbc86a610e32529/app/src/main/java/com/recom3/snow3/workout/WorkoutReceiver.java#L39-L74
[api-template-doc]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/docs/workout-template.md
[api-session-doc]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/docs/workout-sessions.md
[a-template]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/php/workout_template.php#L39-L89
[a-template-sql]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/db/20261123_workout_template.sql#L15-L40
[a-session]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/php/workout_sessions.php#L40-L78
[a-session-sql]: https://github.com/recom3/recom3_api_php/blob/dab78d07ea1d39052a9c81f816d7d019cc8a7128/db/20261125_workout_sessions.sql#L19-L67
