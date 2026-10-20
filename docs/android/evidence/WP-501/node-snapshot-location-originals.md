# Node snapshot location originals through production persistence

## Binding, current-main integration and missing boundary

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134.
Starts at published `19bb78c13ad086e6622c0bf38a66e11dced981e1`;
targeted-execution main/base is
`918af38472a43bf24f9a8bc4762c3c1fb45851b4`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
Semantic manifest remains
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
semantic policy remains
`7f757bb6dbde98ab8af6da189f6e9273d4bc45ddb8e903703bee8b43c9b372f6`.
This increment supports `WP-501-behavior`, `WP-501-boundaries` and
`WP-501-source-test-parity`, not acceptance receipts for those IDs.

Actual PR #252 merged at22:44:27Z, retaining producer
`b8a384d5bb00ea3c282edd405ca6785565affdda`. Same reservation68->69
admitted all eight disjoint incoming paths: the WP-003 CI README and
deviation, controller engine/ledger, local reservation CLI/test and
native-review/state tests. Merge
`bb0728cd58248202503a7fe883457b197c1f27f5` preserves all eight exact
main/producer blobs and every candidate-only path from19bb. There was
no overlap, producer rewrite, frozen-source/policy/manifest change or
replay of unchanged Android cohorts. Inherited controller source is not
a newly claimed controller execution or gate approval.

During normal publication of the six-case increment
`eb44f0b503c89a7396879db9ba66740357774402`, the user merged PR #254 at
23:00:39Z. Latest actual main/base is
`5714ba3be50618b8d946a21e0db15feb67f90eb6`, retaining producer
`5f7f9db4996e47cb96c084e0afcd5fbb76975bb6`. Same reservation70->71
admitted its five disjoint incoming paths. Merge
`555ce38ff8723022044336c21ea974b3c7a247f3` preserves all five exact
main/producer blobs and all146 candidate-only paths unchanged. Inherited
policy bytes change `paused:true` to `false`; dispatch remains off and
activation unapproved. Actual supported policy_revision still returns
the same semantic7f757 value above. This is the already user-merged
WP-003 operational prerequisite, not a WP-501 policy edit or native
authorization. No native action, live deployment, fleet activation or
test replay is performed. Local6/6 below remains execution on918 with
identical test/product/fixture bytes, not a newly executed5714 result.
The intermediate eb44 automatic
[CI39512750216](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39512750216)
was pending at that checkpoint; advisory Python/preflight passed but
scaffold exceeded180s/owned cleanup, explicitly UNVERIFIED. No result
from that intermediate publication is relabeled to the final successor.

Read all six complete adjacent location originals and their
`createTestService`, `metrics`, `testPublicKey` and `testFix` helpers in
`MC1Services/Tests/MC1ServicesTests/NodeSnapshotServiceTests.swift`,
blob `34b8b84896bffde59ec35ed42ef9a7a42062164b`. Its source factory uses
actual in-memory PersistenceStore, not a location/window reference model.
Also read the actual source service, NodeSnapshotPersisting contract and
PersistenceStore+Diagnostics capture branch, blob
`5a6e67e4c4ba28588ad0b8176ececed3c52649e2`.

Existing native `NodeSnapshotServiceTest.Fixture` instead supplies
`NodeConfigSnapshotStore`: that fake implements the row lookup,
15-minute window, status backfill and first-wins location algorithm in
test code. Existing real `NativeDiagnosticRepositoryTest` uses different
compound telemetry/extreme-metric/location(1,2,3)/(4,5,6) inputs directly
against the repository, not the selected complete service originals.
Their actual bodies were read, not rerun or counted as new execution.
Missing markers were not used as proof of missing behavior.

The new owned `NodeSnapshotLocationOriginalPredicateTest` executes the
actual service -> RoomPersistenceStore delegation -> DiagnosticRepository
transaction -> Room DAO/mapping path. No fake snapshot/window/location
algorithm is introduced. All selected service reads and repository
refetches use actual committed rows.

## Complete selected original-case accounting

All six originals are single cases without a source parameter expansion.
Method names belong to
`com.meshcoreone.android.parity.NodeSnapshotLocationOriginalPredicateTest`.

| Frozen original | Complete inputs, actions and required predicates |
| --- | --- |
| `A location fix persists on capture` | `originalStatusCaptureCommitsTheSanFranciscoFix` captures the original3850/3600 status and37.7749/-122.4194 fix for key32x42; requires both original exact stored coordinates. Adds required real row, returned/stored ID, exact key/clock timestamp and battery/uptime checks. |
| `A capture without a fix leaves location nil` | `originalStatusOnlyCaptureCommitsARowWithBothCoordinatesAbsent` captures the original status with location omitted; requires latitude=nil and longitude=nil. Adds a required actual row, returned/stored ID, history count1 and stored uptime3600 so missing persistence cannot masquerade as the two optional-nil predicates. |
| `A GPS-only capture persists the fix on a fresh row` | `originalGpsOnlyCaptureCommitsItsFixWithoutStatus` captures only the original fix; requires non-nil returned ID, exact stored latitude37.7749 and uptime=nil. Adds exact longitude, matching stored ID and nil battery/altitude, without inventing status or a GPS platform operation. |
| `An in-window fix is first-wins` | `originalLaterFortyMinusHundredFixCannotOverwriteTheFirstRow` captures the original fix then the actual alternate40.0/-100.0 for the same key; requires history count1 and original latitude37.7749. Adds preserved original longitude and equal first/second/stored IDs, not a second-row replacement that only happens to return the old coordinate. |
| `An in-window no-fix response does not erase an existing fix` | `originalStatusWithoutFixPreservesTheExistingCoordinates` captures the original fix then status with no location; requires preserved latitude37.7749. Adds preserved longitude, same IDs and actual backfilled uptime3600, proving that status reaches the same row rather than being ignored wholesale. |
| `An in-window fix enriches a row that had none` | `originalFixEnrichesTheSamePreviouslyFixlessStatusRow` captures original status, then the original fix; requires history count1 and exact latitude37.7749/longitude-122.4194. Adds a real before-row with both coordinates absent, matching IDs and preserved uptime3600 after enrichment. |

