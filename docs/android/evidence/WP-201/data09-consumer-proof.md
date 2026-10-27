# WP-201 DATA-09 supplemental repeat snapshot consumer proof

## Scope and source contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Owner: `data-persistence-engineer`.
Managed session: `eaf30961-3b7e-41ab-96a2-311d09796879`.
Retained same-owner attempt/session:
`fc4b2e79-8993-46ae-b073-9fbad90d7635`.
Source pin: `db14559b39d32322b06477c6ae676112f583db50`.
Start base: `b48d47afb1799e670d2ddf67f0b5b3c844dc4265`;
base tree: `f85ca9ecbb31c26ebc9bbbd03324251a47db08e1`.

The previous exact candidate `4fb879995cda5062a2485d08d5cd28f820a066a3`
and its accepted DATA-08 evidence remain unchanged. After its ordinary merge
in #362, this independently authorized source-only follow-up started with one
clean fast-forward to the actual merged main above. It is not a routine update
of a published candidate or a transfer of DATA-08 acceptance.

The retained grouping input has SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
Only `root_cause_groups[group_id=DATA-09]`, DATA-BACKUP `/workstreams/8`,
is considered. It names two original families in
`MC1Services/Tests/MC1ServicesTests/Models/DeviceDTOClientRepeatTests.swift`:

- `hasPreRepeatSettings requires all four fields()`: frequency-only saved
  `915000` gives false; saved `(915000,250000,8,5)` gives true.
- `hasPreRepeatSettings returns false when all nil()`: all four null gives false.

Both original dynamic model cases and every other existing model/holder test
are unchanged. Supplemental consumer scenarios do not add original families.

The actual pinned `RadioPresetSection.swift:273-298` restores each saved field
independently, falling back to that field's current value, then clears the
snapshot after successful verified writing. It does not guard restoration with
`hasPreRepeatSettings`. The native holder already has the same behavior.
Consequently, the retained unsupported-adaptation inference is disproved:
this is additional consumer evidence, not a production fix or new policy.

## Admission and producer reconciliation

Fresh actual identity and genuine test-path absence were checked at start.
The supported `reserve.py admit` matched this exact test path and
`add-direct-support-wiring` to trusted `app-build-launcher`.
Supported `evolve` advanced the existing same-owner receipt from revision 9
to 10 without releasing/recreating it or changing its authorization prose,
stored identities or historical bindings. Owned deviation/evidence operations
also matched `wp-owned`. No live lock, runtime or ledger was manually edited.

The coordinator's scoped operational queue/inherited-handoff lookup found
only this session's DATA-09 packet row. Its known WP-216 grapheme, WP-203
backup and WP-404 location tests are disjoint; protected WP-303 onboarding
work is not this App settings test. This is not a global no-collision
certificate. No foreign holder, App production adapter or shared harness
is changed.

## Actual regression boundary

New file only:
`android/app/src/test/kotlin/com/meshcoreone/android/app/container/RepeatSnapshotConsumerTest.kt`.

Four named proposed native methods exercise:

1. `partialSnapshotRestoresSavedFrequencyWithCurrentCompanionsThroughRealSettingsAndRoom`
2. `completeSnapshotRestoresAllSavedFieldsThroughRealSettingsAndRoom`
3. `absentSnapshotRestoresCurrentFieldsThroughRealSettingsAndRoom`
4. `failedRepeatVerificationKeepsPartialSnapshotAndReportsErrorWithoutClaimingRollback`

The tests use the existing `RoomProcessTest` and `ContainerHarness`, production
`AppSettingsFeature` dependencies, the real `RadioPresetStateHolder`,
`SettingsRadioAdapter`, verified `SettingsService`, editor/event consumers and
Room repositories. The only radio variation is through the existing
per-radio `respond` hook in this new file. Foreground remains active; actual
synced readiness and channel indices `0..7` are required. No shared fixture
defaults, cancellation, retry policy or production behavior is changed.

Current tuple order is frequency, bandwidth, spreading factor, coding rate.
The live tuple is `(910525,250000,10,5)`. Independent source expectations:

| Snapshot | Saved tuple | Completeness | Verified write with repeat false |
| --- | --- | --- | --- |
| Partial | `(915000,null,null,null)` | false | `(915000,250000,10,5)` |
| Complete | `(868000,125000,9,6)` | true | `(868000,125000,9,6)` |
| Absent | `(null,null,null,null)` | false | `(910525,250000,10,5)` |

Literal expected wire packets, derived from pinned
`MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift`'s documented `0x0B` layout, are
`0b38f60d0090d003000a0500`, `0ba03e0d0048e80100090600`, and
`0bbde40d0090d003000a0500`, respectively. They are not produced with the
candidate packet builder or nullable-fallback implementation. The actual
firmware-facing write and subsequent self-info/device readbacks are asserted.

