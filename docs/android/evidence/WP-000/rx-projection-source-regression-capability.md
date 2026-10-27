# WP-000 RX projection source-regression support

## Separate assignment and source-baseline decision

This distinct follow-on support batch starts at clean trusted main
`3498dead5e5cad13d71936d95ddd6860b7217c8c`, after merged PR #351.
The WP-203 rule and its authorization/evidence remain unchanged.
The source pin is `db14559b39d32322b06477c6ae676112f583db50`.
The existing WP-000 producer follows tracked port-orchestrator/common WP
instructions, without custom profile injection, new actors or product edits.

Parent oversight explicitly rejected the unapproved strict-corrupt-projection
adaptation. Pinned `PersistenceStoreTests.swift` requires exactly:
route 999 -> flood, payload -1 -> unknown, version 5000 -> 0,
path 400 -> 0, channel 9999 -> nil, timestamp -10 -> nil.
The frozen DTO initializer uses checked/failable conversion. These are source
projection defaults, not permission to mutate persisted input or swallow
unrelated failures.

Pinned `RxLogService.loadExistingEntries` already catches/logs fetch failures and
returns an empty list; current Kotlin preserves that behavior and additionally
propagates cancellation. The defect is upstream rejection of source-valid fallback
rows, not the matching service catch. The proposed typed service rethrow and new
`RxLogCorruptionBoundaryTest.kt` were withdrawn. No service/UI/new-test capability
is authored, and no new native error-propagation adaptation is approved.

The existing WP-201 producer confirmed that two existing repository tests still
expect the rejected typed-corruption behavior. Both exact current-main `modify`
admissions failed with no trusted path+operation rule. This support exists only
to restore their original source oracles while retaining real Room, non-loss and
non-mutation evidence. The producer's converter, owned database proof and WP-201
evidence remain under its existing ownership, outside this policy batch.

## Minimal exact capability

`wp201-rx-projection-source-regressions` admits only WP-201 named operation
`restore-rx-projection-source-regressions` at these existing paths:

- `android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/RxLogSourceTest.kt`
- `android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/ParityRxMaintenanceOriginalPredicateTest.kt`

In the first file, scope is only the first corruption method
`genuineStoredCorruptionIsReportedWithoutReplacingTheRowWithDefaults` and its
original-case annotation. In the second, scope is only the first corruption
method `originalSixCorruptValuesRetainFullRawInputAndSurfaceTypedFieldFailureWithoutFallbackOrMutation`
and its annotation. Their names may be corrected to describe the actual
source-fallback assertions. Imports may be removed only when unused. All other
methods, pruning/batching/retention/order tests and primary WP-202 provenance
remain unchanged.

Require independent absolute six-field expectations for compound and isolated
inputs. Actual `RoomPersistenceStore.fetchRxLogEntries` must retain the same entry
identity, radio and raw bytes, return the projected row plus a healthy companion
rather than an empty success, and preserve the entire stored raw row through
repeated reads without mutation, deletion, recreation or spurious issues.
Do not wrap/clamp out-of-range values or introduce defaults outside source
approved fields. This is correction of an unauthorized adaptation, not lowered
assertions or replayed source-family acceptance.

There is no generic modify/create, new test, data/services subtree, converter,
schema, build/dependency, service-production or UI authority. Preserve the same
owner/stored session/authorization: no impersonation, release/recreation or ledger
rewrite. Consumers must reconcile trusted main and obtain actual same-owner
admission after landing. Secrets/raw data and licenses remain unchanged.

## Admission, projection and verification

One exact rule is inserted before the unchanged WP-203 rule. Its strict inverse
removes only this delta before the existing WP-203/JVM/WP503/consumer/older chain.
Both raw historical helpers first assert complete actual-3498 object and Git-blob
equality. Every older assertion is retained. The older backup round-trip fixture
normalizes this new delta first and still asserts its unchanged complete baseline.
Every pre-existing capability, including WP-203, is equal to trusted main.

Five new regression methods cover both actual existing-path admissions, all
other manifest WPs, generic/cross-purpose/sibling/new-test/service-production
denials, widening/mutation/overlap/duplicate/order rejection, complete predecessor
equality/round trip and exact source-baseline/non-mutation/ownership fences.
No actual Kotlin test, Room execution, service/UI or Android acceptance is claimed
by these Python policy fixtures.

Declared relevant bounded commands:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\tests"
python -m unittest -q test_consumer_support_capabilities test_scope_amendment test_capabilities test_disposable.DisposableGuestTests.test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\portmap.py
git diff --check
```

Initial complete focused execution: 98 run/passed, zero failures/errors/skips,
exit 0, 16.544 seconds. Final exact frozen-candidate command results belong in
the PR and authoritative hosted job, not a second reproducible CI bundle.
Full controller/local suites, all-stage fast/Gradle and native/hardware/iOS/
Android/signing/legal verification are NOT RUN / UNVERIFIED for this amendment.

The supported WP-000 claim attempt reported an existing source/base/manifest/
backend mismatch. All six edited policy/test/doc paths separately passed trusted
WP-owned admission; no claim was released/recreated or ledger manually changed.
Documented read-only controller status remains off/paused, no activation; this
assignment does not authorize dispatch, runtime installation, jobs, resource
draining or settings. Parent oversight retains protected exact-head review and
normal merge. Previous publication exceptions are not silently reused.
