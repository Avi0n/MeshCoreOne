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
