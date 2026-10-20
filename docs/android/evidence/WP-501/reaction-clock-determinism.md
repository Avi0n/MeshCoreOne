# Mounted reaction fixture clock determinism

## Exact failure and scope

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / existing PR #165 / #134.
Recovery starts at `4cc4099aa12b83fc5722bf63121cdfe5283be183`, based on
`9c430cb0f1666faacc443794899b68c01128a87a`; frozen source remains
`db14559b39d32322b06477c6ae676112f583db50`.

Automatic [run39471896438](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39471896438)
is **FAILURE**, not pending or green. Actual Linux
[job118798809809](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39471896438/job/118798809809)
completed 969 tests with two failures:

- `IncomingReactionAcceptanceTest.mountedMeshCoreOpenV3DirectReactionReachesTheActualStoredTarget`
- `IncomingReactionAcceptanceTest.mountedMeshCoreOpenV3ChannelReactionReachesTheActualStoredTarget`

Both reached the actual stored-reaction assertion, expected1/actual0.
Controller469 and real Swift codec passed; SwiftData restore/export was
skipped after the Linux failure and final `android-ci` failed.
There is no passing current-head strict collector result for that failed App
task. Repeated failure lines in the wrapper log are not extra executions.
Logs were read in memory; no second CI-result bundle is saved.

## Concrete mechanism and controlled reproduction

The owned helper injected `SyncClock.now()` as
`EPOCH + testScheduler.currentTime`, while its wire timestamps and independent
hashes are fixed at `1704067200`, `aa61` (channel) and `92c7` (DM).
Real Room work runs off the coroutine test scheduler. `runTest` can advance
scheduled timers while awaiting that work, so timer progression is not an
explicit wire-arrival wall-clock input.

Actual `SyncCoordinatorReactionHandlers.kt` selects v3 candidates through the
real Room store within `now +/- 300s` (DM) or `receiveTime +/- 300s` (channel).
V3 has no pending queue/cache fallback. V1 uses its wire timestamp anchor;
Pocket's indexed/pending paths are different. Thus timer-driven wall drift
can exclude an otherwise correct fixed v3 target without corrupting its
stored timestamp or changing the reaction parser/hash.

Added two Android-only regression methods to the existing owned fixture:

- `mountedMeshCoreOpenV3ChannelReactionIsIndependentOfTheSchedulerEpoch`
- `mountedMeshCoreOpenV3DirectReactionIsIndependentOfTheSchedulerEpoch`

Each advances the otherwise empty scheduler by301000ms **before** constructing
the session, then executes the unchanged literal reaction and full existing
Room/polling/storage assertions. This does not wait in real time or advance an
active session's watchdog. The unchanged-clock negative control actually
discovered2, ran2, failed2, errors0/skips0 at
`2026-10-20T18:28:07.957Z`; working-source blob
`989e17d4dd2fed8127f27ff4ce80a45febe126e8`.

| Negative-control method | Measured fixture wall time | Scheduler milliseconds | Unchanged target timestamp |
| --- | --- | --- | --- |
| DM | `2024-01-01T00:09:06.200Z` | 546200 | 1704067200 |
| Channel | `2024-01-01T00:05:31.200Z` | 331200 | 1704067200 |

Both actual reads returned zero reactions outside the real300s candidate
window, reproducing the hosted assertion failure. The hosted failure did not
log its clock values; this measured reproduction establishes the concrete
fixture mechanism, not the exact unseen scheduler value on that runner.

## Owned fix and actual positive execution

Only the owned `IncomingReactionAcceptanceTest` helper changes: wall time is
explicitly `EPOCH` for target reception, then `EPOCH+1s` when its reaction
arrives. Pending-before-target cases retain that later reception time.
`elapsed()` and `sleep()` remain scheduler-driven for deadlines/backoff.
An explicit final wall-time assertion and richer count-failure diagnostic
keep clock mistakes observable.

All eight existing JUnit declaration bodies are byte-identical to4cc.
No raw packet, expected hash, timestamp guard, exact reaction/radio/contact/
channel/summary readback, ordinary-row absence or real-service forwarding
assertion is removed. The two new regressions are supplemental native
determinism checks, not two extra frozen original cases.
No product, foreign test/harness, persistence query, protocol parser, time
window, timeout or workflow is changed.

Declared isolated launcher command (same explicit Java21.0.12.1, SDK37.2,
build-tools37.0.0 and private cache environment as the channel increment):

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.IncomingReactionAcceptanceTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS10/10**, failures0/errors0/skips0. Fresh native XML at
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.IncomingReactionAcceptanceTest.xml`,
timestamp `2026-10-20T18:30:49.335Z`, contains exactly the eight existing and
two new identities, each once. Executed test-source blob:
`b79c03ebd5e996d988160dd48b31af5e5bb7c01e`.

The negative command used the same launcher/flags but replaced its class
selector with two `--tests` arguments, each the full class name plus one
regression method above. It ran before the wall-clock fix; its expected
failure is not counted as a pass. Generated prerequisites are not added to
the10 native methods. Later prose/commit publication does not invent a
second local execution.

## Review and acceptance boundaries

[Bounded source review5589646160](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#pullrequestreview-5589646160)
at exact4cc found no remaining source blocker in the seven earlier requested
predicate repairs. It is COMMENTED feedback, not review of this later clock
fix, a gate approval, fresh execution, issue closure or merge authorization.
Those repairs and the channel/draft accounting remain intact.

This recovery has no demonstrated product-owner collision: the defect
reproduced in WP-501's injected fixture clock. Current successor hosted
verification and independent review remain separate from this local pass.
Do not relabel failed4cc, old7db success, peer244 draft execution or the
earlier historical incoming-reaction results. Whole-WP/physical/human
acceptance and persistent reviewed merge remain open.