The partial snapshot must survive repeated real repository reads unchanged
before the action. Its false completeness predicate must not prevent the
saved frequency from reaching the verified write. Successful restoration must
persist the expected RF tuple and repeat false, clear all four saved fields,
and retain every other raw device column, stable identity/public key, a second
radio's entire device row and a nonempty unrelated contact row.

The failure scenario accepts the requested RF write but reports repeat still
enabled during actual verification. It requires the exact typed verification
error, error UI state, reverted switch, released write gate, no retry alert,
and unchanged persisted snapshot. Firmware RF may have changed: there is
explicitly no rollback guarantee.

## Verification state at source-only handoff

This file records the source-stage boundary, not a passing native result.
The four new methods have not been run, compiled or accepted here.
No prior 12,993-case result or six DATA-08 oracle outcomes are transferred.

The following source-only commands all completed with exit 0 before the
frozen-head handoff:

```text
git diff --check
python -B tools\android-port\portmap.py
python -B tools\android-port\controller\validate.py
python -B tools\android-port\controller\verification_config.py --check
python -B android\parity-tests\semantic.py --check
python -B android\parity-tests\audit.py --check
```

The provenance scan found 1,927 implementation dispositions; manifest validation
reported 1,866 reference inputs, 64 WPs and 178 dependency edges. Verification
configuration was valid; semantic/provenance checks passed. These are source
catalog checks, not discovery or execution of the four new native methods,
original-family acceptance, or a new inventory audit.

Documented native tasks include `:app:testDebugUnitTest`,
`:core:model:test`, and `:feature:settings:testDebugUnitTest`; the actual
future complete task closure must come from the then-current trusted selector.
No task filter, raw Gradle invocation or execution grant is implied.
The documented environment preflight invokes `java -version`; it is deferred
under the explicit source-only/no-JVM gate rather than reusing old readiness
as a fresh PASS. No provisioning, native/JVM guard, push or publication has
been performed for this follow-up.

## First guarded attempt and bounded API correction

The source-stage boundary above is historical. The exact first candidate
`e8896c2ecbfbd92297784db8fd4d8d502326412c`, tree
`6937e5cc4894647330b912d82dc6d84950ded333`, subsequently received source
approval and a distinct single guarded-execution grant. That attempt failed;
it is not native acceptance.

From the unchanged trusted runner at
`/ai_cache/meshcoreone/repairs/902ae61f/trusted-runner-047ecbfb`,
the actual command was:

```text
/ai_cache/meshcoreone/python/3.12.4/bin/python3.12 -B tools/android-port/local/check.py --commit e8896c2ecbfbd92297784db8fd4d8d502326412c --affected-base 047ecbfb0f8837109a3aa1ff3fd4f522ca868a18 --parallel-jvm .
```

It used the explicitly isolated canonical environment and the complete actual
047-derived 31-path plan: full-scaffold fallback, 13 requested tasks and all
four stages, without caller scope or trusted-base overrides. Python's actual
70 controller-fixture tests and preflight passed. Scaffold failed at
`:app:compileDebugUnitTestKotlin`: this new test's line 98 referenced the
nonexistent `SyncCoordinator.syncState`. Inspect did not run. The guard and
attached shell both exited 1. Gradle reported 641 actionable tasks:
28 executed, 16 from cache and 597 up-to-date.

None of the four new App methods compiled or ran. Both exact original model
cases were uniquely present and passing in the actual `:core:model:test`
FROM-CACHE XML; none of the 12 existing holder controls ran. Selected-suite
raw XML and the original task log showed data's 437 and services' 2,705 cases
from executed tasks, with zero failures/errors/skips; other reached selected
test tasks were from cache. Thirteen selected suites, including App and
settings, had no results and did not run. These partial outcomes are not a
passing complete plan or transferred successor acceptance.

The original log remains at
`/ai_cache/meshcoreone/repairs/902ae61f/wp201-data09-eaf30961-e8896c2e/guarded-e8896c2e-047.log`.
Its actual reports are under lane
`3ef4c5ed8f5cdc142f27b13d76551b492e5a9f8e961211dbe6ae05782b828fcc/1/run-bUDignj8`.
The installed runner did not harvest failed-run XML. Raw snapshot XML was
consumed directly while the exact head/tree and clean source were verified
before and after. The supported owner
`local-check:64ac6825c1a94300af3711f3eb7c9134` was released; guard PID 541932
and `/tmp/meshcore-execution-hdbhobjx` were absent. Trusted runner source,
default refs and its 47-file installed runtime remained unchanged.

The source-only successor corrects only the new test's property access to
`syncCoordinator.state`. The actual receiver is the existing
`SessionRegistry.current: RadioSessionContainer?`; its coordinator exposes
`state: SyncState`, also used by the unchanged foreground channel and RX
consumer tests. The Synced condition, settings-startup readiness, foreground
operation, exact eight channel requests and all persistence, identity and
failure assertions are retained. No production API, shared harness, foreign
holder test or policy changes.