All status inputs retain the complete source helper: battery3850,
SNR8.5, RSSI-87, noiseFloor-120, uptime3600, rxAirtime100,
packetsSent500, packetsReceived1000; receiveErrors and all six optional
direct/flood/duplicate counters remain nil. Location altitude remains
nil, and telemetry/neighbors are omitted in these cases as in the source.
Node key is exactly32x42. Production independently allocates row UUIDs.
No selected source input, action, assertion or alternative fix is dropped.

## Deterministic native adaptation and actual execution

Existing `RoomProcessTest` provides a fresh in-memory database/native
SQLite per case. The small helper creates an actual additional
RoomPersistenceStore over that database with the test-owned background
scope and passes **one fixed Clock to both store and service**.
The inherited default-clock store performs no selected operation.
The selected store is closed in finally; existing fixture teardown closes
its original store/database and cancels its owner. No foreign fixture,
product clock API, dependency or build-graph edit is needed.

Fixed Unix1704067200 replaces incidental Date.now sampling. Both calls
of each in-window original occur at that same injected instant, strictly
inside the actual15-minute policy. This does not claim exact15-minute
boundary, expiration, concurrent writer or elapsed-time evidence.
There is no arbitrary sleep, polling loop, silent successful default
or test-written persistence policy.

Same actual existing attempt/stored native session identity
`d6056690-3a5d-453e-b67e-36866c676ac5`,
CLI runtime `27f3c368-787a-4cad-8050-8c7c57b25eb4`.
After integration, the new owner-fenced reservation CLI correctly
requires explicit existing attempt/session. The earlier flag-less
invocation was rejected before evolution; the supported retry used the
actual stored identity, not a new runtime alias. Reservation69->70
admitted only the two new owned test/evidence paths. No release/recreate,
authorization rewrite, ledger edit or manufactured identity/receipt.

Credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 with existing explicit private caches.
Declared smallest new-class command:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.NodeSnapshotLocationOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS6/6**, failures0/errors0/skips0. Fresh native JUnit XML:
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.NodeSnapshotLocationOriginalPredicateTest.xml`,
timestamp `2026-10-20T22:56:57.088Z`, contains exactly all six table
method identities, once each. Executed new source blob:
`8fba22797b16738c3531e4eb9c63934f9bd82a95`.
Generated localization/asset prerequisites are not added to these six.

Unchanged executed production service:
`fe59f3a2100d4316e560da896d4ec7a5ecf4141f`;
DiagnosticRepository: `bd341be989bb46aa5af43fccc6fbd65ed433442e`;
RoomPersistenceStore: `de1c03fe01659660ee4628bd804a7b9b565f2d3c`;
real Room fixture: `c6fb962fca19d1ff87c48eaa05868514a08d614a`.
Selected frozen inputs have no drift from the pin.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
all **passed** on the integrated current-main source.
No archived proposal/accounting output is regenerated; proposal totals
are neither executed cases nor independent approvals.

## Limits and historical bindings

This is the complete six-case snapshot **location** subfamily, not all30
NodeSnapshotService originals or neighbor/status baselines, history/prune,
concurrency, errors/cancellation, backup codec, UI/remote-radio consumer
or actual GPS/device behavior. API31 Robolectric/native SQLite/in-memory
Room is not physical disk, process-death or hardware evidence.

Earlier19bb's
[automatic CI39510087157](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39510087157)
was still running at the post-selector checkpoint: classifier,
controller and codec passed, Linux118929872020 was running.
It remains bound to19bb/oldbaseec0, not the new main or these six methods.
The coordinator reports a bounded three-original19bb source comparison
with no blocker, but explicitly **no GitHub review was posted** because
the server base was stale. That report is not a current-base review,
privileged verdict or accepted receipt. No review number is invented.
417/run39507338327 remains terminal CANCELLED by normal successor
supersession; neither a test defect nor a successful full run is inferred.

Earlier slot/retention/contact-sync/predicate/clock/helper/draft bytes
remain intact and were not manually replayed. No second CI-result bundle
is saved or hashed. Normal successor exact-source CI, independent
comparison and persistent reviewed merge remain separate from local6/6.
No new product defect or ownership collision was established here.
Stop after this one family for review; broader assigned software work
and WP-501 remain open. WP-503 native execution/pause handling stays with
its existing owners and is not a prerequisite for these software tests.
No new actor, native/protected action, self-approval, PR merge or issue closure.
