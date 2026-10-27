# WP-204 DATA-20: active vanity-search cancellation

## Current reconciliation (#381)

**Outcome: preserve the already-landed repair; correct stale publication and
verification wording.** No new failing generator behavior was established.
The authoring record below remains historical, not the current repair status.

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `data-persistence-engineer`.
Reconciliation base: `1c1f3b451b25200432ecab09cb41397ae612e4af`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision: `79f6c46a4192aeeac807d1326126eac57c6db70b36a6f011afe00ca002a48f3b`.
Session: `37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313`; reservation revision 2,
trusted `wp-owned` create/modify, no overlapping intents.
Acceptance references: `DATA-20`, `WP-204-behavior`,
`WP-204-source-test-parity`; one original family only.

### Landed identity and authoritative execution

The test repair originated in commit
`4221c4f58fa20fc040b2dfaaf633eb005db0e833` and landed through
[#345](https://github.com/cbattlegear/MeshCoreOne-Android/pull/345):
head `d9419e30171e32585e61af0e6d5b121243edaf32`, merge
`5d59917413d445d7af1fc58f7d5061a796cefa78`. The repair merge and the actual
WP-003/#4, WP-102/#12 and WP-201/#17 prerequisite merges are ancestors of the
reconciliation base. The later repair issue #384 is not declared complete.

The [exact-head hosted run 40403656506](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40403656506)
succeeded. Its **One Linux Android build** log reports 138 datastore methods
discovered/run/passed, zero failures/errors/skips, and names all 26
`KeyGenerationSourceTest` and seven `KeyGenerationBoundaryTest` methods,
including the three cancellation methods below. These are results for the
original PR #345 head, not a fresh run of this documentation candidate.
The hosted result/log is authoritative; no duplicate result bundle is added.

Before publication, `git diff d9419e30171e32585e61af0e6d5b121243edaf32 HEAD --`
with these three explicit paths confirmed no changes:

- `android/core/datastore/src/main/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationService.kt`
- `android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationSourceTest.kt`
- `android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationBoundaryTest.kt`

The Swift service and test also match their frozen source blobs. Historical
catalogs, XML, acceptance accounting and retained authoring identities are
unchanged. This reconciliation changes only three WP-204 documentation files.
The follow-up PR supplies its exact head, review and current hosted check links.

### Original family and cancellation boundaries

The [frozen original](https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Tests/MC1ServicesTests/Services/KeyGenerationServiceTests.swift#L105-L123),
`KeyGenerationServiceTests::Cancellation is respected()` (`single`), cancels
immediately and permits either a completed identity or `CancellationError`.
It does not itself guarantee that the search started. The stronger native
executing-loop evidence is retained without rewriting that source contract.

| Native method at the verified PR #345 head | Stimulus and independently observable boundary |
| --- | --- |
| [`cancellationStopsAnExecutingVanitySearch`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/d9419e30171e32585e61af0e6d5b121243edaf32/android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationSourceTest.kt#L104-L138) | The existing independent RFC8032 seed/public-key vector cannot match `AA`. A second seed request proves one unsuccessful real attempt completed. It cancels the actual search Deferred, requires propagated cancellation with the supplied message, completed/cancelled state, `finally` cleanup, no returned identity and exactly two seed calls. A third call fails immediately; the coroutine-test timeout bounds suspension. |
| [`cancellationBeforeStartNeverReachesEntropy`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/d9419e30171e32585e61af0e6d5b121243edaf32/android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationSourceTest.kt#L93-L102) | Cancel a LAZY launch, join it, require cancelled state and zero seed requests. This remains a separate genuine pre-start case, not the executing-search oracle. |
| [`cancellationFromEntropyIsNotReclassifiedAsRandomFailure`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/d9419e30171e32585e61af0e6d5b121243edaf32/android/core/datastore/src/test/kotlin/com/meshcoreone/android/core/datastore/KeyGenerationBoundaryTest.kt#L63-L67) | The entropy seam throws `CancellationException`; the service must propagate it rather than report random-generation failure or return an identity. |

The real generator runs the search in owned `withContext(dispatcher)` work,
checks cancellation before each attempt and periodically yields. The active
test exercises that loop with `StandardTestDispatcher`, not a replacement
generator. Its terminal Deferred and `finally` assertions cover the owned
operation's termination; this is not physical-thread, device or latency evidence.
The app's `IdentityKeyPort` adapter calls this generator, and
`RegenerateIdentityStateHolder` cancels its generation job, rethrows cancellation
and clears busy state in `finally`. Those consumers exist but are not executed
by these two suites; this record does not grant UI or radio acceptance.

### Current local checks and execution limitation

The installed pinned Python/JDK/SDK passed the declared `ci.py preflight`.
A requested rerun of the already-declared
`:core:datastore:testDebugUnitTest` selections for `KeyGenerationSourceTest` and
`KeyGenerationBoundaryTest` stopped **before Gradle or test execution**:
`check.execution_runtime` reported a missing installed execution-interlock runtime.
The supported `reserve.py install-runtime --owner wp204-381-37f7ca3e-runtime`
retry, using the existing canonical shared ledger, was refused because another
owner holds a JVM execution lane. No foreign reservation was cleared, replaced
or treated as stale. **Fresh native tests/build/lint: NOT RUN.**

Pre-push coverage verification therefore uses the actual hosted method discovery
above plus unchanged relevant implementation/test bytes, not a claimed new local
pass or an inferred result from an annotation. Current documentation/provenance
checks are reported separately in the follow-up PR. No new crypto, schema,
source-family waiver, global parity, platform or hardware acceptance is claimed.

## Historical authoring record

The original unpublished/unverified status, commands and execution hold below
apply to the authoring candidate at that time. They are retained for audit and
are superseded by the landed identity and authoritative hosted result above.

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
