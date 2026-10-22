# WP-501 rollback timeout: bounded observation, not a behavioral repair

LOCAL direct successor to `ededd9d86a37af55d4285b17a72a7488e76c6c01`,
retaining unpublished1083916/ededd source comparisons. Repository
`cbattlegear/MeshCoreOne-Android`, existing issue134/ready PR165;
WP-501-behavior/boundaries/source-test-parity. No publication is authorized by
this receipt. Kotlin/Gradle listener compilation, delivery, selected-method
discovery/execution and full-WP acceptance remain UNVERIFIED.

## Established failure versus unknown cause

Public `ebf6c1db43c87a165208a6766bd995209fa66b95` own
[run39643413076](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39643413076)
completed FAILURE. The complete official Linux119373910636 log was read in
memory: line1140 reports the declared combined Gradle invocation timing out
after1799.999986625 seconds; line1141 exits1. No named FAILED test, assertion
failure, OOM or hanging method is established. Last exposed stdout includes
localization JSON and native HWUI startup, not a root cause. Classifier,
controller and SwiftCodec succeeded; SwiftData was SKIPPED and aggregate failed.
No execution credit transfers to the four rollback methods. Earlier e6
compilation failure, f60 unlocalized timeout and parent software successes
remain separate exact-candidate histories.

Actual task dependencies include App compilation/tests, contracts, Room and
included-build tests plus module dependencies. The unchanged combined command
requests verifyScaffoldTests, verifyRoomSchema, validateModuleGraph,
runtimeDependencyInventory, resolveScaffoldDependencies, :app:assembleDebug,
lintScaffold, :core:protocol:test, :core:data:verifyBackupTests,
:core:data:verifyPersistenceRepositoryTests and :core:database:testDebugUnitTest.
Timeout1800, heap512m, strict verification and task-graph guard remain unchanged.

Source establishes a diagnostic blind spot, NOT the timeout cause:
`execute` redirects stdout/stderr into its existing command log. On failure
`print_failed_command_log` streams that log, prints named failure contexts and
only its final12000 characters. Earlier quiet-visible task/method/phase markers
can therefore disappear from the official failure excerpt. Missing markers
there do not prove App compilation or tests never started. No behavioral
repair to the Room/session/service fixture is justified by this evidence.

## Smallest observation change

The existing App TestListener additionally emits quiet START and actual
END/result callbacks ONLY for the rollback class and these exact four methods:

- `originalSuccessfulCommitRemovesTheRealResurrectedRowWithoutDiscoverOrNotification`
- `originalReAdvertClearsTheDeleteAndKeepsTheReaddedRoomContactAndNewHistory`
- `originalFailedCommitRollsBackItsRealEarlyWriteAndCannotRequeueTheDeletedKey`
- `originalCancelledTeardownStillRollsBackTheRealCommitLandingAfterStop`

Plain, (), [31] and [31]() descriptor spellings are explicitly admitted.
Existing failure/stack handling and path-recency output filtering are retained.
No arbitrary rollback/unrelated test stdout is forwarded.

The existing streamed failure-log scan retains at most128 exact fixed lines:
the existing three App task START/END identities, existing two path-recency
methods and16 phase START/END identities, and the four rollback method
START/END/results. Full-line whitelist equality excludes arbitrary classes,
methods, phases, payloads, results and overlong lines. Only CR/LF terminators
are removed. Retained lines preserve original order; the replay header exposes
seen/retained counts and truncation and says `not-test-evidence`.
Existing named failure contexts, final tail, bounded reads, original exceptions
and deadlines remain intact. Replay can repeat a line also present in the
original tail; neither occurrences nor START/END markers replace JUnit evidence.

Four added pure-controller fixtures exercise the real execute timeout branch
with mocked subprocess, markers before the12KB tail, exact original
TimeoutExpired/1800 preservation, no invented completion, rejected identities/
payloads/overlong lines, bounded129-to128 replay/order/truncation, and actual
App/controller method/phase whitelist agreement. All incumbent tests remain.

This first observation step can show a selected method was reached and whether
its completion callback arrived; it adds no internal rollback fixture phases.
An unfinished method still needs subsequent bounded localization. An unrelated
test/task, unexpected descriptor or marker delivery/flush problem remains
possible. Complete mandatory positive reports and all four exact method
identities in the eventual candidate's OWN automatic run remain required.

## Admission, preservation and checks

Trusted main/base remains `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
Canonical manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`
and policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`
are unchanged/unpaused. Same actual stored owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved100->101 BEFORE edits.
Identity, immutable authorization and historical bindings are preserved.

| Exact changed path | Trusted admission/operation | Parent blob |
|---|---|---|
| `android/app/build.gradle.kts` | app-build-launcher/update-build-or-manifest | `630195588afbe6aca03c8f3f216a28dc91b1eb61` |
| `tools/android-port/controller/ci.py` | traceability-validation/add-or-update-validator-rule | `40de8da41646db40b9d7798af3cda0d500737aae` |
| `tools/android-port/tests/test_ci.py` | traceability-validation/add-regression-fixture-or-test | `f1f1f8d0bee6f5834bd71ee8986682491c95d947` |
| `docs/android/evidence/WP-501/advertisement-rollback-timeout-observation.md` | wp-owned/create, modify, record-evidence | new |
| `docs/android/deviations/WP-501.md` | wp-owned/create, modify, record-evidence | additive section only |

Nine admissions reconcile shared ownership: inherited000/003/313/407/504
producer changes, WP502's unchanged three support paths, and WP503's sole
disjoint App source-set hunk at100, not this listener at322+. No producer
code, protected policy/runtime, quarantine or installed hook is modified.
Insertion-only whole-file reversal recovered all three ENTIRE parent support
files and the ENTIRE prior deviation byte-for-byte. The original432-line
rollback fixture,234-line writer fixture and complete prior108/ededd receipts
also compare byte-for-byte with the parent. Recency/harness/helpers, assertions,
literal inputs, real transactions and cancellation/joins remain unchanged.

Preparation checks (source/pure Python only, not Android execution):

| Exact command | Actual preparation result |
|---|---|
| `python tools\android-port\controller\test_runner.py --quiet` | 389 discovered/run,387 passed,0 failures,2 errors,0 skipped: both immutable-input checks reject uncommitted ci.py versus parent HEAD; no bypass or weakening. Exact committed candidate rerun remains required. |
| `python tools\android-port\controller\verification_config.py --check` | PASS; source configuration only |
| `python tools\android-port\controller\validate.py` | PASS; source/manifest ownership only |
| `python tools\android-port\portmap.py` | PASS; source traceability only |
| `python android\parity-tests\audit.py --check` | PASS; archive consistency, not new-case execution |
| `python android\parity-tests\semantic.py --check` | PASS; historical consistency, not acceptance |
| `git diff --check` | PASS; whitespace only |

No local Gradle/compiler/WSL/native/device/readiness/lease/recovery, manual CI
dispatch/rerun, publication, new worker, results bundle, gate or merge action.
Source review precedes any failure-forward publication. Protected App-build
and shared-controller merge review, native/human/full-WP and backup
interoperability acceptance remain outstanding; ebf6 stays failed.
