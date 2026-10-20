# Remaining snapshot persistence originals through the actual service

## Binding and concrete missing boundary

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134,
owner `test-parity-engineer`. Starts at published
`adb526cbcdc21a8a20bcb3787676972cd7c7432a`, targeted-execution base
`5714ba3be50618b8d946a21e0db15feb67f90eb6`.
Frozen read-only source remains
`db14559b39d32322b06477c6ae676112f583db50`.
Semantic manifest remains
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
semantic policy remains
`7f757bb6dbde98ab8af6da189f6e9273d4bc45ddb8e903703bee8b43c9b372f6`.
Supports `WP-501-behavior`, `WP-501-boundaries` and
`WP-501-source-test-parity`, not approved acceptance receipts.

Read all30 complete frozen `NodeSnapshotServiceTests` bodies and their
service/metrics/key/fix/prefix helpers, not filename/declaration totals.
Source test blob is `34b8b84896bffde59ec35ed42ef9a7a42062164b`.
Read actual Swift service, NodeSnapshotPersisting baseline extensions,
PersistenceStore+Diagnostics save/update/history/prune/atomic capture,
and their actual native service/contracts/Room delegation/repository,
DAO mappings and context transaction/lifetime.

Existing native service cases use NodeConfigSnapshotStore, whose test
code implements capture/window/history/baseline data. Existing real
NativeDiagnosticRepositoryTest compound/max-value/exact15-minute/
two-store cases were read: they have different inputs and bypass the
NodeSnapshotService consumer. They are useful independent tests, not
the complete selected service originals and not new execution here.

Owned `NodeSnapshotPersistenceOriginalPredicateTest` closes that service
-> production RoomPersistenceStore/DiagnosticRepository/DAO/transaction
boundary for22 previously unwitnessed complete original cases. It adds
no replacement persistence, history, baseline or capture algorithm.
Earlier six location methods remain byte-identical and are not replayed.

## Every selected original case and parameter tuple

All rows are single source cases, with full literal tuples rather than
an invented parameter expansion. Method names are in
`com.meshcoreone.android.parity.NodeSnapshotPersistenceOriginalPredicateTest`.
Common source key is exactly32x42; omitted optionals remain absent.

