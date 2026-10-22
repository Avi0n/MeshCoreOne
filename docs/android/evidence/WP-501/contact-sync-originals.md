# Contact synchronization originals with real persistence and claims

## Binding and concrete missing boundary

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134.
Starts at published `e818035c33db013e615fb9ea75a70093eb39706b`,
base actual main `ec0a41e80ac8637baad524b777f774c10cf99f5b`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.

Read all six complete bodies and helper inputs in
`MC1Services/Tests/MC1ServicesTests/Services/ContactServiceSyncTests.swift`,
blob `511990982021b8ae2e9c07c6d279bc1c822f1380`, plus the source
`PersistenceStore+Testing`, `DeviceDTO+Testing`, `MockMeshCoreSession`
and production service. The original suite explicitly requires real
in-memory persistence to execute the optimized `batchSaveContacts`
override, and real `SyncCoordinator` for the three manual-refresh cases.

Existing `core:services` `ContactServiceSyncTests` retains the six original
input/assertion shapes but uses `ContactsFakeStore` and
`ContactsFakeSyncCoordinator`: its comment describes the older base on
which the real coordinator was not yet ported. The sync-module
`ReferenceContactService` also replicates service logic in test code.
Existing real `BatchSyncPersistenceTest` exercises the repository boundary,
not these complete service-plus-coordinator original scenarios.
These bodies were read, not rerun, and marker/worklist absence alone was
not used as proof of missing coverage.

The new owned `ContactSyncOriginalPredicateTest` binds all six originals to
actual `ContactService`, production `RoomPersistenceStore` / real
in-memory Room/native SQLite and the real `SyncCoordinator` through the
existing App `ContactSyncCoordinatorAdapter`. It does not implement a
replacement claim, upsert, prune, V-contact check, watermark or error
mapping algorithm. Only the original protocol-session/contact reply and
gated advert-service boundaries are doubles.

## Complete original-case accounting

All six are single cases, without an omitted source parameter expansion.
Method names below belong to
`com.meshcoreone.android.parity.ContactSyncOriginalPredicateTest`.

| Frozen original | Actual inputs, actions and assertions |
| --- | --- |
| `Full sync persists all device contacts and prunes locals not on device` | `originalFullSyncCommitsAliceBobPrunesStaleAndReturnsExactWatermark` saves DD-filled32-byte `Stale`, fetches AA/Alice with lastModified1700000000 and BB/Bob with epoch0 through the real service's full `since=null` path. Requires received2, incremental=false, exact watermark1700000000, fetched name set Alice/Bob and absent DD contact. Additionally asserts one null-since protocol call. This reaches production batch-save and committed orphan deletion, not a reference store. |
| `Full sync prunes true orphans but keeps the ZephCore V-contact` | `originalFullSyncPrunesDDOrphanButRetainsSelfDerivedVContact` stores the actual V-key derived by production `VContactIdentity` from the original self key32x01, name`vTestDevice`, plus DD/Orphan. The reply contains only AA/Alice. After actual full sync, DD must be absent, exact V-key must still resolve and names must equal Alice/vTestDevice. Count/name-only preservation cannot substitute for the required V-key lookup. |
| `Incremental sync upserts without pruning unseen locals` | `originalSince100IncrementalSyncCommitsAliceAndPreservesExistingDD` stores DD/Existing, replies AA/Alice, invokes `since=Unix100`, requires received1/incremental=true and exact name set Alice/Existing. Additionally requires the actual session invocation carry exactly Unix100, not null or another watermark. |
| `Manual refresh waits for an in-flight advert delta sync` | `originalManualRefreshMakesNoFetchDuring200MillisAdvertClaimThenFetchesOnce` seeds original lastContactSync1704067200 and holds an actual coordinator advert claim inside the source-style gated service. Starts real manual refresh before release, advances exactly the original200ms, requires no session fetch and an incomplete refresh. After release, the real advert result must be SYNCED and refresh must finish with exactly one fetch. Additionally refetches committed Alice from Room. |
| `Manual refresh claim is atomic so a racing advert delta returns busy` | `originalHeldManualFetchMakesRacingAdvertBusyWithoutEnteringItsBody` holds the first actual advert body, starts manual refresh, releases first advert and requires SYNCED. The stub session then signals that refresh is suspended inside getContacts, just as the frozen barrier does. Requires the fetch really held; a second actual advert must return BUSY. Additionally proves its gated body never started, then releases refresh, requires exactly one fetch and actual stored Alice. No test-written flag or fake coordinator decides BUSY. |
| `Manual refresh surfaces syncInterrupted when the advert claim wait times out` | `original40MillisClaimTimeoutSurfacesTypedSyncInterruptedWithoutFetching` holds the actual advert claim and sets production's existing40ms override. The real adapter/service must surface actual typed `ContactServiceError.SyncInterrupted`, not a generic exception or successful empty result; no contact fetch may occur. Additional exact deadline assertions require incomplete refresh at39ms and completed typed failure at40ms. Releases the source-style advert gate and requires its real SYNCED outcome. |

