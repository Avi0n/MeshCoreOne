# WP-204 DATA-20: active vanity-search cancellation

Status: **UNVERIFIED local candidate; not published or accepted.**

Repository: `cbattlegear/MeshCoreOne-Android`.
Branch: `cbattlegear-wp-204-active-key-search-cancellation`.
Session/reservation: `017ff00e-6250-4088-b512-d22d36d63f29`, revision 1,
capability `wp-owned`, operation `modify`.
Base/current HEAD at authoring: `23c2d985dca3d27dd2b819fb54babe66280077f5`.
Source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision: `46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
This bounded batch is preserved in a local-only commit; its exact identity is
reported separately by `git rev-parse HEAD` after commit creation. No push or PR
is authorized at this persistence step. Later verification must bind the complete
frozen committed inputs, not this authoring base or an earlier working-tree view.

**Kotlin test execution: NOT RUN. Build/compilation: NOT RUN. Android lint:
NOT RUN.** Local product execution remains held. The coordinator reports that
session `4748` owns the sole serialized final policy suite; that is not product
test evidence for this batch.

## Scope and current relevance

DATA-20 is the single assigned root-cause group, source pointer
`DATA-BACKUP/workstreams/19`. The read-only consolidated R118 input matched
SHA256 `62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
The custom profile injection failed; the tracked data-persistence-engineer
profile was read and the Android-port and Swift-to-Kotlin skills loaded.
No profile activation or settings change is claimed.

Merged prerequisite commits were confirmed through GitHub and local ancestry:
WP-003 PR4 (`979dd73b2fc3cb5cbeea07b1c809f5c9283289ee`),
WP-102 PR12 (`79bd7790edb174ed702010b98ee09b2674b70a3e`),
WP-201 PR17 (`dc15f1ba445acf3230383ea68d4827c592f3fafa`).
The source service/test have no changes against the immutable source pin.
Current native inspection reproduced the oracle gap: the original-case mapping
cancelled a LAZY launch and asserted zero seed calls.

Acceptance references: `WP-204-behavior`, `WP-204-source-test-parity`.
The existing source-case annotation now maps to
`KeyGenerationSourceTest.cancellationStopsAnExecutingVanitySearch`.
The existing pre-start test remains as
`cancellationBeforeStartNeverReachesEntropy`; the entropy-thrown cancellation
case in `KeyGenerationBoundaryTest` is unchanged.

The new test uses StandardTestDispatcher and the independently known RFC8032
seed/public key. Its `AA` mismatch is asserted before execution. At the second
seed request, after one failed attempt, it cancels the real Deferred search job.
Assertions require cancellation propagation, terminal cancelled state, finally
cleanup, no returned identity and exactly two seed requests. A third request
fails immediately; a ten-second coroutine-test timeout bounds suspension.
No production change was justified by an executed defect.

## Checks and blockers

| Command/check | Actual outcome |
| --- | --- |
| `python tools\android-port\local\reserve.py claim --wp WP-204 --session-id 017ff00e-6250-4088-b512-d22d36d63f29 --authorization <unchanged assignment context> --base 23c2d985dca3d27dd2b819fb54babe66280077f5 --path android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationSourceTest.kt --path docs/android/deviations/WP-204.md --path docs/android/evidence/WP-204/active-key-search-cancellation.md` | Acquired, revision 1, three paths admitted by trusted `wp-owned` capability. Historical overlapping intents WP-000/WP-311 are advisory, not evidence of live writes. |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit 2: explicit `--state/ANDROID_CI_STATE` required. No Gradle started. |
| Declared WSL `ci.py local-inputs --root <session-artifacts>/wp204-readiness --jdk <declared-installed-jdk> --sdk <declared-installed-sdk>`, then `ci.py preflight --state <session-artifacts>/wp204-readiness/environment.json --output <session-artifacts>/wp204-readiness/result --local` | PASS, exit 0, using the declared local runner's Python and installed toolchain with an isolated session state. Initial preflight could not resolve the Windows worktree Git metadata; retry used explicit WSL-converted `GIT_DIR`/`GIT_WORK_TREE` for Git inspection only. Candidate environment is stripped normally. No Gradle/native execution. |
| `python tools\android-port\controller\validate.py` | PASS: 1,866 pinned reference paths, 64 WPs, 178 dependency edges; manifest digest matches reservation. |
| `python tools\android-port\portmap.py` | Initial run failed with Python MemoryError while projecting the unchanged translation-scope predecessor; next attempt interrupted without a result. Isolated retry then PASS, exit 0, 1,905 traceability entries. No validator change or weakened assertion. |
| `git diff --check` | PASS after test and documentation edits. |
| Existing `collect_evidence.py` `declared_tests()` read-only declaration inspection | 126 native method declarations, 34 original declarations; the cancellation mapping points to the active test and both other cancellation declarations remain. This is not test execution. |

The shared reservation status reports a hard `native-execution` lock:
owner `1666502a-0420-46ef-930f-e78651ba8b9f:nodes:66bd32eca2a449ac969a53a90b384c9c`,
WP-503, purpose `nodes-api37-7-original-methods`, source
`473f3860829fc0e33305c39517aef650d88b2d57`.
The assignment requires respecting actual native/Gradle interlocks. No lock was
cleared, no private ledger was edited, and no Gradle process was launched.
The coordinator has been notified for an authoritative owner cleanup/handoff.

The explicit toolchain state now passes readiness. After that interlock clears,
the declared targeted `:core:datastore:testDebugUnitTest` selections for
KeyGenerationSourceTest and KeyGenerationBoundaryTest are required, followed by
the completed-batch affected build/lint/validators and nonzero result discovery.
Relevant existing module tasks are `:core:datastore:verifyPreferenceTests`,
`:core:datastore:compileDebugKotlin` and `:core:datastore:lintDebug`.
No checkpoint push, hosted-first debugging, duplicate CI-result bundle, full
repository build, device/hardware acceptance, signing or release is claimed.