This correction has not received a new native execution or publication
grant. The failed candidate and its history remain immutable; all 18 required
source identities still require complete, source-bound successor verification.

## Second guarded attempt and bounded startup fixture correction

The exact corrective candidate
`f95bfa1de6fea9dd28284be183abec79be78cc0c`, tree
`9c093ea72667795d0443a0c9d6cf9943da315a70`, received source approval and a
distinct single whole-plan execution grant. Its original canonical command was:

```text
/ai_cache/meshcoreone/python/3.12.4/bin/python3.12 -B tools/android-port/local/check.py --commit f95bfa1de6fea9dd28284be183abec79be78cc0c --affected-base 047ecbfb0f8837109a3aa1ff3fd4f522ca868a18 --parallel-jvm .
```

The same unchanged trusted runner used the complete validated 31-path plan,
all 13 requested tasks and all four stages, without caller filters. Python's
70 controller fixtures and preflight passed. Scaffold failed at the actually
executed `:app:testDebugUnitTest`; inspect did not run. Guard and attached
shell both exited 1. Gradle reported BUILD FAILED in 19m44s, with 831
actionable tasks: 19 executed, 25 from cache and 787 up-to-date.

All four new App methods compiled and were uniquely discovered, but all four
failed during `h.manager.connect` at line 96, before Synced/channel readiness
or any restore assertion. Each original XML failure was
`CancellationException: Superseded connection generation`, with the same
cause trace through `ConnectionManager.requireCurrent:1404`,
`establish:582`, `connectAttempt:358` and this test's `restoreSnapshot:96`.
All four full traces were consumed; there were no suppressed entries and
XML stdout/stderr were empty. No recorded wire/correlation event in that
XML proves the initiating cause.

Of the 27 selected report modules, 26 had XML; core/testing was missing and
did not run. Raw selected XML contained 13,004 cases: 13,000 passing and
four failing, with zero errors/skips. App contributed 1,625 passing and
four failing cases. Settings actually executed 296 passing cases, including
all 12 required holder controls. Model's 166 passing cases were FROM-CACHE,
including both uniquely matched original model names. Thus all 18 required
identities were uniquely present: four new App failures, 12 executed holder
passes and two cached model passes. This is not complete-plan acceptance;
no passing control result transfers to the successor.

The original log remains at
`/ai_cache/meshcoreone/repairs/902ae61f/wp201-data09-eaf30961-f95bfa1d/guarded-f95bfa1d-047.log`,
with original reports in the same lane's slot 1, `run-miPvZbOB`.
Failed-run XML was consumed directly from the original snapshot, with
exact source head/tree and clean state checked before and after.
Own supported lock `local-check:605a3d36064b431c842c4899ab14b30d`,
guard PID 561338 and `/tmp/meshcore-execution-oe97p3du` were absent after
closeout. Canonical 047 source, default refs and 47-file runtime were unchanged.

Read-only source diagnosis found a test-local protocol mismatch: this
fixture advertises repeat support, so real connection startup requests
allowed repeat frequencies with `0x3c`. Without a local handler the shared
firmware returned generic OK (`0x00`), not the `AllowedRepeatFreq` reply
required by the actual session. A pending typed query can time out, emit
`CorrelationUncertain` and trigger generation recovery; that source-derived
path is consistent with the observed cancellation, not recorded wire causation.

At the immutable source pin, `PacketCodes.swift` assigns request `0x3c` and
reply `0x1a`; `MeshCoreSession+DeviceConfig.swift:119-123` matches that typed
reply, and `Parsers+Network.swift:88-100` accepts an empty range payload.
The native parser and session match those contracts. The bounded successor
adds only a local `0x3c` response of `[0x1a]` and asserts exactly one actual
startup `0x3c` in `radio.sent` before the restore action.

Repeat support remains true; real foreground/connect/Synced/startup-read
readiness, all eight channel requests and every restore/persistence/failure
oracle remain unchanged. No production recovery, timeout, cancellation,
shared harness or default response is modified. Fresh trusted admission
retained the same owner and evolved revision 10 to 11 by adding the exact
owned evidence path alongside its existing prefix; identities, historical
bindings and authorization prose were preserved.

The six declared source-only commands listed above were repeated for this
correction and all exited 0. Removing the two added Kotlin lines reproduces
the parent's entire canonical Git/LF test content; all 18 exact source
identities remain unchanged. This source comparison is not native execution.

This successor is source-only and has not been compiled or run. Fresh
readiness, complete trusted selection, guarded execution and publication
remain separately gated. Both failed candidates and their histories remain
immutable; no DATA-08 or failed-attempt result is successor acceptance.