Every store starts with the source helper's device: id=radioID,
self key32x01, `TestDevice`, firmware8/`v1.0.0`, maxContacts100,
maxChannels8, multiAcks0 and active=true. The three claim cases retain
lastContactSync1704067200; the first three retain zero. Contact frame
inputs preserve CHAT/flags0/outPathLength0/empty path, advertisement0,
coordinates0 and each original lastModified. Stub replies include the
original complete-stream reported total, required for real pruning.
No contact/claim original or alternative parameter was silently excluded.

The session double records `since`, returns the source contact list and
supports the original fetch barrier. All unrelated protocol operations
throw AssertionError rather than report fake success. The gated advert
double only announces entry, awaits explicit release and returns the
source zero-count/zero-watermark/incremental result. It is not a second
coordinator. Gates are released in finally blocks; event streams are
finished in teardown, and the coordinator uses the test-owned scope.

## Native adaptations and actual execution

The source's200ms `Task.sleep` becomes exactly200ms of injected coroutine
test time, not a wall-clock sleep or arbitrary polling. The existing
coordinator `SyncClock` reads scheduler elapsed time and delays on that
same scheduler; wall reference1704067200 keeps the source stored watermark
plausible independently of the host date. The40ms source timeout uses the
same production override and additionally checks the39/40 boundary.
Explicit `CompletableDeferred` barriers replace Swift continuations.
No private coordinator state, test-only claim algorithm, expectation
reduction, foreign support path or new dependency is needed.

Same existing advisory attempt
`d6056690-3a5d-453e-b67e-36866c676ac5`,
CLI session `27f3c368-787a-4cad-8050-8c7c57b25eb4`;
supported reservation66->67 admits only the new owned test/evidence
paths. No release/recreate, manual ledger/receipt or authorization edit.

Credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 with the existing explicit private caches.
Declared smallest new-class command:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ContactSyncOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS6/6**, failures0/errors0/skips0. Fresh native JUnit XML:
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.ContactSyncOriginalPredicateTest.xml`,
timestamp `2026-10-20T22:14:14.886Z`, contains exactly the six table
method identities, once each. Executed new source blob:
`f17e9ac844426dbb55664eec4a473ee74258c3ea`.
Generated localization/asset prerequisites are not added to these six.

Unchanged executed production service:
`36b51dd1eacbe9d1aae0482cd4b17a41a9ea76cb`;
coordinator-contacts implementation:
`92107c00fd960a021bd216a0df675bf73492d14d`;
actual App adapter:
`c282f65b2508c57b444b4325ddb0af7fb21cffd2`.
Room fixture remains `c6fb962fca19d1ff87c48eaa05868514a08d614a`.
Selected frozen source/test/helper files have no drift from the pin.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
all **passed**. Assertion proposals/discovery totals are not executed
case totals or an independent acceptance verdict; no archived output was
regenerated.

## Limits and preserved history

This increment is six complete original cases with actual native
persistence/claim assertions, not six independently approved receipts.
API31 Robolectric/native SQLite/in-memory Room is not physical-disk,
device/radio, process-death, contact-wire codec or mounted UI evidence.
`SharedPreferencesContactFlags` is the actual App preference adapter,
but these sync branches do not exercise preference writes and claim no
preference-durability result.

Predecessor e818's
[automatic run39503877263](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39503877263)
was still running at the22:15 checkpoint; classifier/controller/codec
passed and Linux was in progress. Its eventual result remains bound to
e818, not execution of these six new methods.
[COMMENTED source review5592397882](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#pullrequestreview-5592397882)
finds no bounded blocker in the earlier retention two-case increment only.
The retention class was not replayed or changed. Earlier predicate,
clock, channel/map/contact/draft bytes and their execution/review bindings
remain intact. No second hosted-result bundle is retained.

Normal successor exact-head CI, independent comparison and actual
reviewed merge remain separate from local6/6. No production defect or
owner collision was established here. Remaining software original-family,
helper/consumer/resource comparison stays assigned; no WP503/provider or
human gate is invented for that work. No new worker, protected/native
action, self-approval, merge, issue closure or whole-WP acceptance.
