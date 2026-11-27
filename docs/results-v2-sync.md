# Dynamic result delivery to ICE3

Workout **0.1.15 / code 16**, Android 4.1.2 / API 16. This connects the existing
`files/workout-results-v2/` outbox to the stock Recon OBJECT broadcasts. It changes
neither the dynamic encoder nor v1 SESSION/TEMPLATE and ROUTINE_V2 reception.
No new socket, service, dependency, account flow or server access is introduced.

## Negotiation and wire contract

Register the temporary result receiver before submitting any frame on
`com.reconinstruments.mobilesdk.hudconnectivity.channel.object`, using the existing
`HUDConnectivityMessage` extra. Responses use the existing `message` extra.
Every result attempt first submits:

- Sender `com.recom3.workout`, action `com.recom3.workout.CAPABILITIES`.
- A fresh positive request key, empty info and zero data bytes.
- At most three seconds for a matching response, within the total attempt budget.

Only `com.recom3.workout.CAPABILITIES_RESULT` from sender `com.recom3.snow3`, with
that request, empty info and these **exact UTF-8 bytes**, is accepted:

```json
{"schemaVersion":1,"sessionSchemas":[1,2],"maxSessionBytes":20000,"maxFrameBytes":25600}
```

Exact-byte comparison validates all fields, structure, types and budgets; unknown,
duplicate, reordered or whitespace-modified representations are not this exact
handoff contract. Bluetooth connection state, APK version and routine receipts do
not advertise result support. Absent/invalid capabilities leave v2 bytes local;
there is no schema-1 fallback.

The result uses sender `com.recom3.workout`, action `com.recom3.workout.SESSION`,
a different fresh positive request, original saved `DynamicWorkoutJson` bytes
(schemaVersion 2), and their exact lowercase SHA-256 in info. Retry reads the
persisted JSON, without truncating, fragmenting or reserializing it. JSON is at
most **20,000 bytes**; the full existing envelope is at most **25,600 bytes**.
Locally valid JSON up to the encoder's existing 65,536-byte limit stays in the
same outbox when it exceeds the transport budget.

Only `com.recom3.workout.SESSION_STORED` from `com.recom3.snow3`, with the current
result request, exact digest in info and **only the UTF-8 UUID** in data, confirms
storage. JSON receipts, account metadata, malformed UTF-8, wrong actions/senders,
wrong requests/digests/UUIDs and late callbacks are rejected. The entire
capability/result exchange has a maximum ten-second budget; each batch attempts
at most twenty results within thirty seconds. Old ICE3 times out during probing.

## Connection, revision and recovery

One process worker serializes v1 and v2 result operations away from the HUD.
A process observer tracks the stock `HUD_STATE_CHANGED` (`state`: 0/1/2), Bluetooth
ACL and adapter changes. Any observed connection event changes the epoch and
invalidates the current exchange. No capability cache survives an attempt; a
reconnected peer must answer a new probe. Replies must match the current stage,
request, observable connection epoch and monotonic deadline. The stock OBJECT
broadcast does not expose a socket token or authenticated peer identity: these
checks use its available connection events and fresh request correlation, not a
claim of cryptographic peer authentication. Phone-side socket scoping is part of
the ICE3 receiver contract.

Before a CSV edit, the Activity invalidates its earlier result revision. Already
queued old snapshots cannot be submitted after a newer edit; in-flight old ACKs
cannot confirm changed bytes. New bytes keep the UUID and replace only that
existing JSON file. Receipt commit rechecks the current digest under the outbox
file lock, writes/syncs the same existing digest receipt and reads it back. No
result, receipt or CSV is deleted after delivery.

Startup reconciles self-contained CSV archives with the existing queue; equal
bytes preserve the receipt. A reset/reopened incomplete CSV suppresses delivery
of its formerly completed queued revision. Backup-only JSON/receipts recover
through the existing atomic-file primitives. Unreadable entries are retained;
healthy entries and the v1 queue can proceed. Descriptor sync and recoverable
backups retain the existing API 16 durability limits; host tests do not prove
Android crash or power-loss durability.

## Existing controls and meaning

The existing summary and **Menu → Retry results** show saved locally, pending,
sending, stored in ICE3 on the phone, receiver incompatible/no valid v2 reply,
timeout/error or too large. Retry covers saved results, including earlier
workouts, without creating or completing a workout. A successful local receipt
survives Activity closure and process restart. Transmission does not remove CSVs.

**Stored in ICE3 means stored locally on the phone.** It is not a server receipt,
HTTP success or confirmation that the signed-in phone account can upload it.
Accounts and cloud ownership are handled by ICE3; they are not fields in the Jet
receipt contract. Routine review/acceptance, session goals, calibration and motion
recognition remain unchanged.

## Validation and device handoff

`checks/run.ps1` runs **337 host checks**: 47 motion, 34 historical/session,
30 v1 sync, 78 template/consent, 62 dynamic model/storage, 54 production result
transport/worker, 13 HUD and 19 production routine-broadcast checks. Transport and
worker checks execute production code, replacing only Android/phone host
boundaries. The encoder fixture includes four exercises/Dips, distinct targets,
a target of 100 and zero actual reps. Checks cover lost/invalid/late ACKs,
connection changes, corrections in flight, startup/reopen, storage/transport
failure, old receivers, retained CSVs and exact size boundaries (20,000/20,001
JSON and 25,600 full frame). The padded exact-byte boundary fixture tests the
transport limit separately from the real encoder fixture.

The current real encoder output and OBJECT frames also passed the unchanged ICE3
validator, receiver and store, compiled read-only from its coordinated checkout.
That check substitutes only host log/HTTP boundaries and filesystem rename /
directory fsync operations. Original-byte readback and exact capability/receipt
fields passed; Android crash durability and actual Bluetooth are not established.
The local cross-contract check and APK evidence are reported with the change. No foreign
repository is modified by these checks.

All app/Recon UI/resource sources compile with Java 7 source/target against the
actual API 16 SDK. The APK retains `com.recom3.workout`, min/target 16 and the
0.1.14 certificate, verifies its API-16 v1 signature and has no debuggable,
testOnly or native-library requirement. Generated binaries and private/local
validation tooling are excluded from Git. Local validated APK:
`build/installer/Recom3Workout-0.1.15-api16.apk`, **311,380 bytes**, SHA-256
`f89b6f8ed526fde9979d84b80c84e9e3b6f56db8532bee545471b4d34cdc4a94`.

No device install, workout manipulation, production upload or live Bluetooth
receipt is claimed. The existing saved Dips result on the Jet must be preserved
for a separately authorized device test coordinated with ICE3. Do not start a
replacement workout, alter its UUID/counts or clear app data. A live receipt and
subsequent independent phone/server status verification remain acceptance work.
