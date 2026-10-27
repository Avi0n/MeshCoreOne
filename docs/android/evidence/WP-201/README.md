# WP-201 historical implementation and current validation

The implementation record below, including `local-evidence.json`, is historical:
it retains the original **80 inputs, 114 families and 215 native identities**.
It is not a newly executed result or the identity of a later worker.
Repository `cbattlegear/MeshCoreOne-Android`; original owner `data-persistence-engineer`.
Session `f7b1af4d-9429-49e8-bcb0-6e3cc11cd5de`; owning branch
`cbattlegear-potential-engine`. Clean initial base:
`47822de6ff8641a6d085baf0d4993aa3c792db8c`.
Coordinator-authorized final integration/publication base:
`0394b83c9b47fa0d7198e2d631f7ddb313cd370d`.
Read-only reference: `db14559b39d32322b06477c6ae676112f583db50`.
Canonical manifest:
`78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746`.
Semantic policy:
`56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a`.

The kickoff is the exact all-write-path lease for model, contracts/domain,
database and these per-WP evidence/deviation paths. No live ledger, formal
review or protected approval is fabricated. Source/manifest/policy pins,
65 IDs/185 edges/eight human gates are unchanged. Implementation head and
normal hosted results bind through the actual PR rather than a self-referential
committed SHA.

## Implemented bounded scope

All **54 primary production + 14 test + 12 support inputs** are mapped in
[`local-evidence.json`](local-evidence.json), with immutable source blobs/hashes,
many-to-many native files, every original declaration/parameter family,
concrete passed testcase identity and exact JUnit file hashes. Header mapping
alone is not acceptance.

The pure JVM model contains full non-rendering DTOs/value utilities, original
defaults/raw values/units/options, source identity/hash algorithms, defensive
collections/binary values, source computed behavior and typed wire-field
policies. Neutral contracts include all thirteen persistence roles plus
current store extension methods, typed failures/tuples, process/radio/screen
seams and deliberate generic session-service factories. There is no fake
repository/ServiceContainer implementation or production testing dependency.

The actual initial Room database has **17 tables**, native version **1**,
identity `847554c8d7ca15f1f881ba6bccce4121`, every persistent source field, radio
keys/indexes, explicit nullable parent links, two real cascades, exact
seconds/nanoseconds, source global-cache/history exceptions and actual DAOs.
Its KSP export is
[`MeshCoreDatabase/1.json`](../../../../android/core/database/schemas/com.meshcoreone.android.core.database.MeshCoreDatabase/1.json).
Full repository/retention/adoption/delete/backup algorithms remain WP-202/203,
not unimplemented calls hidden behind a pretend store.

| Acceptance | Concrete evidence |
| --- | --- |
| WP-201-behavior | Immutable full-field DTO/Room round-trips, source computed cases, typed contract/sync/error metadata and real abstract repository boundaries |
| WP-201-boundaries | Radio isolation/upsert/Flow, foreign-key and primary-key failures/rollback/cancellation, shared-history deletion guard, original byte/UUID/width/date/legacy policies |
| WP-201-source-test-parity | Complete 114-declaration map: all114 source-behavior/native equivalents execute, including the real merged WP-103 resolver consumer |

Six Channel Codable cases are *field-policy equivalents*, not whole-envelope
compatibility. Twenty-three original persistence/ConnectionMethod cases run
actual native DAO/column equivalents, not WP-202 repositories. Seven
createDevice cases exercise the extracted actual pure source factory, not
a mock connection ceremony. Those distinctions are attached to each case.
The initially retained matchRegions integration now executes the actual merged
resolver and protocol RegionMatchResult; its temporary neutral projection was
removed rather than kept as a competing type.

## Actual commands and discovery

All Gradle invocations use `android\scaffold\invoke-gradle.ps1` with the existing
portable JDK21.0.12.1/SDK37.2 inputs, private per-session user/project/Android
caches, unchanged credential allowlist, one worker/in-process Kotlin,
512–768MiB build heap and 256MiB test heap/metaspace. No global host setup or
unpinned SDK CLI/package bootstrap occurred.

