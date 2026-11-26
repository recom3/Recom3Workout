# TEMPLATE receiver validation

26 November 2026 (Europe/Madrid). Recom3Workout **0.1.13 / versionCode 14**, package unchanged.

## Automated validation

**204 checks passed** using the existing JDK 8 / Java 7 toolchain:

| Suite | Passed |
| --- | ---: |
| Existing motion detector | 47 |
| Existing session persistence/summary | 34 |
| Existing outgoing SESSION queue/receipts | 30 |
| TEMPLATE framing, schema, persistence, handoff, goals and consent | 78 |
| Production receiver with host-only Android boundaries | 15 |

Run `checks/run.ps1`. The exact Snow3 JSON/frame vector, strict type/UTF-8/duplicate checks, long revision limits, 20-set/100-rep bounds, restart/rollback/readback failure, missing ACK on failures, identical retransmission, stale/conflicting revisions, context handoff expiry, active-session isolation, different targets per set, numbered PLAN persistence and unchanged result schema are covered. Host broadcast checks execute the production TemplateReceiver and stock OBJECT submission method, with substituted Android framework boundaries. They do not simulate a Bluetooth radio or assert firmware interoperability.

All application, generated R and bundled Recon UI Java sources compiled against the actual **Android API 16 android.jar** with Java 7 source/target. The signed APK built successfully, min/target SDK remain 16, and final manifest verification found the exported TEMPLATE receiver with no testOnly/debuggable attribute. Signature verification for API 16 passed with v1 signing enabled and the existing Workout identity. The APK has no native libraries: its Dex/resources support the original ARMv7 Jet without another ABI/library dependency.

MotionEngine, RepDetector, WorkoutJson, SyncQueue, SyncBatch, SyncFiles, SyncRecovery, WorkoutSync, SessionStore and CsvRecording hashes matched the baseline. Outgoing results fields, UUIDs, queue format and CSV storage/export paths were not changed. Uniform/historical CSVs retain their representation; new variable plans use numbered PLAN overrides in the same columns as documented in [template-sync.md](template-sync.md).

## Test APK

`build/installer/Recom3Workout-0.1.13-api16.apk`

Size: **295,476 bytes**.

SHA-256: `1fbe7be67e56bcbd2f6b2531d1208786474d35dca3b85ffceeb9cd7993d83e57`.

Generated APKs, local evidence, signing material and private packaging changes are excluded from the PR. Public source/docs contain no production account/session IDs, device serials, credentials or captured workout/phone screenshots.

## Authorized physical checks

The user explicitly authorized updating the connected glasses for testing. This APK installed in place and launched on an original Jet reporting **Android API 16 / armeabi-v7a**. Existing application data was retained; the previous completed workout summary remained available, with its prior result-sync status. No phone APK, Web/API code, account data or firmware was modified by this work.

During the user's real ICE3 Android send, the Jet logged the sanitized event `Routine stored for the next workout; receipt submitted`. The phone's actual Workout sync UI displayed **Confirmada: Recom3Workout ha guardado esta rutina.** Its routine section showed the downloaded revision and targets per set. This demonstrates real phone-to-Jet receipt, template validation/storage/readback and a matching acknowledgement accepted by the existing phone implementation. It is not merely a successful local socket write.

After feedback that a transient toast was insufficient, the final build added a persistent review/accept screen, a strict exact-digest approval gate for new workouts, and a WEB origin label. The review screen was visually verified on the Jet with the saved routine reloaded across update/relaunch; it was left for the user to accept, not accepted by the agent. The user subsequently accepted it, and the actual paused HUD displayed the WEB origin marker with the downloaded target and set count. The automated consent checks verify that a stored ACK does not authorize use, an unreviewed payload is blocked, an exact reviewed snapshot is usable, fallback needs no approval and an update needs new approval.

The already completed Jet session stayed unchanged after receiving the routine. After the user started New workout, inspection on the Jet verified the first target and set count of all three exercises matched ICE3's downloaded routine. Exercise navigation returned to the initial paused exercise without starting detection. The user also confirmed a subsequent real hot send with Workout already open; the Jet logged a second stored/receipt-submitted event. Live nonuniform targets across completed sets, physical reboot recovery, disconnect/lost-ACK behavior and a newly completed workout's return through ICE3 to the production API have not yet been validated in this run. Automated tests cover those model/retry boundaries, but are not a substitute for these remaining device tests.

## Known integration gap

Source review found that the unchanged Snow3 results validator still caps exercise/set repsTarget at 99, including in current master. Jet goals of 100 are preserved correctly, but their completed results cannot yet receive a phone receipt. These objects remain safely retryable. A separate Snow3/API acceptance follow-up is required before claiming end-to-end 100-target support. The issue remains linked as a dependency rather than being automatically closed by this PR.

## Scope and publication

The implementation is confined to Recom3Workout and follows Snow3 PR #8 at commit `a1ed0577ddd893f1d3ec671fa5e4c1c4e58728fd`. Snow3, ICE3 iOS, Web, PHP and MySQL are unchanged. The user confirmed that this repository's PR target is `main` (there is no `master` branch here). No merge or release deployment is performed; the glasses installation above was a separately authorized development test.