| Complete frozen original | New native method and retained predicates/inputs |
| --- | --- |
| `Second status within the window enriches the same row, not a new one` | `originalSecondStatusRetainsTheFirstIdBatteryAndUptime`:3850/3600 then3900/7200 with full helper metrics; first ID required, second equals first, count1, battery3850 and uptime3600. Adds committed row ID equality. |
| `Different nodes get independent snapshots` | `originalFortyTwoAndNinetyNineNodesCommitIndependentSnapshots`: keys32x42 and32x99 with3850/3600 and3700/1800. Both IDs required/different; adds exact committed IDs/keys/batteries/second uptime and one history row per key. |
| `Neighbors enrich the in-window snapshot` | `originalNeighborEnrichmentKeepsTheActualStatusRow`: original3850/3600 then prefix01020304/SNR5.5/age30, same IDs, count1 and first SNR5.5. Adds entire persisted neighbor tuple, actual ID and preserved status. |
| `Telemetry enriches the in-window snapshot` | `originalTemperatureEnrichmentKeepsTheActualStatusRow`: original3850/3600 then channel0/temperature32.5; same IDs/count1/value32.5. Adds full persisted tuple and actual row ID. |
| `Fetch snapshots returns ascending order` | `originalTwentyAndTenSecondRowsReturnInAscendingOrder`: direct actual saves atNOW-20/-10, batteries3600/3800 and other fields nil; count2 and both ordered batteries. Adds exact ordered IDs/timestamps. |
| `Prune only deletes snapshots older than cutoff` | `originalThirtySecondPruneRetainsOnlyTheTenSecondRow`: direct actual savesNOW-60/-10 with3600/3800, cutoffNOW-30; count1 and exact recent ID. Adds both rows present before pruning. |
| `Prune with future cutoff does not delete recent snapshots` | `originalOneCalendarYearAgoCutoffPreservesTheRecentRow`: actual source body saves current3850 and prunes **one calendar year ago**, despite its misleading title. Requires remaining count1, adds exact ID. Does not silently change the body to a future cutoff or a365-day approximation. |
| `Fetch snapshots with since filter` | `originalFifteenSecondSinceFilterReturnsOnlyTheFiveSecondRow`: actual savesNOW-30/-5 with3600/3800, sinceNOW-15; count1/battery3800. Adds complete pre-filter count2 and exact recent ID. |
| `Enrichment data survives save -> enrich -> fetchAll round-trip` | `originalTwoRowEnrichmentRoundTripRetainsEveryHistoryEntry`: NOW-20 status3600/7.0/-90/-120 andNOW-10 status3800/8.5/-85/-118, other counters nil; update each actual ID with channel0 temperature25/30 and prefix01020304/SNR5/age60 versus05060708/SNR9/age10. Preserves count2 and every source per-row array count/value/SNR; adds exact ordered IDs and entire telemetry/neighbor tuples. |
| `Status + telemetry + neighbors in one window collapse onto a single row` | `originalStatusTelemetryAndNeighborsCollapseOntoOneRealRow`:3700/3600 status, then channel0 temperature28.5 +channel1 humidity65 and AABBCCDD/SNR6.5/age45; first ID required, same enriched ID, row count1, telemetry count2 and neighbor count1. Adds full persisted arrays/ID/battery. |
| `Telemetry-first then status-within-window backfills status onto the single snapshot` | `originalTelemetryFirstCaptureReceivesEveryOriginalStatusBackfill`: channel1 temperature21.5 then3900/9.0/-84/-119/uptime7200/rx150/sent600/received1200/errors3, all six optional per-type counters nil. Requires first ID, same status ID, count1, preserved21.5, uptime7200, battery3900/errors3. Adds required before-row with nil uptime, all supplied status fields and same committed ID. |
| `Neighbors captured before any status persist on a fresh snapshot` | `originalNeighborOnlyCaptureCommitsWithoutInventingStatus`:0A0B0C0D/SNR4/age90, required ID/count1/neighbor count1/uptime nil. Adds entire committed tuple and exact ID. |
| `Previous neighbor snapshot skips a more recent status-only row` | `originalNeighborBaselineSkipsTheNewerStatusOnlyRow`: NOW-3600 row3700/6/-90/-120/3600 with01020304/SNR6/age30; NOW-1800 status3850/8/-85/-118/7200. Requires baseline SNR6; adds both rows present and baseline exact prior ID, not newer status ID. |
| `Previous neighbor snapshot excludes the current in-window capture` | `originalNeighborBaselineExcludesTheCurrentNineSnrCapture`: same original prior row atNOW-3600, then current01020304/SNR9/age5; requires baseline SNR6. Adds different current/prior IDs and exact prior baseline ID. |
| `Previous neighbor snapshot is nil when no prior neighbor data exists` | `originalStatusOnlyHistoryHasNoNeighborBaseline`: original3850/3600 status, requires nil baseline. Adds required actual capture/history ID so empty storage cannot fake success. |
| `Previous neighbor snapshot returns an out-of-window neighbor row as the baseline` | `originalOutOfWindowNeighborRowIsItsOwnLatestBaseline`: original NOW-3600 prior status/01020304/SNR6/age30, requires baseline SNR6; adds exact stored baseline ID. |
| `Previous neighbor snapshot is nil when the only neighbor row is the in-window capture` | `originalOnlyCurrentNeighborCaptureHasNoPreviousBaseline`: current01020304/SNR9/age5, requires nil previous. Adds actual required capture ID and stored history ID. |
| `Seen neighbor prefixes span all history, not just the previous snapshot` | `originalSeenPrefixesRetainSilentAAndExcludeCurrentC`: complete six-byte seedA0/B0/C0 prefix helper, NOW-7200 status3700/6/-90/-120/3600 withA/SNR6/age30; NOW-3600 status3800/7/-88/-119/5400 withB/SNR7/age30. Requires previous B/not A and seen A/B; after currentC/SNR9/age5 requires historical A retained and C excluded. Adds required previous ID, exact seen{A,B}, current stored ID and unchanged B baseline. |
| `Previous status snapshot skips a more recent neighbor-only row` | `originalStatusBaselineSkipsTheNewerNeighborOnlyCapture`: NOW-3600 status3700/6/-90/-120/3600 then current01020304/SNR9/age5; requires status baseline uptime3600. Adds exact prior ID and required committed current capture. |
| `Previous status snapshot includes an in-window status capture` | `originalStatusBaselineIncludesTheCurrentStatusRow`: original3850/3600 status then causally later before-date, requires uptime3600; adds required returned/stored baseline ID. |
| `Previous status snapshot is nil when only neighbor data exists` | `originalNeighborOnlyHistoryHasNoStatusBaseline`: current01020304/SNR9/age5, requires nil status baseline. Adds real required capture/history ID. |
| `Concurrent in-window captures never duplicate a snapshot` | `originalConcurrentStatusAndTelemetryResolveToOneCommittedRow`: two actual concurrent calls, original3850/3600 status versus channel0 temperature19; both IDs required/equal and final count1. Adds exact committed ID, preserved full telemetry and status battery/uptime, not a success-shaped empty/fake result. |