| Exact command/task arguments | Result |
| --- | --- |
| `python android\scaffold\check_environment.py` in the stripped declared environment | Passed exact JDK/SDK/license-presence/private-cache checks |
| `:core:model:dependencies :core:database:dependencies --write-locks --dependency-verification strict` | Passed owned local-lock generation with already admitted versions |
| `:core:database:assembleDebug :core:contracts:compileKotlin --write-locks --dependency-verification strict` | Passed real Room/KSP generation and Kotlin compilation; observed late androidApis lock state generated |
| `:core:model:test :core:contracts:test :core:database:verifyDomainRoomTests validateModuleGraph runtimeDependencyInventory resolveScaffoldDependencies --dependency-verification strict --no-build-cache` | Passed final repair verification: **166 + 4 + 45 = 215** actual cases; 0 failed/errors/skipped; the verify task depends on actual Room execution |
| `:app:assembleDebug --dependency-verification strict --no-build-cache` | Passed actual production packaging, including 74 executed l10n self-tests and integrated locales/crypto |
| `:core:database:lintDebug --dependency-verification strict --no-build-cache` | Passed actual Room-module Android Lint |
| `python android\scaffold\inspect_apk.py` with declared SDK variables | Passed actual debug APK min31/target37/package/notices/fixture exclusion |
| `python tools\android-port\portmap.py` | Passed real trusted provenance-disposition validator after repairing mixed headers |
| `python tools\android-port\controller\validate.py` | Passed frozen source/ownership/DAG/65 IDs/185 edges/eight gates |
| `python tools\android-port\controller\verification_config.py --check` | Passed unchanged foundation overlays; feature formal verification still unconfigured |
| `python tools\android-port\controller\runtime_inputs.py` | Passed all77 exact committed/checkout runtime inputs at final integrated base |
| `python docs\android\evidence\WP-201\collect_evidence.py --write` | Passed nonzero/unskipped XML discovery, all80 primary input mappings, 114 case accounting and actual17-table/two-FK schema checks |

Actual debug APK: `android\app\build\outputs\apk\debug\app-debug.apk`,
40,442,186 bytes; SHA256
`b95f51375da5906a703c6468ca88df3cb45f38bffdb9f1f9b2f96985714ef1ce`.
Package `com.meshcoreone.android.debug`, min31/target37. GPL/MIT/Apache pinned
notices retained; no fixture or imaginary connected/send/restore UI packaged.
This is not physical-device, signed-release or native16KiB certification.
The local evidence collector records raw XML and schema hashes; canonical-LF
schema hash permits only Git text newline normalization, never byte-value or
golden changes.

## Real failures repaired, not waived

The first compile exposed Kotlin's unavailable predicate split and AbstractMap
values-name collision; proper types/regex/backing names fixed them. A reified
intersection-array diagnostic was fixed with explicit Any? element type.
Source contract reflection now distinguishes compiler-generated static
default-argument bridges from actual abstract instance methods.

Real Room failures exposed generator-omitted JSON defaults and Windows
file-backed WAL VFS behavior. The assertions consume the actual generator
shape, and the file proof explicitly uses test-only TRUNCATE/native SQLite.
An invalid Closeable assumption was replaced by explicit awaited lifecycle
and finally-close. Actual row conflict/cancellation tests prove preserved
committed rows and absence of each uncommitted row, not a proxy count.

The real localized APK build caught conflicting PortedFrom/AndroidOnly
dispositions in owned files. These were repaired without changing the trusted
validator or localization tests; the same actual assembly then passed.
No zero/ignored suite, missing metadata, fake formal review, catch-all default,
shared pin advance or lowered golden was used.

