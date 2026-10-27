# WP-203 evidence contract

## Current pickup: retained R118 repairs (issue #382)

Reconciled on `1c1f3b451b25200432ecab09cb41397ae612e4af` against frozen
source `db14559b39d32322b06477c6ae676112f583db50`. This is a documentation-only
handoff, not another implementation of the nine accepted concerns, a fresh
native run, or completion of WP-203/release acceptance. The initial-contract
sections below and the source-freeze execution boundaries in
[fixture widths](fixture-width-correspondence.md) and
[restore lifecycle](restore-lifecycle-correspondence.md) are historical.
Their `Pending`/`NOT RUN` statements must not be read as the current status
of the subsequently merged repairs.

The repairs landed in [#342](https://github.com/cbattlegear/MeshCoreOne-Android/pull/342)
(DATA-11/14/15/16/17),
[#359](https://github.com/cbattlegear/MeshCoreOne-Android/pull/359) (DATA-05),
[#363](https://github.com/cbattlegear/MeshCoreOne-Android/pull/363) (DATA-18)
and [#366](https://github.com/cbattlegear/MeshCoreOne-Android/pull/366) (DATA-19).
All four merge commits are ancestors of this pickup base. DATA-34's existing
inert backup fields are preserved; translation remains removed from active scope.

| Concern | Preserved assertion/consumer boundary |
| --- | --- |
| DATA-05 | `BackupTransactionTest` exercises actual precommit rollback and postcommit caller cancellation with/without preferences. All five `BackupRegistrationTest` methods cover real service-to-UI Success, later UI cancellation, precommit Cancelled, preference-only retry, and explicit committed refresh failure. |
| DATA-11 | `BackupChannelIntegrationTest` encodes/decodes all three current flood modes and removes each legacy favorite/notification/mention key from true/muted/5 independently. |
| DATA-14 | `AppBackupEnvelopeTest.oversizedBodyIsRejectedByParserBeforeDecompression` sends 52,428,801 bytes through `parseBackup`, requiring typed compressed-size failure despite invalid DEFLATE and a zero expanded cap. Exact-cap and streaming expanded-limit controls remain separate. |
| DATA-15 | `AppBackupServiceTest.sourceSensitiveRedactionIsComplete` compares explicit safe values, including spreading factor 10/coding rate 5, and the unchanged original live row; expected values are not produced by the redaction helper. |
| DATA-16 | `redactedDeviceRoundTripHasSafeDefaults`, `crossBundleContactUsesLocalIdentity` and `orphanDmCanBeAdoptedAfterCrossRadioRestore` retain separate real Room source/destination stores, actual codec bytes, parent remapping and unchanged source data. |
| DATA-17 | `BackupFieldIntegrationTest` retains sent/failed outgoing and failed-self legacy omission cases, earlier sort date 1600000000 versus creation 1700000000 through codec/Room, and altitude 42 with latitude 37.7749/longitude -122.4194. |
| DATA-18 | D026 keeps exact 33-byte contact keys; D152 keeps exact 32-byte channel secrets. Separately named 32-byte-key/16-byte-secret supplements remain. Seven other channel families already match the pinned 16-byte source; raw backup bytes and live-wire projection are distinct. See the width correspondence. |
| DATA-19 | D069 requires the actual staged transaction/hook and original terminal failure, rollback and a subsequent same-store write. D083 refetches an immutable DTO; D148 observes successful transaction completion and later writability. See the bounded technical lifecycle disposition, not a new human approval. |
| DATA-34 | `BackupPreferenceTest` retains the five language/overlay round-trip and missing-key families, including independent DataStore fields. These are legacy backup data, not a translation provider, model, UI entry point or future release dependency. |

### Authoritative hosted evidence, not transferred acceptance

The exact-head [run 40459946828, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40459946828/attempts/1)
for #366 binds head `05e7f10f6425d0b88d1e7ab8b6951c4b7b906d6e` and base
`cf1d4cd316190c46c29eaf85d37701729e521729`. Its successful
[Linux job log](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40459946828/job/122043190664)
records 440/440 core/data, 45/45 database and 1,624/1,624 App methods,
with zero failures, errors or skips in those suites. Direct comparison of
its complete named core/data methods to the current declarations found all
221 backup methods exactly once. The complete core/data list also contains
all five DATA-19 required methods (three originals plus two support controls,
one of which is a repository test). The five App registration
methods are also present. The static collector separately finds 171 original
bindings; that number is not added to native execution counts.

That run also has a successful
[SwiftData restore/export job](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40459946828/job/122052636777).
The generic `Swift codec oracle` job was skipped, not passed; the selected
backup-specific SwiftData oracle ran successfully. This supplements the
older cross-direction history below, without claiming physical iOS/Android
execution or fresh compatibility for this documentation candidate.

At the pickup base, a Git comparison with the tested #366 head finds no
changes in backup production/tests, `AppBackupFeature.kt`,
`BackupRegistrationTest.kt`, or the Settings backup consumer directory.
There ARE subsequent repository-test and diagnostic database changes outside
that bounded comparison. Unchanged backup bytes do not establish a current
whole-module, dependency, process-graph or whole-WP PASS.

Current lightweight checks and the blocked local readiness/reservation attempt
are recorded in [the pickup deviation](../../deviations/WP-203.md#issue-382-pickup-reconciliation-2026-10-27).
Canonical feature verification is still unconfigured; current trusted software
CI and its exact-candidate logs remain separate from full-WP acceptance.
Do not close #382 on the strength of this handoff: current-candidate bidirectional
codec/restore evidence and applicable device/release gates remain explicit.
No new CI-result bundle, copied XML or replacement historical receipt is retained.

## Initial contract and execution history

Repository `cbattlegear/MeshCoreOne-Android`; owner `data-persistence-engineer`.
Initial base `e697823c8937eb5b12a40362ca2c5aae4c45f56a`.
Frozen source `db14559b39d32322b06477c6ae676112f583db50`,
tree `8918fdc604341e6996a68c88f6bb1c02b9c2f87e`.
Manifest `78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746`;
semantic policy `56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a`.
Receipt `autonomous-WP-203-e697823c` binds the initial owner and subsequent
explicit A/B/C/D producer amendments. It does not publish a formal gate.

## Declared commands

All local Java, including preflight, is invoked inside the coordinator's
private `Invoke-MeshCoreNativeBuild -Owner WP-203` gate. Each invocation sets
the read-only approved SDK/JDK paths and this owner's private Gradle user,
Gradle project and Android user caches. The existing
`android\scaffold\invoke-gradle.ps1` strips credentials. Local budgets are
512MiB build heap/metaspace, 256MiB test heap/metaspace, one worker, in-process
Kotlin, SerialGC and two active CPUs. A busy/capacity refusal is **BLOCKED**,
not a successful runner result.

| Command/task | Purpose | Result |
| --- | --- | --- |
| `:core:data:dependencies --write-locks --dependency-verification strict --quiet` | Generate only the admitted owner-local dependency state | Passed through registered private gate: five existing serialization1.7.3 records gain four compile configurations each; no new version or shared content delta |
| `:core:data:compileDebugKotlin --dependency-verification strict --quiet` | Compile the actual production backup and admitted producer seams | Pending |
| `:core:data:testDebugUnitTest --dependency-verification strict --no-build-cache --rerun-tasks --quiet` | Complete actual Room/codec module suite, not a map surrogate | Historical Linux run37332274169/head852792fe: 348 passed, zero failed/errors/skips; final-head refresh required |
| `:core:data:verifyBackupTests --dependency-verification strict --quiet` | Fail-closed original-family and raw nonzero/unskipped backup proof | Same actual Linux run: 194 native backup cases / 171 distinct original bindings passed |
| `:core:data:verifyPersistenceRepositoryTests --dependency-verification strict --quiet` | Preserve original WP-202 producer behavior and separate all-module evidence | Same actual Linux run: 154 repository methods / 208 original dispositions passed separately |
| `verifyScaffoldTests validateModuleGraph --dependency-verification strict --no-build-cache --rerun-tasks --quiet` | Complete native integration/graph regression proof | Pending |
| `:core:protocol:test --dependency-verification strict --no-build-cache --rerun-tasks --quiet` | Complete frozen protocol regression proof | Pending |
| `:app:assembleDebug lintScaffold --dependency-verification strict --quiet` | Debug APK/native lint; no release or device claim | Pending |
| `python .\tools\android-port\controller\validate.py` | Frozen ownership/manifest graph validation | Passed before edits: 65 WPs, 185 edges, eight gates |
| `python .\tools\android-port\test_inventory.py --check` | Frozen original inventory drift | Passed: 468 paths, 5,133 declarations; no feature-pass claim |
| `python .\tools\android-port\extract_vectors.py --check` | Independent immutable vector drift | Passed: 49 immutable vectors |
| `python .\docs\android\evidence\WP-203\collect_evidence.py --inventory-only` | Original case/native declaration binding | 171/171 original bindings, 194 native declarations; not execution |
| `python -m unittest discover -s .\docs\android\evidence\WP-202 -p test_collect_evidence.py -q` | Frozen producer collector guards | 19 passed, zero failures/errors/skips |
| `python -m unittest discover -s .\docs\android\evidence\WP-203 -p test_collect_evidence.py -q` | Raw backup evidence guard negatives | 11 passed, zero failures/errors/skips; includes initial-versus-effective immutable base guards; synthetic inputs not native proof |
| `python -m unittest discover -s .\tools\android-port\oracle\tests -p test_wp203_*.py -q` | Cross-direction artifact/source/XML guard negatives | 20 passed, zero failures/errors/skips; not Swift execution |
| `python .\tools\android-port\controller\workflows.py` | Declared workflow trust-boundary assertions | Passed; no live publisher/gate claim |
| `python .\tools\android-port\controller\verification_config.py --check` | Unchanged semantic manifest/overlay policy | Passed; feature acceptance remains unconfigured in canonical manifest |

The admitted new hook does not count the existing 154 repository tests as
backup proof. Every original backup declaration/family must bind to an
actually executed native assertion. Source headers are traceability only.
Complete raw XML and test input blobs are also independently retained by
the existing whole-module CI reader.

The subsequent actual gated targeted command
`:core:data:testDebugUnitTest --tests
com.meshcoreone.android.core.data.backup.AppBackupEnvelopeTest
--dependency-verification strict --quiet` passed production compilation,
then failed unit-test compilation because a protected test helper exposed
the internal hook type. The helper is now internal; that repair is not
reported passed until re-executed. A single later repair probe was refused
as `NATIVE_BUILD_BUSY` before starting Gradle. No filtered suite, compile
failure or resource refusal is complete native or original-case evidence.

The real full historical local run discovered 348 methods, passed 347 and
failed one added exact-cap-prefix assertion, with zero skips. The codec now
delivers the entire permitted prefix and probes a private single overflow
byte; it never exposes that byte to a reader. The prior real non-JSON bomb
failure is also repaired without whole expanded-buffer allocation: a known
JSON/UTF-8 decode failure drains the still-bounded inflater so source
decompression/size error precedence remains exact. The subsequent actual
Linux producer run passed all 348 methods and retained the complete raw
module XML, 171 original bindings, input blobs and measured export hashes.

## Real cross-direction boundary

The three-job admitted candidate workflow has genuinely executed an actual
Room Kotlin export, frozen macOS Swift decoder and **SwiftData restore/export**,
then actual Room restore of that Swift output. Run37334739229/attempt1 on
`69173e343346c5d0d557a8a027344ee498ca66f2`, base064f091c, passed all three
jobs with complete same-head/base/source/run/attempt XML/input/data hashes.
Both native stages discovered/passed all 348 methods, including 194 backup
methods and 171 original bindings. The actual frozen source XCTest passed
one mandatory case with real XML and independent framework unskipped trace.
Both restores inserted twelve records, preserved semantic fields/identity,
restored preferences and inserted zero on re-import. All three downloaded
bundles were independently reparsed, not accepted from a green badge.

This completed cross-direction proof is historical after the actual
Linux-only foundation integration. Final-head ordinary root/protocol/APK,
fresh oracle and independent review still need to bind to current main.
Windows source staging, candidate-only round trips and the existing WP-004
fixture never substituted for real execution. Only controlled data, XML,
logs and source provenance are uploaded, not executable artifacts,
credentials or user content.

Actual new pipeline commands are
`python tools/android-port/oracle/wp203_ci.py producer --state <private
environment.json> --output <new runner-temp output>`,
`python tools/android-port/oracle/wp203_interop.py --input <same-run producer
data> --output <new runner-temp output> --stage-root <new frozen-source
directory>` and `python tools/android-port/oracle/wp203_ci.py consumer
--state <private environment.json> --input <same-run Swift data> --output
<new runner-temp output>`. Each native stage executes the existing module's
actual `verifyBackupTests`, `verifyPersistenceRepositoryTests` and
`validateModuleGraph`, with strict dependency verification and forced tests.
Swift executes existing WP-004 codecs plus real `swift test --package-path
<isolated MC1Services> --filter WP203InteropTests --parallel --num-workers 1
--disable-swift-testing --verbose --xunit-output <raw XML> --scratch-path
<isolated build>` on frozen production code. This uses exactly one XCTest
worker, not parallel agent work.

Run37332274169/head852792fe actually compiled the full frozen source and
executed the owned SwiftData XCTest with **one test / zero failures**,
restoring Kotlin's export and producing genuine Swift export/proof bytes.
Its pipeline still failed closed: Darwin SwiftPM's non-parallel XCTest
runner does not emit the requested xUnit file; only a separate zero-case
Swift Testing report was emitted. That zero report was **not accepted**.
The real SwiftPM parallel runner is required to produce actual XCTest XML.
Its measured generator has no skipped-count attribute, so the reader also
requires the hash-bound framework trace identifying the exact mandatory
test as passed and `Executed 1 test, with 0 failures (0 unexpected)`.
Missing/renamed/zero/skipped/malformed evidence remains blocked.
The consumer was skipped in this historical failed pipeline, so this does
not claim completed bidirectional acceptance. Raw measured hashes are in
`hosted-history.json`.

The producer separately generates only its admitted module-local lock with
the declared `:core:data:dependencies --write-locks` command. Any real local
lock delta is retained as mechanical data and **blocks** exact-head native
acceptance until committed; no generated working-tree state is falsely
bound to an unchanged candidate. Missing/zero/skipped or stale-hash data
stages cannot progress through the dependent jobs.

## Effective build-host direction

The maintainer explicitly replaced the Windows/Linux requirement with Linux
CI and WSL local development. The actual PR27 merge
`05a6b0f830de9f803f04674f6275073dfabfd9b1` was independently verified before
integration into this owning branch. No WSL installation/global configuration
occurred, and no new local Windows JVM retries are required. Earlier Windows
results/refusals remain historical records, not new Linux evidence. Actual
macOS SwiftData interoperability and physical/API31-37, HIL, licensing and
signing gates are not waived. Initiale697, the exact9f3 producer and later
real-base histories remain preserved.