The repeated full `metrics(battery,uptime)` retains SNR8.5/RSSI-87/
noise-120/rxAirtime100/packetsSent500/packetsReceived1000 and nil
receiveErrors/six direct/flood/duplicate counters. Historical primitive
saves retain source-specified values and nil for all omitted counters.
No source array member, action, optional, tuple or required assertion is
dropped. Four-byte literal neighbor prefixes stay four bytes; only the
source seed helper uses the actual frozen six-byte length.

## Remaining originals: reuse actual complete witnesses, not duplicates

The six location originals remain represented by unchanged
`NodeSnapshotLocationOriginalPredicateTest`, blob
`8fba22797b16738c3531e4eb9c63934f9bd82a95`, with their actual earlier
6/6 on918 and [per-original table](node-snapshot-location-originals.md).
The separate source `Record returns an ID on first capture` is already
fully witnessed by its `originalStatusOnlyCaptureCommitsARowWithBothCoordinatesAbsent`:
same32x42, full3850/3600 status, no fix, required non-nil returned ID
plus actual committed row/ID/history. That is a many-originals-to-one
complete native witness, not a dropped case or another required replay.

The sole non-persistence original,
`NodeStatusMetrics(status:) maps the six per-type packet counters`,
already directly calls production `NodeStatusMetrics.fromStatus` in
`NodeSnapshotServiceTest.sourceCases`, without NodeConfigSnapshotStore.
Read its complete tuple: REPEATER/layout,6x42 prefix,3800/queue1/noise-110/
RSSI-85/received2000/sent1000/airtime500/uptime3600/flood200/direct100/
receivedFlood400/receivedDirect300/fullEvents7/SNR9/duplicates11,22/
rx100/errors3; all six original counter assertions are present.
Native StatusResponse/fromStatus replaces Swift RemoteNodeStatus/init.
This is the existing exact case-body witness, not new execution,
all-field conversion acceptance or a blanket DTO equivalence approval.

Thus all30 source cases have concrete body-level destinations:
22 new cases, seven original cases witnessed by the prior six location
methods, and one existing direct mapper. This is accounting of original
cases, **not a combined30-case execution or independent full-file
acceptance**. No historical/fake-store test total is added to the new22.

## Native clock, transaction, concurrency and lifetime adaptations

Uses the unchanged RoomProcessTest API31 Robolectric/native-SQLite
database/teardown. The helper owns one additional actual
RoomPersistenceStore with test backgroundScope and the same Clock passed
to NodeSnapshotService; finally closes it. The inherited default store
does none of the selected work and closes normally. Test-core TestClock
is a suspending clock, not java.time.Clock; the separate data-module
RepositoryClock is not an App test dependency. A private controllable
Clock follows that repository pattern; it is not a snapshot oracle.

NOW is fixed Unix1704067200. Kotlin's public primitive save lacks the
Swift concrete store's explicit timestamp overload. `seed` temporarily
sets the injected clock to the exact source relative timestamp, calls
the **actual production save transaction**, and restores NOW in finally.
It does not insert fabricated DAO rows, bypass repository writes or
implement expected history/first-wins logic. No clock mutation occurs
during the concurrent calls.

Calendar subtraction uses minusYears(1) in deterministic UTC. For the
status baseline's strict `timestamp < before`, NOW+1ns represents the
source's causally later `.now` sample; it does not redefine the cutoff
or claim exact-boundary coverage. All source elapsed seconds,15-minute
in-window/out-of-window relationships and chronological input order
remain intact.

Both concurrency calls start undispatched and suspend at one explicit
CompletableDeferred gate, then resume on Dispatchers.Default against
the real repository mutex/Room committed transaction. No arbitrary
sleep, fake synchronizing store, expectation weakening, retry-until-pass
or assertion about every possible scheduler interleaving.

## Actual final execution and source binding