Independent read-only review of frozen initial head
`4d7c428bf2b5a9012888a3e560b305f88b002cca` found three real defects. The new
`ReviewedSourceRegressionTest` reproduced **3 tests/3 failures** before repair
and **3 passed/0 failures/errors/skips** after the source-correct changes:
oldest-first redecryption preserves canonical path1A instead of newer2B;
direct timestamp correlation excludes a newer channel7-attributed row;
timestamp-zero fallback truncates full Unix fractions toward zero before
UInt32 checking, retaining normalized dates and max-UInt32 fractions. No
expectation, source/golden or unrelated query was changed to make them pass.

The first combined768MiB final retry hit shared-host native commit exhaustion
while starting the Room test JVM, not an assertion failure. The actual complete
45-case Room suite then passed alone at512MiB; the combined module verification
and collector also passed at512MiB without skipping or reducing any suite.

## Remaining external/consumer evidence

The coordinator-authorized module build-script hooks now attach the real model
and database suites plus `:core:database:verifyDomainRoomTests` to
`verifyScaffoldTests`, following the merged l10n pattern. The independent
collector fails on a missing, malformed, zero, skipped or unaccounted suite.
The coordinator-owned generic raw-module retention/reader amendment is
actually merged into the final0394b83 base. Schema2 normal hosted artifacts now
derive committed active-module test sources, retain complete raw XML/input
blobs and independently reparse their exact cases/counts/hashes. This same PR
must pass on the final repair head; a legacy-only green build is not retained
WP-201 original-case proof.

## Evergreen root validation and explicit writer audit

`verifyDomainRoomTests` still depends on the actual model, contracts and Room
unit runners and remains attached to `verifyScaffoldTests`. Its normal collector
command validates the **current immutable HEAD**, not every later repository
change against the original WP-201 publication base. A valid protocol,
repository, DataStore or other native-module successor is not a WP-201 writer
scope violation.

```powershell
python docs\android\evidence\WP-201\collect_evidence.py
python docs\android\evidence\WP-201\collect_evidence.py --output C:\absolute\private\current-domain-evidence.json
python docs\android\evidence\WP-201\collect_evidence.py --audit-writer-scope --base-sha <exact-requested-40-character-base>
python tools\android-port\controller\test_runner.py --quiet
```

Only the explicit audit applies the original writer scopes to the requested
ancestor base. There is no implicit historical base, and `--base-sha` alone
is rejected. The code owner/coordinator chooses the actual base for that
one-off audit; the root task does not request it.

Current output separates actual candidate HEAD/branch and, when an event
context is available, its real base/head/run/attempt from historical metadata.
Detached checkouts have no invented branch; local output claims no hosted
authority or unavailable session identity. The credential-stripped Gradle
child does not invent missing event metadata: the outer schema2 bundle binds
that same candidate's actual hosted run. `--write` is no longer supported:
current reports may be written only to an explicit absolute private path
outside the repository, never over the immutable historical record.

The frozen original 215 **identities**, not just counts, are a minimum; all
114 catalog family/source/blob/parameter identities and original DAO
annotation bindings remain mandatory. Extra executed tests are counted and
listed separately without fabricated source parity. Complete, nonzero,
unskipped raw XML is independently parsed using the existing CI helpers.
Missing, unsafe, malformed, duplicate, failed/error/skipped or inconsistent
suites fail. Current committed native source/build/schema and collector/catalog
inputs must match the checkout, including ignored uncommitted source files.
The pinned Swift reference remains read-only. Frozen Room v1 semantic content,
including every field and both exact cascade relationships, cannot change;
text CRLF/LF normalization is the only allowed checkout normalization.

`runtime_inputs.py` requires both this collector and its real temporary-Git/XML
regression suite in the committed candidate tree. Normal schema2 CI still
retains/reparses complete raw active-module suites and candidate input blobs;
this hook does not replace that evidence or upgrade historical schema1 proof.
No manifest, source, policy, production model/DAO/schema, dependency or
privileged workflow is changed by this integration repair.

