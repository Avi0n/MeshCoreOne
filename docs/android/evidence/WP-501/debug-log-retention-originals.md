# Debug-log retention originals against real persistence

## Binding and actual missing boundary

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134.
This increment starts at published
`add0aa7b94bb7f8525b3ad9908799cbaefd45b9a`, based on actual main
`ec0a41e80ac8637baad524b777f774c10cf99f5b`.
The frozen specification remains
`db14559b39d32322b06477c6ae676112f583db50`.

The existing WP-212 source-comparison worklist identifies both bodies in
`MC1Services/Tests/MC1ServicesTests/Services/DebugLogRetentionPruneTests.swift`,
frozen blob `d1fc4e00f4e3b794777ac9bba0ad0f2fb5fe6e44`.
Read the complete originals and their helpers, the frozen retention policy
and actual SwiftData save/fetch/prune methods, then the current native
store, DAO and tests. Missing marker-derived proposals alone did not
establish this gap.

`core:services` already retains both original parameter/assertion bodies in
`DebugLogRetentionPruneTest`, but its `RetentionReferenceStore` implements
the prune/fetch algorithm inside the test. Its documented dependency
boundary is legitimate; those tests do not exercise production persistence.
Existing `NativeDiagnosticRepositoryTest.debugLogsUseTheGlobalScopeAndStrictTimeThenHardCountRetention`
does use real Room, but its four nanosecond-spaced rows and keep-two
scenario do not execute these two complete original input families.
Neither suite was replayed or credited as a new execution.

The new owned `DebugLogRetentionOriginalPredicateTest` ports both complete
originals through the existing `RoomProcessTest`: real native SQLite /
in-memory Room and production `RoomPersistenceStore`, repository, DAO,
entity conversion and committed save/prune/refetch calls. No test-written
store, replacement retention algorithm, foreign test or production change
was added. Awaited repository operations complete before each assertion;
there is no sleep, polling or scheduler-based approximation.

## Complete original-case and parameter accounting

| Frozen original | New real-persistence witness |
| --- | --- |
| `prune keeps a recent entry and drops one older than the window` | `originalRecent60SecondsAndStaleWindowPlus3600RetainOnlyTheActualRecentId` saves the source's recent age60 seconds and stale age`window + 3600` seconds, prunes at`now - window` with the source maxEntries, fetches all surviving ages with limit10 and requires the exact one-element recent-ID list. Independent literal assertions pin the actual production policy to seven days / 50000 rows. An added pre-prune count2 proves the two actual rows were stored. A no-op prune or deleting the wrong/all rows fails. |
| `prune enforces the ceiling when the window alone exceeds it` | `originalTenInsideWindowMinuteSpacedRowsRetainExactlyTheNewestFourActualIds` saves all ten source inputs, ages`0,60,120,180,240,300,360,420,480,540` seconds. All are inside the retention window. It prunes with keepCount4, fetches with limit20, requires count4 and set equality with the actual IDs of source entries0..3. Added pre-prune count10 proves the complete input set was stored. Keeping the oldest four or any arbitrary four fails; the time prune cannot supply the missing count-ceiling behavior. |

Both use INFO / subsystem`test` / category`retention`, the source's
`entry aged <TimeInterval>s` message shape and generated UUID identities.
Generated IDs are compared with the exact saved DTO IDs, not substituted
by message/count-only expectations. Expected surviving identities come
from the frozen input positions, not from running a candidate prune
algorithm to manufacture its expected output.

**Native adaptations:** the original helper samples `Date()` separately
for each entry/cutoff; one fixed `2025-01-01T00:00:00Z` origin preserves
every relative age, margin, ordering and original predicate deterministically.
`Instant.MIN` means all fixture ages for the source's `.distantPast` fetch;
it is not asserted to be that Foundation constant's literal epoch.
SwiftData's real in-memory container becomes real in-memory Room under the
existing API31 Robolectric/native-SQLite fixture. This is actual repository
integration, not physical-disk, Android-device, process-death, connection
prune scheduling, hourly-buffer or logging-export evidence.

## Actual strict execution

Same existing advisory attempt
`d6056690-3a5d-453e-b67e-36866c676ac5`,
CLI session `27f3c368-787a-4cad-8050-8c7c57b25eb4`;
supported reservation65->66 admitted only the two new owned test/evidence
paths. Existing owned deviation history is retained. No authorization
rewrite, manual ledger/receipt edit, release/recreate or new worker.

Declared credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 with explicit private Gradle/Android caches.
Only this new exact class was selected:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.DebugLogRetentionOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS2/2**, failures0/errors0/skips0. Fresh JUnit XML at
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.DebugLogRetentionOriginalPredicateTest.xml`,
timestamp `2026-10-20T21:49:06.118Z`, contains exactly both method
identities in the table, once each. Executed source blob:
`a72c73dd74f1cbf1896c1ae7c4fce5591716e419`.
Generated asset/localization prerequisite cases are not added to this
two-method total.

Unchanged executed fixture blob:
`c6fb962fca19d1ff87c48eaa05868514a08d614a`.
Actual production repository/DAO blobs:
`bd341be989bb46aa5af43fccc6fbd65ed433442e` /
`264c014927b3e5b1aa55b41430acb223f827048a`.
Read existing reference-store test blob:
`38e4d39d710d77c3173ec749d47b4880f51bb161`.
Selected frozen test/policy inputs have no drift from the pin.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
all **passed**. These are configured/provenance/assertion validators, not
additional executed original cases or a reason to refresh archived outputs.

## Execution and acceptance limits

This is two new working-source native methods covering both complete
selected original cases, not two freshly accepted receipts, a replay of
already-positive service/data/App cohorts or whole-WP parity.
Published predecessor add0 / successful
[run39496267800](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39496267800)
with App1004 and bounded source-only review5591742112 retain their exact
head/base and earlier channel-helper scope; they do not verify this new
class. The normal successor's automatic exact-source checks and
independent comparison remain separate from local2/2 and persistent merge.
No second hosted-result bundle is retained.

The earlier seven repairs and all channel/reaction/map/contact/draft
source bytes remain unchanged. Manifest/policy/frozen reference and
archived discovery/accounting outputs are not regenerated or weakened.
No product-owner defect or hardware/provider blocker was established by
this bounded family. Remaining original/helper/consumer/resource software
comparison stays assigned WP-501 implementation; broader independent,
device/human and full-WP acceptance remain open. No protected/native
action, self-approval, merge or issue closure.