Declared credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 with existing private caches. No dependency
installation/change or new runner/task.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.NodeSnapshotPersistenceOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**Final PASS22/22**, failures0/errors0/skips0, with all22 table method
identities uniquely discovered in fresh
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.NodeSnapshotPersistenceOriginalPredicateTest.xml`,
timestamp `2026-10-20T23:26:43.975Z`. Executed source blob
`0ea30e54bf200dddec4a024bf5821a66bd75d758`.
Initial development23/23 at23:23:06.607Z/blob2ca592bf included the
redundant standalone first-ID case; it was removed in favor of the
actual prior complete witness above. That development result is
historical, not final-source execution or an additional23 new originals.
Generated localization/theme/asset prerequisite counts are not new22.

Unchanged executed service:
`fe59f3a2100d4316e560da896d4ec7a5ecf4141f`;
repository: `bd341be989bb46aa5af43fccc6fbd65ed433442e`;
Room store: `de1c03fe01659660ee4628bd804a7b9b565f2d3c`;
real fixture: `c6fb962fca19d1ff87c48eaa05868514a08d614a`.
Selected source tests/service/protocol/persistence bytes have no drift
from frozen db145. No prior cohort or pure mapper was manually rerun.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
passed. No archive/proposal generation, nominal source-marker coverage
or invented independent receipt.

## Current-main reconciliation and independent limits

Same existing actual attempt/stored session
`d6056690-3a5d-453e-b67e-36866c676ac5`,
runtime `27f3c368-787a-4cad-8050-8c7c57b25eb4`.
Reservation71->72 admitted only new owned test/evidence paths.
At the safe execution boundary actual main advanced to
`687c2eee4ee8685948fef79bfd188726625e7045` through user-merged PR #255
at23:24:21Z, retaining producer
`e584cb3736f839e86353eccf1ac5e2438d325406`. Same reservation72->73
admitted its four disjoint incoming packaging/docs/test paths.
Merge `b7b3583e88e639117c3e7655f19717a7e7fb6947` preserves all four
exact producer/main blobs and every one of146 candidate-only adb paths.
New executed0ea test bytes remain unchanged. Manifest/policy/frozen
source are unchanged; local22/22 remains actual5714 execution, not a
fresh687 replay. Inherited packaging code is not WP-501 deployment,
native consumer verification or permission to invoke it.

During normal publication of initial22-case
`ce29cb1b87e834c484245bcf3095aec1a97150e9`, user-merged PR #256
advanced actual main at23:36:30Z to
`bfd7551565de3c5b1e5a71f3f16df1b72c7af00f`, retaining producer
`1562fb5c1a0bcb62e7b5c56d844ea41fc8c2a3af`. Same owner-fenced
admission is idempotent for those already admitted four paths:
actual revision remains73, not a manufactured74 or new attempt.
Merge `b58cf78d35c4f6d4ba1a4b49d04c1d6edc82e012` preserves all
four exact producer/main blobs and all148 ce29 candidate-only paths.
This is the latest integrated main/base. Frozen source, manifest,
policy and all executed Android test/product/fixture bytes are unchanged.
Local22/22 remains actual5714 execution, not a new bfd replay.
No packaging/native invocation, deployment or signing acceptance.
Intermediate [ce29 CI39516079612](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39516079612)
was running at this read: classifier/controller passed, Linux and codec
running; no full verdict. Its installed advisory Python/preflight
passed, scaffold exceeded180s with owned cleanup: explicitly UNVERIFIED,
not a test defect, merge check or transferred successor execution.

Actual [COMMENTED source comparison5592998645](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#pullrequestreview-5592998645)
binds adb/5714's six location originals and carried-forward three slot
originals only. No blocker in that bounded source comparison is not a
review of this new22, a gate approval or whole-WP/service acceptance.
Earlier actual [adb CI39513276508](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39513276508)
was still running at the continuation checkpoint; its eventual outcome
stays bound to adb/5714, not this successor or these new methods.
Historical19bb/eb44 runs remain CANCELLED by actual successor
annotations, not test defects or full passes. No CI-result bundle is
saved/hashed/revalidated.

This selected source-test family does not certify real disk/restart,
physical GPS/radio/device, mounted remote UI, full error/cancellation,
every time-boundary/scheduler interleaving, backup oracle or all assigned
original software families. No new product defect or ownership
collision was established. Independent source comparison/current-head
hosted checks/persistent reviewed merge and broader WP-501 remain open.
Stop this coherent family for review, not a new worker/native/WP-503
action, self-approval, PR merge, issue closure or full-WP verdict.