See [deviations](../../deviations/WP-201.md) for explicit native adaptations.
Robolectric simulates API31; production AUTOMATIC/WAL on actual devices,
supported future Android upgrades and full persistence consumers remain their
actual owners. Initial v1 does not manufacture historical migrations.

The owned compression utility consumes the real WP-004 Swift raw-DEFLATE
artifact, matching the independent JSON bytes, high bits and Unix fractions.
This does **not** establish candidate-to-Swift export or bidirectional restore,
atomic import/preference completion, radio remapping, key loss/security,
hardware, licensing/signing or a feature-complete release.

WP-202 receives real rows/DAOs/checked mappings and full neutral interfaces;
WP-203 receives exact legacy/wire policies and raw framing; WP-207 receives
generation/factory/lifetime seams. Coordinator review/merge, not this worker's
test summary, controls their dispatch. No next work package is started here.

## DATA-01 current live-producer repair candidate

Owner `data-persistence-engineer`; session/attempt
`fc4b2e79-8993-46ae-b073-9fbad90d7635`; branch
`cbattlegear-wp-201-persist-live-ble-method`. Initial base
`263d30332940f09082709b9d38ee8877dfb9fedd` was fast-forwarded, before the first
product edit, to trusted capability prerequisite
`f2f481d2e7c2a9029808943529c98c98ef879299` (PR #334). The source pin remains
`db14559b39d32322b06477c6ae676112f583db50`; the existing manifest/source/policy
binding and immutable authorization context remain in the original advisory
record, not silently rebound or recreated.

Supported `reserve.py admit` accepted `ConnectionManager.kt` with
`persist-active-connection-method` and the exact new
`DeviceConnectionMethodPersistenceTest.kt` with
`add-connection-method-persistence-regression`. Same-owner `evolve` advanced
the actual reservation from revision 1 to 2 to 3, retaining identities/binding.
The admitted capabilities are `wp201-active-connection-method` and
`wp201-connection-method-regression`. WP-201 is the first runtime producer;
WP-214's separate transport-type forwarding must reconcile serially afterward.

The defect was reproduced by inspecting the real `establish` producer:
its Bluetooth branch passed `emptyList()` while model-only tests injected a
Bluetooth descriptor. The producer now exhaustively selects the actual target
method. The added runtime suite maps exactly these three pinned families:

| Original family | Producer-to-persistence assertions |
| --- | --- |
| `createDevice persists the Bluetooth method supplied by the BLE connect path()` | Fresh BLE endpoint stored; public-key-derived radio identity remains distinct; actual saved/current/service DTOs and last-connection identity agree |
| `createDevice merges a Bluetooth method with an existing WiFi method()` | Establish WiFi first, then BLE for the same public key; retain WiFi host/port, stable radio partition and one saved row |
| `createDevice replaces a stale Bluetooth peripheral UUID instead of accumulating()` | Exactly one fresh BLE method, retained WiFi and preferences, stable radio ID, save before stale-row cleanup |

The fixture uses the real protocol session and runtime producer with recording
in-memory persistence and simulated link/service boundaries. No Room, hardware,
native-radio or backup-oracle result is implied. These assertions supplement,
not replace or weaken, the existing source factory tests. Acceptance references:
`WP-201-behavior`, `WP-201-boundaries`, `WP-201-source-test-parity` for this
bounded DATA-01 concern only.

Current execution state: **UNVERIFIED source candidate**. Independent declared
WSL `ci.py local-inputs` / `ci.py preflight --local` passed before source edits;
this establishes installed build inputs only. After the admitted source edits,
`git diff --check` passed;
`python tools\android-port\portmap.py` passed with 1,909 dispositions, including
the new test's exact source provenance;
`python tools\android-port\controller\validate.py` passed with 1,866 reference
inputs, 64 WPs and 178 dependency edges; and
`python tools\android-port\controller\verification_config.py --check` returned
`valid`. These lightweight checks are not compilation or runtime execution.
`:core:runtime:test` is an existing declared runtime task hooked into
`verifyScaffoldTests`; it has **not run for this repair**, with zero execution
or discovery claimed. Build/lint and runtime assertions remain pending.
The installed execution runtime and retained native capacity gate are owned
by the coordinator; no Gradle/native/heavy suite, push or PR is authorized
until those gates are reconciled. Reproducible automated CI result bundles
are not committed or duplicated here.

## DATA-10 queued model-oracle batch

The next same-owner assignment covers only `DATA-10` / `D265`, `D269`, `D281`,
not RX/restore/schema or a global re-audit. Its parent is the frozen DATA-01
candidate `87e3ecce1629a659198a1313635f41caf46557ac` (tree
`8b9d0f0069a4ce8fa079c2b6d4f998d54f00f860`). The existing remote DATA-01
snapshot, active verification ticket and acceptance/publication boundary are
unchanged. No DATA-10 assertion is claimed as exercised by that earlier run.

The retained grouping artifact was read and its SHA-256 verified as
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
The actual current `SourceModelCasesTest.kt` still used shared-default SF10
snapshots in the three assigned cases. The pinned
`MC1Services/Tests/MC1ServicesTests/Models/DeviceDTOClientRepeatTests.swift`
matches the immutable source commit and explicitly uses SF8 in all three.
Supported admission accepted the owned test path with operation `modify`;
same-owner reservation evolution advanced revision 3 to 4 without rewriting
the historical authorization or replacing the record.

| Retained family | Current oracle repair |
| --- | --- |
| `clearingPreRepeatSettings sets all preRepeat fields to nil()` (`D265`) | Explicit 915000/250000/SF8/CR5 saved inputs; four nulls/false presence; absolute unchanged active and original saved fields |
| `hasPreRepeatSettings requires all four fields()` (`D269`) | Original frequency-only negative and explicit four-field positive; absolute saved values; each individually missing field remains false |
| `updating(from: SelfInfo) carries forward preRepeat settings()` (`D281`) | Absolute saved 915000/250000/SF8/CR5 after real SelfInfo update; active 906875/250000/SF11/CR8 and original input unchanged |

The original dynamic case identities remain in
`SourceModelCasesTest.clientRepeat`; the test count is not increased merely to
inflate parity coverage. No production defect is alleged, no generic model
default is changed, and no expected value comes from the implementation under
test. Acceptance references are `WP-201-behavior`, `WP-201-boundaries` and
`WP-201-source-test-parity` for this bounded oracle correction.

This is an **UNVERIFIED next source batch** until the exact new candidate runs
the declared `:core:model:test` and its affected consumer obligations. The
specific class/factory selectors are
`com.meshcoreone.android.core.model.SourceModelCasesTest` / `clientRepeat`;
the required three original families must appear in nonzero successful JUnit
discovery, with no failures, errors or skips. No additional heavy job, native
execution, remote snapshot update, push or PR was started for DATA-10.

Post-edit lightweight checks passed: `git diff --check`;
`python tools\android-port\portmap.py` (1,909 dispositions);
`python tools\android-port\controller\validate.py` (valid, 1,866 reference
inputs, 64 WPs, 178 edges); and
`python tools\android-port\controller\verification_config.py --check` (valid).
These are provenance/ownership/configuration results, not model test execution.

## DATA-01 authenticated compile failure and queued correction

The guarded remote retry for exact `87e3ecce1629a659198a1313635f41caf46557ac`
terminated with exit 1 at `:core:runtime:compileTestKotlin`. Its authentic log,
`guarded-execution-87e3ecce-python-alias-fixed.log` in the assigned remote
WP-201 lane, reports Kotlin type inference errors at lines 31 and 49 of
`DeviceConnectionMethodPersistenceTest.kt`. The expected single-subtype lists
were compared with a `SnapshotList<ConnectionMethod>`; Kotlin could not infer
the assertion's shared type. The three mandatory runtime cases did not execute.
Other partial module reports are not whole-candidate acceptance.

The correction explicitly constructs `listOf<ConnectionMethod>` for those
two expected values, retaining exact collection equality and every assertion
without casts or production changes. It is a separate successor after DATA-10
`f96d48ad598840a81e9012b9cce41a1ae176b965`; neither the failed `87e3ecce`
record nor DATA-10's unexecuted status is overwritten. The successor requires
an admitted diagnostic `:core:runtime:test` run before the full affected
runtime/model/consumer closure; diagnostic execution alone is not acceptance.

## DATA-08 initial corruption boundary candidate (superseded, unexecuted)

The new bounded assignment is `DATA-08` / `D372`, not a rerun of the DATA-01
and DATA-10 union verified and landed in PR #344. Its clean start is
`610f606baaaf38eb2a2b14cb054b18fedba58a1f` (tree
`ced3cdbdc42cd7d22dc0bd33aef7b3c43e01eb6f`). The previous candidate remains in
history; no reset, amended commit, source-pin change or foreign checkout is used.
WP-101 PR #6 and WP-106 PR #13 were checked as actually merged and their merge
commits are ancestors of this start.

The retained grouping artifact's SHA-256 remains
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
The pinned original family is
`PersistenceStoreTests::RxLogEntryDTO(from:) falls back on out-of-range stored values instead of trapping()`.
Its six stored values are route type `999`, payload type `-1`, payload version
`5000`, path length `400`, channel index `9999` and sender timestamp `-10`.
The original assertions expect flood/unknown/zero/zero/nil/nil, respectively.

Current code does not implement that fallback. `DiagnosticMappings.kt`
rejects each invalid width with `DatabaseValueException.field`.
`RoomRepositoryContext` reports and wraps the cause as a fetch failure.
Existing `ParityRxMaintenanceOriginalPredicateTest` tests the combined stored
row and each isolated converter failure. However, the actual downstream
`RxLogService.loadExistingEntries()` catches that failure and returns an empty
list, so the RX history consumer cannot distinguish corruption from a successful
empty fetch. The historical "reviewed adaptation" annotation/deviation is not
itself an approval of the source behavior change.

Before any edit, supported `reserve.py admit` accepted the owned database
converter/test scope as `wp-owned`; same-owner `evolve` advanced the existing
`fc4b2e79-8993-46ae-b073-9fbad90d7635` reservation from revision 4 to 5 for
`CorruptRxProjectionTest.kt`, preserving its historical binding and immutable
authorization context. No production projection policy was changed.

The added Room/SDK31 test candidate isolates all six exact corrupt inputs in
stored rows, repeats reads and typed field failures, checks every raw column
remains equal, and verifies a healthy row with the same ID in a second radio
partition remains readable and unchanged. A second test preserves all six
corrupt values together and checks the first typed failure repeats without
deletion, replacement or normalization. These are deterministic safety proofs
of current native behavior, **not approval or parity credit for replacing the
original fallback**.

Supported WP-201 `modify` admission actually failed for the existing
`android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/ParityRxMaintenanceOriginalPredicateTest.kt`
and
`android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/diagnostics/RxLogService.kt`:
no trusted path+operation rule. Those files were not edited. The precise
consumer capability/producer handoff and explicit fallback-versus-typed-failure
disposition were sent to the coordinator and oversight. The affected completion
gate remains blocked; unrelated authoring is not paused by this record.

Source checks passed: `git diff --check`;
`python tools\android-port\portmap.py` (1,922 dispositions);
`python tools\android-port\controller\validate.py` (valid, 1,866 reference inputs,
64 WPs, 178 edges); and
`python tools\android-port\controller\verification_config.py --check` (valid).
The new tests are **UNEXECUTED**: zero discovery/pass is claimed.
The existing declared database `verifyDomainRoomTests` depends on
`testDebugUnitTest`, `:core:model:test` and `:core:contracts:test`; exact-candidate
guarded affected verification must also include the computed consumer closure,
compile/lint and graph obligations. No raw Gradle bypass, laptop native run,
publication or PR is authorized by this source-stage evidence.

## DATA-08 settled pinned-source disposition and corrected candidate

Oversight rejected the unapproved strict-corrupt-projection adaptation and
directed restoration of the actual pinned reference behavior. A fresh read of
`RxLogEntry.swift` at `db14559b39d32322b06477c6ae676112f583db50` confirms the
six failable-conversion defaults and unknown-enum behavior; the pinned
`RxLogService.loadExistingEntries()` also catches/logs fetch failures and returns
an empty list. Therefore the earlier service-rethrow proposal and two-service
support request are withdrawn. The Kotlin service's cancellation propagation
remains intact. Candidate `8eed0b5e` was not executed or published and its
strict-failure expectations are superseded in the successor.

`DiagnosticMappings.kt` now projects route/payload through exact-width nullable
conversion plus source enum defaults, version/path through nullable conversion
plus zero, optional channel/timestamp through nullable exact conversion, and
unknown decrypt status to not-applicable. This is a read-only DTO projection,
not normalization of persisted data. Other checked database values remain
typed failures. Supported converter admission/evolution advanced the same
reservation from revision 5 to 6.

The corrected `CorruptRxProjectionTest` has three methods: all six independently
stored corrupt fields with repeated exact fallback/raw-row/second-radio checks;
the exact original compound six-value fixture and literal fallback expectations;
and valid unsigned bounds, negative/overflow widths, unknown enums and retained
RX payload nibble behavior. No candidate conversion generates the expected six
fallback values.

The existing App capability admitted
`android/app/src/test/kotlin/com/meshcoreone/android/app/container/RxLogProjectionConsumerTest.kt`
with `add-direct-support-wiring`; same-owner evolution advanced revision 6 to 7.
Its original-family-linked method exercises real Room, `RoomPersistenceStore`,
the connected `RadioSessionContainer.rxLogService`, the production App
`ToolsRxLogFeedAdapter` and `RxLogStateHolder.subscribe`. Repeated loads must
retain the original ID, the six literal fallback values, a healthy max-width
companion, exact group counts, and all underlying raw rows including a same-ID
row in a second radio partition. No feature/service production file or test
dependency was changed.

The exact two-test support rule landed in PR #352 as
`a1942a9c40e2cb37c31466cd0bb320d7c2b9eb95` and was merged into this branch
without changing the original repair commits. Supported admission accepted
`restore-rx-projection-source-regressions` for the two exact existing paths;
same-owner evolution advanced revision 7 to 8 to 9 without recreating the
reservation. Only the first corruption methods/annotations and genuinely unused
imports in `RxLogSourceTest.kt` and `ParityRxMaintenanceOriginalPredicateTest.kt`
were changed. The actual repository now must return compound and isolated
fallback rows alongside a healthy max-width companion, repeatedly preserving
the entire raw entities and producing no spurious issue reports. All other
methods and the existing primary provenance remain unchanged. No generic
data/services authority is inferred.

This corrected source batch is **UNEXECUTED**, pending one exact-candidate
guarded affected run; no previous DATA-01/DATA-10 pass is rebound as acceptance.
After the two-test integration, `git diff --check`, `portmap.py` (1,923
dispositions), manifest/configuration validation, `semantic.py --check` and
`audit.py --check` passed. A byte-for-byte suffix comparison against the trusted
base also confirmed every non-corruption method and the primary provenance in
both existing data tests remained unchanged. These are source-stage checks, not
JVM/Room/App execution evidence.

Corrected source-stage checks passed: `git diff --check`;
`python tools\android-port\portmap.py` (1,923 dispositions);
`python tools\android-port\controller\validate.py` (valid, 1,866 references,
64 WPs, 178 edges); and
`python tools\android-port\controller\verification_config.py --check` (valid).
