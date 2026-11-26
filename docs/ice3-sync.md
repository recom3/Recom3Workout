# Workout results to ICE3: phase 1

For the current incoming Web routine receiver, see [template-sync.md](template-sync.md). The SESSION/SESSION_STORED contract below remains unchanged. Snow3's Android receiver/upload path has since been implemented separately; the original iOS-only handoff notes below describe that initial inspection, not a new limitation on Android session sync.

Version 0.1.12 / code 13. Only Recom3Workout is changed. No phone application, server, web catalog, firmware or Uplink integration is deployed by this change.

## What is implemented and what remains

Workout creates an additional versioned JSON object for a completed routine, retains it in an internal persistent queue, and submits it over the existing Recon Bluetooth OBJECT path. It retries on application startup and after completing a workout. All queue I/O, archive scanning, encoding, submission and receipt waiting run on the single `WorkoutIce3Sync` executor. The HUD never waits for Bluetooth.

The phone source was inspected in **ICE3/Snow3 for Android**, including its Capture/Vision checkout, and read-only in [ICE3 iOS](https://github.com/recom3/Ice3iosInside/tree/779c398724964346066794a44a3eef60fcdb7f50), master commit `779c398724964346066794a44a3eef60fcdb7f50`. The iOS channel/parser already support this Recon envelope, but its `ReconAccessoryManager.handle(_:in:)` handles time-sync and A-GPS requests, not workout persistence or a stored-session acknowledgement. Consequently, this delivery is the reusable glasses sender and a receiver contract, **not a validated end-to-end iOS integration**. Until the receiver extension below is implemented, completed sessions remain pending/failed, including when the existing transport accepts their bytes. No iOS file was modified.

Inspected local source patterns:

- Recom3Workout `WorkoutSession`, `SessionStore`, `WorkoutActivity` and `WorkoutHud`: UUID session identity, immutable completed-set records, atomic internal CSV archive, external export and final summary.
- Recom3JetVision `VisionActivity.send` and `visionprotocol/ReconMessage`: stock `com.reconinstruments.mobilesdk.hudconnectivity.channel.object` broadcast, `HUDConnectivityMessage` extra, big-endian Recon envelope and returned `message` extra.
- ICE3/Snow3 `HUDConnectivityService`, `BTObjectConnector`, `HUDConnectivityMessage`, `BTTransportManager` and Vision controller: OBJECT queue, 25,600-byte broadcast limit, existing connection ownership and application-level result messages. A write/queue callback is not durable receiver storage.
- ICE3 iOS [ReconCommunication.swift](https://github.com/recom3/Ice3iosInside/blob/779c398724964346066794a44a3eef60fcdb7f50/Shared/ReconCommunication.swift): `ReconFrameParser.maximumLength = 25_600`, retained partial input, multiple frames per read, UTF-8 sender/intent/info/payload, and the matching `ReconLegacyFrameEncoder`.
- ICE3 iOS [ReconAccessoryManager.swift](https://github.com/recom3/Ice3iosInside/blob/779c398724964346066794a44a3eef60fcdb7f50/Shared/ReconAccessoryManager.swift): one retained `EASession` per protocol, `parser.append` feeding `handle(_:in:)`, existing stream/backpressure output pump, cleanup on channel loss, and no workout branch or durable workout receipt.
- ICE3 iOS `ReconBuddyMessage.swift`: reuses OBJECT framing, but its delivery completion explicitly means a local complete write, not receiver acknowledgement. It cannot substitute for a workout storage receipt.

No direct Bluetooth socket, Wi-Fi protocol, external library, new permission, Internet access or phone discovery flow is added. Pairing, discovery and connection remain the responsibility of the stock Recon service and ICE3. iOS source framing limits match; actual Jet/MFi availability, reconnection and receiver behavior still require hardware validation.

## Session JSON, schemaVersion 1

`docs/workout-session-v1.example.json` is a synthetic, valid example. The sender emits deterministic compact UTF-8 JSON. This is an application payload inside the original Recon envelope, not new Bluetooth framing.

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Integer `1`; reject unsupported versions rather than guessing. |
| `sessionId` | Existing `WorkoutSession.id` UUID; never regenerated on retry. |
| `startedAt` | Stored start time, UTC ISO 8601 with milliseconds. |
| `completedAt` | Latest stored set-completion timestamp, in the same UTC format. |
| `status` | `completed`; only routines whose three exercise set goals have ended are queued. |
| `source` | `recom3-workout`. |
| `exercises[].exerciseId` | Stable IDs `pull-ups`, `squats`, `push-ups`. Array order is fixed and does not imply the user's exercise order. |
| `exercises[].repsTarget`, `setsTarget` | Current configured goals at completion. |
| `sets[].setId`, `setNumber` | Exercise ID plus one-based set number, and its numeric value. `setId` is scoped to `sessionId`. |
| `sets[].repsTarget`, `setsTargetAtCompletion` | Goal snapshots already recorded for that set. |
| `sets[].repsCompleted` | Actual stored count, including manual corrections and partial sets. |
| `sets[].completedAt` | Recorded completion time; not an estimated motion duration. |
| `sets[].completionReason` | Existing `TARGET`, `MANUAL` or `CORRECTED` reason. |

`workoutId` and `templateRevision` are deliberately omitted from results to preserve the agreed SESSION schema 1/API contract, even when a Web template is used locally. No fields are added without a separately agreed results contract. No heart rate, load, calories, set start time, user identity, phone address or credentials are invented. UTC formatting does not correct an inaccurate glasses clock.

The existing app permits explicitly ending partial sets. `completed` describes its routine completion semantics, not a claim that every target was achieved. Actual counts and completion reasons remain authoritative. If a completed routine is corrected and becomes complete again, its **same** session ID carries a changed payload digest and is queued for an upsert. The receiver must not insert a second session for it.

## Recon OBJECT submission

The glasses broadcast:

```text
Intent action: com.reconinstruments.mobilesdk.hudconnectivity.channel.object
byte[] extra:  HUDConnectivityMessage
```

The byte array is the established Recon big-endian frame:

```text
int32 totalLengthIncludingThisField
int32 requestKey
int32 senderUtf8Length    + sender UTF-8
int32 actionUtf8Length    + action UTF-8
int32 infoUtf8Length      + info UTF-8
remaining bytes          = data
```

Outbound fields:

```text
sender     = com.recom3.workout
action     = com.recom3.workout.SESSION
info       = lowercase SHA-256 hex of the exact JSON UTF-8 data bytes
data       = session JSON
requestKey = fresh nonnegative 31-bit random correlation value per attempt
```

The persisted `sessionId` is independent of `requestKey`. A retried unchanged payload has the same session ID and digest. The sender limits JSON to 20,000 bytes and the whole envelope to 25,600 bytes. A maximum supported 60-set session fits this limit in the automated check. Oversized future payloads are retained via their source CSV and reported pending, rather than split by a new protocol or silently truncated.

OBJECT is a framed byte stream: the receiver must assemble the existing length-prefixed frame across partial reads, and handle multiple frames in a read. This task adds no application fragmentation. Both the local Android sources and the inspected iOS `ReconFrameParser` use the 25,600-byte limit. The iOS source already assembles partial and multiple frames; live interoperability is still untested.

## Durable storage acknowledgement

ICE3 must send this response on **the same existing OBJECT mechanism** only after validating and safely storing the received session:

```text
requestKey = exactly the requestKey of this attempt
sender     = ICE3's existing sender identity
action     = com.recom3.workout.SESSION_STORED
info       = lowercase SHA-256 hex of the exact received JSON bytes
data       = sessionId as UTF-8 (UUID only, no JSON, whitespace or newline)
```

On the Jet, the stock Recon service delivers the complete envelope as a `message` byte-array extra on the `com.recom3.workout.SESSION_STORED` intent. Workout registers a temporary receiver before submission and removes it after a verified receipt or timeout.

All four conditions must match: stored action, current request key, session ID, payload SHA-256. A transport success, bytes written, connection event, cloud response, unrelated ACK, malformed frame or receipt for an older corrected payload cannot mark the entry synced. SHA-256 identifies the exact content; it is **not** an authentication signature. The integration retains the stock OS/Bluetooth trust model and does not authenticate other local apps broadcasting intents. Do not describe it as a cryptographically authenticated receipt.

ICE3 receiver requirements:

1. Register the new session action with its existing OBJECT dispatcher; decode the complete envelope without losing its `info` bytes.
2. Bound the frame/body size, verify the digest and parse/validate schema 1, UUID, exercise IDs, set IDs, numeric counts and timestamps.
3. Persist by `sessionId` using an atomic durable write or committed local transaction. Receiver storage must survive phone app closure/reboot before ACKing.
4. For an identical `(sessionId, digest)`, acknowledge again without duplicating the session. A lost ACK must be harmless.
5. For a changed digest of an existing session, retain/update that session's results safely and acknowledge that digest after commit. Avoid duplicate session rows.
6. On parse, checksum, unsupported-schema, storage or commit failure, withhold `SESSION_STORED`. The first sender version times out and keeps the object; a richer error response is a future compatible extension.
7. Send the exact acknowledgement above over the existing connection. If it disconnects before the ACK, the next launch can resend the same session.

This ACK means safe **local receipt by ICE3**. It does not mean MySQL/cloud synchronization. No new PHP endpoints, database tables, template flows or cloud retries are part of phase 1.

### Exact iOS extension points (handoff only)

In `Shared/ReconAccessoryManager.swift`, extend `handle(_:in:)` with an OBJECT-only branch for `com.recom3.workout.SESSION`. Use `frame.requestKey`, `frame.info` and `frame.payload`; the existing parser already exposes all of them. Add a schema-1 model and a small atomic local session store, with validation/storage off the main stream/UI loop. Return a storage success only after the store's commit is durable.

Encode `SESSION_STORED` using the existing `ReconLegacyFrameEncoder`, passing the incoming request key and digest and the UUID payload described above. Extend the existing per-session output pump/response-kind queue to carry these bounded ACK frames. **Do not write directly to the EASession output stream**: that could interleave a receipt with time-sync, A-GPS or buddy data. Preserve offsets/backpressure and complete-frame ordering.

Capture the originating `ReconChannelSession` identity before asynchronous storage. Before enqueuing its ACK, verify that the live channel is still that same context; do not route a late ACK to a replacement accessory/session merely by protocol name. If disconnected, keep the local session safely stored; the sender retry will be deduplicated and acknowledged on the next valid request. Review exported raw-traffic logging so workout payloads are not unintentionally included in shared diagnostics. These changes are identified here but are not applied to the iOS repository.

## Local queue and retry behavior

The app-private `files/ice3-sync/<sessionId>.queue` contains one atomic record per session: queue version, session UUID, delivery state, completion timestamp, digest and the unchanged UTF-8 JSON bytes. The queue container is a small binary record; the payload is the JSON specified above. `SYNCED` records and their JSON are retained. No object is deleted after acknowledgement.

States:

- `PENDING`: durably queued but not confirmed.
- `FAILED`: last submission/receipt attempt failed or timed out; still eligible for retry.
- `SYNCED`: matching durable-storage receipt received and state committed locally.

Writes use a backup of the previous committed record plus file-descriptor sync. A leftover backup restores the previous complete entry on next access. The payload and delivery state commit together. Checksum/truncation failures leave the original entry in place and do not prevent processing healthy entries. No new database is used.

Startup scans completed internal CSV archives in the background to repair a process kill between saving the final set and queuing JSON. It also makes existing completed archived workouts eligible for sync. This scan reads an existing CSV backup as the last committed version when necessary, but never rewrites, exports, recovers or deletes CSV files. An unreadable archive is retained and logged with no personal content. Queue replay is deduplicated by session ID and digest.

Each startup job is coalesced while already running. One pass processes eligible entries by completion time, with a maximum of 20 attempts, at most 5 seconds waiting per receipt and at most 30 seconds of transport waiting for the pass. Each entry is attempted at most once in that pass. Remaining entries keep their state. There is no timer loop, periodic poll, background reconnection, aggressive resend or forced Bluetooth ownership change. A later startup or completed workout initiates another pass.

The process worker can finish a bounded job after the Activity closes, but Android may kill it at any point. There is no new always-running service. Persisted queue entries and completed CSVs provide recovery on the next launch.

## HUD and CSV independence

Congrats includes `ICE3 sync pending` or `Synced with ICE3`. Failed attempts display the brief `Workout saved. ICE3 sync pending.` notice while the summary is visible. Confirmed receipt displays `Workout synced with ICE3.` Starting another workout does not wait for a receipt.

The existing internal/external CSV format, paths, set snapshots, export behavior and retention are unchanged. Queue failure cannot roll back a saved set, block Congrats or remove a CSV. Existing sensor diagnostic recordings are untouched.

## Validation and remaining acceptance work

Run `checks/run.ps1` with JDK 8. Existing motion/session checks remain, with new sync checks covering completion and receipt, disconnected ICE3, transfer failure, retained objects across reopen, retry ordering, duplicate/lost acknowledgements, content revisions, rejected wrong receipts, interrupted writes, corrupt entry retention, crash-window repair, unchanged CSV bytes, maximum supported payload and bounded worker execution.

The transport is guarded against the Android main looper. A separate compilation against the actual API 16 `android.jar`, plus final APK manifest and v1-signature verification, checks legacy API compatibility. JVM tests use a fake durable receiver; they do not demonstrate a live iOS receiver.

Before accepting end-to-end iOS sync: implement the receiver additions in its separately authorized repository, test the existing iOS parser/dispatcher with workout frames, test offline and reconnect scenarios with the Jet, kill/restart both applications around storage/ACK boundaries, compare the receiver's stored digest and session totals, and confirm no duplicate session after an ACK is lost. No APK is installed and no repository is published automatically as part of this delivery.
