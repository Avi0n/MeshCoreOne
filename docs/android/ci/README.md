# WP-003 isolated scaffold CI

**Supervised dependent draft, not accepted WP-003 or activation.** The build
workflow runs real scaffold commands. Dispatch, shepherd and gate-audit workflows
remain manual previews and deliberately return `BLOCKED` for absent fleet/merge
integrations. The manual review-publication workflow now has a supported native
GitHub transport, described below; deploying it is not fleet activation.

The user-authorized WP-503 language-cohort pause correction sets policy
`paused:false` only. Dispatch stays `off`, activation stays unapproved, and
all source/device/preservation/native-lane and human/protected gates remain.
The existing WP-503 owner must refresh deployed main before its separately
guarded execution; a candidate checkout is not live authority.

## Candidate execution contract

### Local-only disposable WP-503 guest contract

The successor to published `ad40165c93d1bc21e073f91102971719b078ad02`
is a source proposal, not present admission, deployment or execution authority.
Recovery of the original guest/snapshot is NOT a prerequisite for this fresh
guest path. Its existing native row, receipt, runtime and history remain intact;
this is neither recovery/reentry nor a relabelled physical/existing device.

The proposed `disposable-native-guest` capability admits only WP-503,
`tools/android-port/controller/disposable.py`,
`execute-private-disposable-avd`. The only additional closed resource is
`disposable-native-execution`. Actual admission comes from the immutable trusted
origin default-base policy/manifest, never the candidate's policy. All existing
product rules and the manifest remain unchanged; removing this exact rule and
resource restores the entire ad40165 policy, including its canonical Git blob.

After separate source/trust admission and same-owner typed reservation evolution
for that exact path/operation, a dedicated fresh native worker can use:

```python
from controller.disposable import trusted_guest
from local.debug_packaging import assemble_debug_pair

ledger, guest = trusted_guest(
    repo, private_parent, owner=fresh_execution_owner, revision=exact_head,
    session_id=existing_wp503_session, attempt=existing_wp503_attempt,
)
try:
    packaging = assemble_debug_pair(
        manifest=trusted_manifest, policy=trusted_policy, ledger=ledger,
        owner=guest.owner, revision=exact_head, state=readiness_state,
        debug_home=existing_debug_home, installed_apk=None, output=new_output,
        guest=guest,
    )
    guest.start(ledger, emulator=installed_emulator, adb=installed_adb,
                image=installed_api31_or_37_image)
    guest.adb(ledger, ["get-state"])
    # WP-503 supplies its separately admitted real runner and acceptance evidence.
finally:
    guest.dispose(ledger)
```

This is an interface example, NOT permission to invoke it. `trusted_guest`
requires the existing WP-503 session/attempt and admitted receipt revision,
an exact HEAD, a distinct execution
owner, the canonical shared Ledger/repository and a canonical private parent.
It verifies the loaded source checkout and runtime helper bytes, packaging
adapter and Windows launcher against ONE immutable default-base tree, and binds
policy/manifest to that same revision before allocation. Candidate-only code
fails closed. It verifies the retained installed runtime's integrity/namespace
without executing or replacing it; no canonical runtime installation is needed.
Missing/malformed/unadmitted authority is not a disposable availability flag.
Low-level `PrivateGuest.allocate/acquire` are implementation/fixture primitives,
not a second live authorization entrance.

One immediate SQLite transaction inserts BOTH the private guest row and the
real global `gradle-execution` host guard. The legacy native row is not rewritten
or borrowed. The new owner cannot equal its retained owner. The real global
guard is visible even to the original main Ledger implementation: a different
endpoint or newly named resource alone cannot bypass its existing Gradle check.
The guard lasts THROUGH packaging, guest startup, testing and observed disposal,
not merely the build. Current native/Gradle/runtime-install acquisitions conflict;
packaging borrows the exact outer receipt without changing its payload/time.
Generic release cannot remove that guard while the private guest exists.

Packaging keeps the existing tasks, strict dependencies, build/cache reuse,
source/package/App-Test-certificate/default-debug-key checks and no-install/no-
acceptance result. The guest path additionally uses in-process Kotlin compilation
with `--no-daemon`, following the existing owned manual/CI adaptation; persistent
or unobserved children fail rather than manufacturing quiescence. Historical
installed APK/snapshot reads are absent ONLY for this genuinely fresh guest.
The original non-guest packaging contract is unchanged.

Native Windows uses an unnamed non-breakaway Job: suspended process creation,
assignment BEFORE resume, an explicit inherited-handle allowlist, current-user/
SYSTEM protected fresh data and Job/PID TCP-listener ownership. Linux uses a
single-threaded pidfd/subreaper worker, pinned descendant identities and owned
socket inodes; WSL cannot emulate the canonical Windows Ledger. Only installed
tools/images are used. A fresh exclusive data directory, image-default userdata,
AVD home, console/adb ports and private adb server/environment never select an
existing AVD/server/device. Selected commands require the owned live transport.

`dispose` signals only pinned owned descendants or the exact owned Windows Job,
has bounded cleanup, and requires observed zero/exited processes and closed
owned handles before atomically removing BOTH exact receipts. Unknown cleanup
retains both rows/data and returns failure; there is no force-release, guessed
idle acknowledgment, foreign-daemon kill, wipe or recursive data deletion.
Even success intentionally retains terminal private data for inspection.

The existing candidate controller job retains ONE strict `test_runner.py` test
invocation alongside controller/local tests. Its candidate-event arguments bind
native-family selection to actual HEAD and immutable GitHub base/head trees.
`android/accessibility-tests` is explicitly **NOT SELECTED** only when absent
from BOTH trees and untouched by relevant family changes. The filesystem's
absence alone never decides selection, and an uncommitted family cannot be
reported absent. This lets the never-present WP-003 producer obtain its own
source CI before WP-503 consumes the contract, without fabricating producer tests.

Presence in either immutable endpoint, or changes under that suite,
`docs/android/accessibility/`, `docs/android/evidence/WP-503/` or the WP-503
deviation file, makes the real committed suite mandatory. The scope job performs
selection ONLY (no discovery/tests) and forces the controller job when selected;
the test invocation independently rechecks the same endpoints. Missing/deleted
suite, malformed authority, stale HEAD, checkout/file-byte drift, zero discovery,
failures/errors/skips all fail closed. Selected files must match the head tree;
ignored Python bytecode is not producer source. Unknown/missing immutable
endpoints block selection rather than inventing an absence baseline.

The one strict suite prints actual method names and requires nonzero
discovered=run with zero failures/errors/skips. Read-only tokens, ephemeral
runners and default-base trusted controller boundaries are unchanged; no new
workflow, dispatch, result bundle or fabricated runner is added. The genuine
producer suite is absent at both endpoints of this local proposal and its
execution is NOT SELECTED, not passed. WP-503 runner code is not modified here.
Python fixtures do not prove actual Win32/emulator, Linux process branches,
cold Gradle, native acceptance or deployment.

### Local-only active Translate removal proposal

The successor to published `aa4b0b425b3ee58485191c7ede39e69ca8a1acf5`
proposes ONE independent `message-actions-translation-removal` capability,
WP-502 only, operation `remove-active-message-translation-affordance` only,
exactly `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt`.
This is not present trusted-main admission or a product edit. App/State/core/
build/resources receive no new path or operation. Inert Translate enum/handler/
helpers may remain; no provider/model/backend/billing/service is introduced.

The actual public MessageActionsRoute currently reaches unconditional Translate
in ActionButtons, and AppMessageActions reports "Message translation is not
registered". Removing this ACTIVE entry implements the already-approved
PORTING_PLAN section 2.2 scope removal; the frozen Swift button remains read-only,
WP-309 primary provenance unchanged. All other real action availability, theme,
localized labels, 48dp, dismissal, state/side effects and original text must remain.

Future product evidence must mount the REAL public MessageActionsRoute in
English AND Chinese, prove no reachable Translate dispatch (not just absent,
hidden or renamed label text), and independently render/dispatch retained actions
according to their original availability. A fake renderer or deleting the action
section cannot satisfy this. No mounted/native proof is claimed by Python tests.

Exact reverse removes only the new rule and recovers the ENTIRE reviewed aa4
policy/Git blob; Room7/picker3/avatar rules and manifest stay unchanged. Current
binding includes the new rule; old historical projections retain their identities.
Workers must obtain actual trusted-main admission and evolve the SAME WP-502
reservation only after separately reviewed deployment/verified native cleanup;
candidate policy cannot supply that authority. Semantic overlap serializes or
transfers to the current producer. This proposal remains LOCAL/UNPUBLISHED:
no publication, main/policy/runtime landing or product consumption is authorized.

### Source-only local/native execution interlock correction

The supported invocation-local feature publisher is
`python -I tools/android-port/controller/source_push.py`. Use only its reviewed
trusted-default-base version after landing; it refuses a candidate-modified
entrypoint. After refreshing the trusted origin default ref, its explicit inputs
are `--repo <worktree> --trusted-base <full-main-SHA> --head <full-HEAD-SHA>
--remote <configured-name> --remote-url <exact-single-push-URL>`.
It is source-only until reviewed and merged; do not run an unpublished successor
as trusted controller code.

The publisher stages the complete guard from immutable Git blobs into a private
temporary directory and runs it as the actual nonempty pre-push hook for one
normal nonforce feature update. It binds the source/candidate/tree/branch/remote
and canonical Git-common location again at hook time, checks staged hashes and
actual hook input, and refuses alternate-ledger or unrecognized Git configuration/location
overrides. It does not install a runtime, change configured hooks, use no-verify
or run the candidate's controller code. When current execution admission refuses
an old/mismatched installed runtime, the full trusted guard records exact
UNVERIFIED before transport or Gradle; publication is not merge acceptance.
The inherited invocation-only hooksPath is accepted only if exactly equal to
the generated nonempty guarding-hook directory. No empty-hook path is accepted.
Linked-worktree hooks may inherit Git's canonical `GIT_DIR`, `GIT_COMMON_DIR`,
`GIT_WORK_TREE` and empty `GIT_PREFIX`. Independent local probes with caller Git
overrides excluded discover the actual requested worktree root, private git-dir,
common-dir and Git installation exec-path; inherited locations must match those
probes, not mutable request values. These probes do not run transport or the guard.
The original and hook-time bindings must match, including the exact repository,
locations and nonsecret configuration/context digest. Git's automatic exec-path
export is normalized against its independently discovered installation. Caller
context is preserved for the actual guard, never silently stripped.

The only admitted counted overrides are exact `safe.bareRepository=explicit`,
`credential.interactive=never` and `core.fsmonitor=false`, without duplicates or
orphan indexed entries. Scoped credential helper injection is still refused:
the observed first-party CLI profile resets the GitHub helper then names
`copilot` on PATH. A valid GitHub Authenticode signature was observed on this
host's resolved helper, but neither a signed publisher nor a PATH name binds
the exact intended executable to trusted repository policy. No helper contents,
arguments or secrets were exposed. Admission needs a supported trusted executable
identity anchor; this change does not invent one or permit arbitrary helper
executables/candidate paths. An already-authenticated host without injected
helper configuration avoids that blocker; do not strip a caller's auth context
or retry a publication without separate exact-candidate authorization.
Actual execution/cleanup remains governed by the existing complete pre-push
guard when admission is available.
The hook independently reconstructs the complete normalized file set and hashes
from immutable base Git blobs; mutable request.json is not a hash authority.
Omitted/extra/traversal entries, forged recomputed manifests, extra staged files
and symlinks fail before the checker runs. Both outer and hook-time bindings also
read the actual configured origin's symbolic HEAD/main with `ls-remote`; a fresh
remote main with a stale local origin/main cache is rejected, not silently used.

The source successor published as `aa4b0b425b3ee58485191c7ede39e69ca8a1acf5`
closes the advisory/manual launcher gap; it does not deploy either candidate
capability or alter the held native473 operation/runtime. Both independent
Room7/picker3 rules and historical projections remain unchanged.

`Ledger.acquire_hard_lock` arbitrates native-execution and gradle-execution
inside the same immediate SQLite transaction in both directions. Only EXACT
owner equality permits distinct native+Gradle nesting (the existing debug
packaging contract), not a session/prefix exemption. Reacquiring an execution
resource cannot replace its incumbent payload/timestamp or accidentally release
an outer context. Explicit retained-operation recovery passes the entire known
`expected_existing` receipt (repository/resource/owner/payload/acquired/
record_state) plus exact owner/payload to `hard_lock` or `acquire_hard_lock`.
The transaction rejects stale/foreign/missing receipts; a match returns
`acquired_new:false` without updating ANY stored byte/time. A borrowing context
never auto-releases, including on failure or nested use. After actual cleanup,
the original owner still explicitly releases via the existing gated consumer
path; recovery is not cleanup proof or permission to release live work.
Runtime installation conflicts with both execution resources,
even under the same owner; unrelated resource behavior is unchanged.

The actual advisory and full/manual entrance binds the requested commit/tree,
uses a fresh invocation owner and acquires the canonical `shared_ledger` Gradle
resource BEFORE transport/WSL probes, path conversion, bundle or adapter launch.
An atomic admission collision runs nothing: advisory records exact pending and
remains nonblocking; manual returns an explicit failure for retry after the
incumbent releases. The existing Linux flock queue remains 10s for advisory,
unbounded for manual, and the advisory work deadline remains 180s.

Missing/old/self-consistent-but-mismatched installed code fails closed. The
schema-v2 runtime manifest binds the repository selected by the trusted protected
installer, not the candidate policy. Candidate repository drift is rejected
BEFORE opening a lock namespace. The
manifest, executing launcher/helpers and loaded Ledger version/source must match
the canonical runtime; revalidation under actual execution ownership prevents
installation racing launch. The supported atomic installer already delivers
helpers before check.py; hook installation now uses the same runtime-install
interlock and no unnecessary WSL probe. Installation still needs separately
authorized, quiescent deployment; no install occurs in this source assignment.

The actual `reserve.py install-runtime` CLI resolves the host-controlled
`refs/remotes/origin/HEAD` default-base commit and reads THAT committed policy's
repository before creating a Ledger or writing anything. It rejects candidate
repository drift and disagreement with an existing schema-v2 runtime identity.
Initial installation and integrity-verified schema-v1 upgrades use this same
default-base authority; schema-v1 never authorizes a candidate-selected namespace.
Missing/malformed default-base authority or non-object/invalid candidate/runtime
metadata fails explicitly, with no guessed namespace or candidate fallback.
This does not fetch, set a default ref, install or authorize deployment by itself.

Windows retains its native SQLite ownership while WSL receives an exact private
invocation handoff. Linux does not open the Windows database or substitute its
flock for the shared Ledger. Internal shell modes require the actual inherited
handoff descriptor; advisory recursion also checks the inherited flock.
The pidfd/live-ancestry subreaper emits an invocation-bound completion ONLY
after observed child termination/reaping. These transient lifecycle records are
not CI results, acceptance or a second verification bundle.

Missing/malformed/stale completion, abrupt transport/supervisor failure or
unreapable children retains the actual owner AND its control/bundle/recovery
inputs. There is no TTL, forced foreign release or exit-code-shaped cleanup
success. A proven PRELAUNCH refusal (absent wsl.exe/bash, unsupported adapter or
worktree overlay) releases only its fresh lease. State is marked started
immediately BEFORE an actual transport/bundle/adapter subprocess attempt, never
merely because a helper was called. Attempted-but-unacknowledged execution retains
ownership regardless of exception type or exit status. Advisory TERM/KILL cleanup
is bounded and signals only pinned owned descendants.

Unsupported worktree overlays and malformed structural requests are rejected
before even binding/admission/transport, not inside a runner after WSL discovery.
Malformed namespace inputs become explicit blockers/pending candidates rather
than uncaught TypeError or an alternate partition.

Full/manual has an explicit OWNED NON-DAEMON adaptation: local Gradle tasks use
`--no-daemon` and Kotlin compilation in-process, including cold first use.
Strict tasks/results, normal caches and task reuse remain unchanged; shared/
foreign daemons are never stopped. This does not claim warm daemon reuse.
Manual supervision waits briefly for children to self-reap: normal success AND
nonzero verification failure with fully observed quiescence release ownership
with the original status. Persistent descendants/unknown cleanup retain ownership
for authoritative reconciliation, without daemon kill or automatic adoption/
release. Native contributor adapters without
the supported Linux pidfd supervisor fail before launch, not fake a result.

Controller regressions use real TEMPORARY Ledgers, concurrent two-party races,
exact payload preservation, owner/stale/runtime boundaries, actual matching-runtime
namespace drift, actual unavailable-tool/prelaunch refusal, retained-receipt
borrowing, command construction and pending commit/tree assertions. Seven discovered process-fixture
cases execute real isolated Linux launchers/children only on Linux. The
`advisory_test.load_tests` host partition selects all seven on Linux (including
WSL's Linux-only temporary process fixtures), or one actual native Windows
unsupported-platform rejection case without any WSL/native launch. Discovery
prints the selected class and explicitly marks Linux process proof NOT SELECTED
on Windows; that Windows result is not seven Linux passes. Unknown hosts fail
discovery rather than silently selecting an empty suite. Pure regressions verify
the complete Linux selection, Windows selection and unsupported-host rejection.
The declared `fast.local_test_command` remains candidate-local discovery and
uses this same partition; no separate hidden test command or host override exists.
The strict hosted controller runner also uses `*test*.py` discovery, so the
suffix-named `advisory_test.py` and `hook_test.py` are mandatory there rather than
silently omitted by unittest's default `test*.py` pattern. Its existing zero-test,
failure/error and skipped-case rejection remains unchanged.

The disposable authority integration fixture has a separate WSL boundary: its
real subprocess must reject canonical Windows-ledger emulation before reaching
trusted-source checks, with its temporary historical lease, hard lock and legacy
runtime bytes unchanged. It explicitly reports canonical lifecycle NOT SELECTED
there. Native Windows and non-WSL Linux retain the complete trusted-code,
scope-evolution and prepared/disposed fixture sequence. Pure negative cases also
prove unsupported/WSL rejection precedes authority loading and process launch.
No platform is mocked into executing another platform's lifecycle.
Linux fixtures include first/next manual invocation, observed nonzero quiescence
and persistent owned-child retention; they are not real Gradle cold builds.
Source-local Windows results are not Linux/WSL/native proof. Original full/manual
result validation and strict verification remain mandatory; fast-check failure
never becomes a merge pass.

### Reservation mutation and merge-intent fences

The actual reservation CLI now selects capability policy, manifest write paths
and exclusions from ONE immutable host-controlled origin default-base commit.
Manifest/exclusion/schema inputs use bounded Git object reads. It validates the
source pin/catalog/DAG/paths and any installed namespace before opening Ledger.
`admit`, `claim`, `evolve`/`reconcile` and `transfer` never authorize from checkout
policy or manifest; status and other Ledger consumers use the same namespace.
Missing/malformed trusted metadata fails explicitly with no candidate fallback.
The aa4 installer retains its distinct candidate-identity rejection and atomic
installation/execution exclusion.

This fixes a real gap in the first Translate proposal: manually feeding an old
policy to CapabilityEngine did NOT prove the CLI chose that policy. Real
temporary-Git CLI tests now use a complete valid trusted checkout/default ref,
an actual same-owner claim, a same-repository candidate-only rule and a separate
candidate manifest widening. Hypothetical engine calls admit each candidate
change, while actual CLI admit/claim/evolve/reconcile/transfer reject it and
preserve the entire raw receipt/revision/binding/capabilities/authorization and
product bytes, with no foreign partition.

Existing trusted WP-owned/support development remains usable even with malformed
candidate policy/manifest drafts: actual WP-003 policy admission and typed support
evolution succeed under trusted authority, preserving historical receipt fields.
This is not blanket refusal of policy-development edits or candidate simulation.
These tests use private Git/SQLite/runtime fixtures, not host runtime/lease actions.

Local `reserve.py evolve` (also `reconcile`) now requires the caller's stored
`--attempt` and `--session-id` as well as `--expected-revision`. It no longer
reads the current attempt from the ledger and silently adopts it. For example,
in the receipt's original worktree/branch:

```powershell
python tools\android-port\local\reserve.py evolve --wp WP-xxx --attempt <stored-attempt> --session-id <stored-session> --expected-revision <actual-revision> --path <admitted-support-path> --operation <trusted-operation>
```

Session, current Git branch and resolved worktree must match the existing receipt
inside the same ledger transaction as revision checking and evolution. Use the
stored identity, not a runtime alias; this operation neither resolves aliases nor
transfers a claim. A stale or foreign checkout must not copy the current owner's
identity to proceed. Historical claims lacking branch/worktree attribution fail
closed here; their identities/history must be reconciled through the existing
trusted lifecycle path, not release/recreate or hand-edited ledger rows.
The trusted controller's `evolve_reservation` similarly requires an explicit
`attempt` keyword; missing/stale attempt never adopts the current owner.

### LOCAL receive-prefetch admission proposal after b677 (not live authority)

The separate candidate-only `receive-prefetch-admission` capability admits only
assigned WP-501 / `repair-receive-prefetch-admission`, for exactly:

- `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/TimelineContracts.kt`
- `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatContent.kt`

It does NOT extend channel-open, fallback, Rooms or generic App authority. It
does not grant primer, core/service, primary-test, build or unrelated App edits.
The current incoming event immediately admits/builds the row, while the mounted
App caches never construct the existing prefetcher; those source facts remain
unfixed by this proposal. Future repair requires the real App/feature/cache
consumer, not a cloned evaluator or fake renderer. Tests/evidence remain in
already-owned parity paths; WP-307/WP-407/WP-218 provenance/accounting is unchanged.

The rule carries all nine frozen admission cases: immediate plain/master-off
with zero collaborator calls; URL prefetch before incoming admission; optional
nil fast path without permission to omit production wiring; positive 200x100
dimensions yielding aspect2.0 in the matching actual row; the bounded production
3s race with injected200ms/<1s against a cancellable5s probe; `g:abc123` expanding
to `https://media.giphy.com/media/abc123/giphy.gif`; HTML-image/card re-entry
without stranded loading; outgoing immediate visibility plus async prefetch.
Master/DM/channel privacy, independent process-store/cache/worker lifetime,
actual late-content refresh or reviewed native equivalent, cancellation without
stale conversation/radio admission, original text/identity/order/unread/send,
explicit errors and ZERO radio commands remain required.

The exact single-rule inverse restores the ENTIRE b677 object and raw Git blob
before the retained6b/ad40165 inverses. Real private Git/SQLite CLI tests keep
immutable default-main authority while candidate evaluation accepts each tuple:
actual admit/evolve MUST reject it without altering receipts, products or runtime.
No candidate policy can self-admit a product repair.

Protected trusted-main admission and same-owner scope evolution precede product
edits; real semantic overlap serializes/transfers to the current producer.
Active conflicting native/Gradle operations remain excluded. Old473/66bd guest/
receipt/history are untouched and need not be restored, released or cleaned for
these disjoint source/repair paths; no inactivity/disposition is inferred.
The disposable consumer loads the trusted-main checkout: this proposal invents
no global-runtime-install prerequisite and changes none of its pinned APIs or
actual execution/install interlocks. Source review, protected policy admission
and future product/native acceptance remain distinct. Publication is HELD.

### LOCAL parity-support proposal after 6b (not live admission)

Two candidate-only rules propose bounded support for existing parity owners:
WP-502 / `repeat-row-fallback-affordance` / operation
`repair-repeat-row-fallback-affordance` admits only
`android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt`;
WP-501 / `channel-open-flood-scope` / operation
`repair-channel-open-flood-scope` admits only the chats timeline
`TimelineContracts.kt`, `ChatViewModel.kt` and App container `AppChatsFeature.kt`
at their full canonical `src/main/kotlin/com/meshcoreone/android/...` paths in
the rule. The generic App rule does not admit that named operation and is not
broadened. No Room rule, whole directory, generic modify, resolver or primary
test authority is reused to smuggle either repair.

These proposals require trusted-main admission BEFORE product edits, existing
producer identity/reservation, preserved WP-309/WP-307/WP-407 provenance,
semantic-overlap serialization/current-producer transfer, real mounted consumer
evidence and protected review excluding active conflicting native/Gradle
operations. The old retained guest/receipt/history are untouched: no restoration,
release or cleanup of that guest is a prerequisite for these disjoint repairs
or their trusted policy proposal landing. This does not assert that the old
guest is idle or dispose of any receipt. Product
tests/evidence stay in existing parity-owned paths. The two-rule inverse
restores the entire reviewed6b policy, not an approximate digest or rewritten
historical receipt. The actual reserve CLI continues reading trusted default-
base policy/manifest; candidate rules cannot admit or evolve themselves.

RepeatRow evidence must demonstrate fallback-only tappable/localized explanation
and an independent accessibility action through the real public route, retaining
exact/unresolved behavior and all other actions. Channel-open evidence must
exercise the actual timeline-to-App-to-same-radio/session command, original
population/scope/unread ordering, firmware capability, success-only deduplication,
error/cancellation/retry behavior and zero prefetch radio effects, not merely
repeat the already-passing resolver cases. Details and exact input revisions:
`docs/android/deviations/WP-003.md`. Until protected admission, these are still
product prerequisites, not implemented features or live execution permission.

This clarification applies ONLY to the two new proposed rules; incumbent rules
and actual execution/runtime-install interlocks are unchanged. In particular,
`Ledger.acquire_hard_lock` still rejects an `installed-git-common-runtime`
acquisition while an execution receipt is held. This proposal does not install
that runtime or bypass its exclusion. The inspected controller merge/gate and
native-review paths do not impose an old-guest recovery prerequisite for this
source-policy proposal; protected/current-head/semantic gates still apply.

Merge-lane acquisition checks the current attempt, reservation revision,
implementation PR, complete historical binding, base/policy and review/merge
state transactionally **before** acquiring or replaying an intent. The controller
also rejects a reservation changed during gate evaluation. Stale merge payloads
cannot reuse a confirmed operation after successor admission. The existing
serialized uncertain-lane behavior and real-merge reconciliation remain intact.

These are local mutation/merge-intent fences, not global worker revocation.
The third retained diagnostic (the hosted publisher does not open a worktree
SQLite ledger) still holds by design: ephemeral default-branch publication uses
authenticated GitHub PR/base/head/review/workflow facts, not a worker-local
database. Regression tests check both valid hosted publication without local
ledger access and failure on changed server head/base/closed PR. There is no
shared authoritative attempt-revocation store consumed by that publisher;
therefore local retirement alone must never be represented as globally revoking
publication authority. This repair adds no retirement, terminal observation,
publisher revocation protocol, activation or native action.

### WP-502/504 software-command amendment

`docs/android/evidence/WP-003/software-verification-manifest-amendment.json`
replaces the two bootstrap-era "Gradle/Android CI do not exist" blockers with
real software commands and specific remaining acceptance gaps. Its pinned,
complete-catalog preimage/postimage and exact inverse preserve every historical
manifest/receipt and all other WPs. The runtime delivery guard and WP-201
provenance reader include the new projection and amendment inputs.

WP-502 uses the existing preflight/scaffold/inspect lane: its tests are registered
in App `testDebugUnitTest`/`androidTest`, not standalone UI/screenshot modules.
The [owner mapping at dcd13384](https://github.com/cbattlegear/MeshCoreOne-Android/blob/dcd133840897a8aee19941633b612ed9cc40bc1b/docs/android/evidence/WP-502/software-verification.md)
documents five App selectors (ConversationComposeTest9,
ConnectedConversationComposeTest4, QueuedComposerIntegrationTest6,
ShortcutQueueIntegrationTest16, DurableRetryIntegrationTest6), totaling41.
The scaffold lane executes all registered App tests, not only those selectors.
The two adaptive Connected cases belong to that separate producer candidate;
listing its inventory does not merge it or credit41 executions on this base.
API31/37's12 methods and targeted33/34/36's2 methods still require real
candidate-bound instrumentation; assembly and API31 Robolectric are not proxies.

WP-504 uses preflight and `ci.py run --stage benchmark`, which executes the
existing `:benchmark:verifyBenchmarkHarness` and `validateModuleGraph`.
The harness includes eight dataset JVM cases, dependency inventory, test APK,
App benchmark/profile/release assembly and static R8/ELF/ZIP checks.
The [owner mapping](https://github.com/cbattlegear/MeshCoreOne-Android/issues/137#issuecomment-6293784662)
separately identifies physical `:benchmark:connectedDebugAndroidTest` classes:
BaselineProfileGenerator2, PerformanceBenchmark9 and NativePageRuntimeTest1.
No new device endpoint, measurement, numeric budget or runtime16KB success
is manufactured by declaring software commands.

Both entries deliberately retain `configured:false`: the existing field gates
**full WP acceptance**, not whether a worker may run a declared software command.
Complete original-family/feature accounting, current device captures, approved
goldens, folding/TalkBack/radio evidence and measured performance/runtime review
remain explicit gaps. Neither acceptance gate nor dependency completion is
weakened. The amendment is a reviewable content specification, not a terminal
worker receipt or a second CI-result bundle. WP407 host-lifecycle admission
remains a separate blocker.

`android-ci.yml` runs for every pull request, including dependent drafts, plus
merge groups, `main` pushes and manual validation. A small scope job classifies
whether Android build inputs changed; metadata-only changes validate the
controller inputs without running the scaffold build. `ubuntu-24.04` x64 is the sole mandatory host; the
prior `windows-2025` leg was removed (no shipped Windows build, local Windows
development is covered by WSL instead).

The exact-commit GitHub job result and log are the authoritative reproducible
CI record. The build job validates complete JUnit and lint XML in place,
compares actual cases/outcomes with discovery and graph/runtime outputs, and
inspects the built APK before it can succeed. Zero discovery, failures, errors,
skips, malformed output or a failed command fail the job. The always-running
`android-ci` job directly requires success when the scope selected the build
and requires a skipped build otherwise. CI does not upload, download, hash or
revalidate a second result bundle.

| Stage | Actual executable tasks / mandatory evidence |
| --- | --- |
| Python | Frozen overlay/manifest/traceability/notices, controller regressions and all scaffold Python cases; nonzero discovery and no failed/error/skipped case |
| Preflight | Exact Python/JDK, publisher-pinned wrapper, installed SDK37.2/rev1 and build-tools37.0.0, explicit private caches, unchanged credential allowlist |
| `scaffold` | One Gradle invocation runs `verifyScaffoldTests verifyRoomSchema validateModuleGraph runtimeDependencyInventory resolveScaffoldDependencies :app:assembleDebug lintScaffold`; strict dependency verification remains mandatory, build-logic tests are collected once through the composite, and all 24 lint targets must produce readable XML with no errors |
| Inspect | Actual APK min31/target37/debug package, launcher, notices, permissions and test-fixture absence; `zipalign -P 16` and ELF PT_LOAD alignment |

Four Kotlin suites retain at least conventions31, contracts4, app10 and Room2
actual cases. The executor independently compares XML testcase/outcome nodes
with the scaffold TSV. These are scaffold assertions, **not original feature
test parity**. The 30-module boundary graph, Room/KSP schema check, runtime
POM/license inputs and exact debug APK are validated in the executing job.
Each host's debug key and APK are ephemeral. Static 16KB alignment is not
real-device/MapLibre/HIL evidence.

The live report validation additionally requires every committed active unit-test source
set under `core`, `feature` and `platform` to have complete nonzero raw JUnit
evidence. Original scaffold suites and the separately required protocol/testing
workflows are not double-counted. Expected modules and all their main/unit/build
input blob identities come from the exact candidate Git tree. An active module
missing a runner/report, having ambiguous JVM/Android report directories, or
containing failed/skipped/malformed cases fails.

Module owners must connect their actual `test` or `testDebugUnitTest` task to
`verifyScaffoldTests` in their module build script, following the existing
localization hook. Missing wiring is an explicit CI failure, not an empty-success
fallback. The localization-specific source/resource/family verifier still runs
against its live report. This additive unit-run proof is not original-case
parity, instrumentation, radio behavior or a protected WP acceptance receipt.

The WP-201 `verifyDomainRoomTests` hook validates current immutable model,
contracts and Room inputs, the frozen original 114 families/215 native identity
minimum, and the initial v1 schema. It allows additional executed cases and
unrelated committed native-module successors. Its historical writer-scope
audit runs only when explicitly requested with an exact base, never implicitly
in the evergreen root task. Historical `local-evidence.json` is unchanged;
current execution/run metadata and additive cases are separate. The collector
and its temporary-Git/raw-XML regressions are mandatory committed runtime
inputs. This is not a new source-parity or WP-201 acceptance receipt.

No detekt, ktlint, Kover, localization synchronization, full source-case catalog,
instrumentation, Swift/macOS oracle or hardware task is invented here. Their
actual tooling, output contracts and acceptance remain WP-004/005 and the owning
feature/quality WPs. Passing this workflow never configures those future WPs.

## Provisioning and credential boundary

`controller/toolchain-pins.json` pins Temurin21.0.12.1+1 archives separately for
Windows/Linux, exact Python3.12.4, Gradle9.8.0 distribution/JAR, and Google's actual
`platforms;android-37.2`, `build-tools;37.0.0`, `cmdline-tools;23.0` archives.
SDK SHA-256 values were independently computed from exact first-party downloads
and checked against Google's size/SHA-1 metadata. JDK SHA-256 values come from the
publisher release asset digests. Metadata comes from `repository2-3.xml`; the
older repository index and assumed `platforms;android-37` are not used.

Provisioning requires a new absolute ephemeral directory, verifies bytes before
extracting, rejects archive traversal and validates exact package revisions,
hosts and license text. The explicit `--accept-sdk-license` option records only
the pinned SDK terms for this isolated installation; it is not app/dependency
legal approval. Changed SDK terms require human provisioning review.
The command-line-tools archive is installed but its CLI bootstrap is not executed:
an actual Windows probe showed it automatically fetching a separate helper.
Preflight instead reads real installed package metadata and Gradle exercises
the exact platform/build tools. Any separately approved future CLI invocation
must use `--no-metrics`; no CLI login, updater or device operation is a CI step.
Both-host installer regressions forbid every process invocation and every
download beyond the exact four locked archives plus first-party SDK metadata.
Provisioning provenance explicitly records archive-only installation and no
separate CLI-helper download/execution.
No SDK/JDK/cache/debug key or private installation path is committed.
`runtime_inputs.py` checks the immutable committed Git tree and exact checkout
bytes (with Git text CRLF/LF normalization, never binary normalization) for every
required pin/requirement/reader/workflow/build input before
provisioning or verification. A developer-only ignored file cannot satisfy it.

All Actions are pinned to full reviewed commit SHAs. Checkout has
`persist-credentials: false`; workflow/job tokens have only `contents: read`.
No signing, dispatch, merge, Copilot credential, shared runner, service/container
or cross-PR cache is provided. Candidate Gradle receives only the **unchanged**
`android/scaffold/environment-allowlist.json` names, with explicit `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `ANDROID_USER_HOME`.
The Windows PowerShell child strips startup-added variables and repeats the
independent parent preflight before invoking the batch wrapper.

Hosted builds use one worker, in-process Kotlin, a 2GiB build heap and 512MiB
test heap; these are build-process budgets, not app runtime flags or AI budgets.
The separate local `--local` mode retains measured shared-Windows constraints.
PyYAML6.0.3 is hash-pinned MIT-licensed CI parsing tooling, not an Android-linked
dependency. Tool/SDK terms and existing GPLv3/MIT/Apache notices remain distinct
from pending human dependency/license admission.

## Cloud setup and worker readiness

`copilot-setup-steps.yml` has exactly one supported Ubuntu x64
`copilot-setup-steps` job, only supported job properties, and a 59-minute limit.
It provisions the same exact archives and resolves the real scaffold
dependencies strictly. PR/manual execution validates setup code, **not cloud
account assignability**. The file takes effect for Copilot only after a real
default-branch merge; no cloud agent is invoked by this draft.

Setup failure may still allow Copilot to start. Before writing, a worker must
run the explicit preflight and actual strict dependency preparation, not infer
readiness from the setup workflow name or an existing directory.

At the repository root, with `ANDROID_CI_STATE` pointing to the private
provisioner's `environment.json` and `ANDROID_CI_OUTPUT` to a private absolute
evidence directory:

```powershell
python .\tools\android-port\controller\ci.py preflight
python .\tools\android-port\controller\ci.py run --stage prepare
python .\tools\android-port\controller\ci.py python
python .\tools\android-port\controller\ci.py run --stage scaffold
python .\tools\android-port\controller\ci.py inspect
```

Use `--local` for the documented constrained shared host. Local records explicitly
lack hosted run authority. Missing state, SDK, checksum, command, test or output
fails; no fallback installation/cache/pin rewrite occurs.

## Protected verification amendment

The bootstrap overlay changes only WP-002/003 executable verification configurations. Every WP ID,
owner, dependency, human/supervised flag, acceptance, source ownership and pending
feature state remains unchanged. WP-001 stays unconfigured; architecture/license
approval cannot be replaced by a static build command.

`verification_config.py --check` reconstructs the frozen WP-000 generator output,
requires its original semantic digest, applies the two exact controller-owned
overlays, and compares the complete canonical manifest/exclusions. The original
`bootstrap.py`, `inventory_rules.py`, `wp_metadata.py`, plan and schemas are not
edited. **Bare `bootstrap.py` check mode now deliberately rejects the amended
manifest**; the protected overlay command is the coherent new check, not a claim
that the old generator remained unchanged and magically accepted new fields.

```powershell
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\workflows.py
python .\tools\android-port\controller\test_runner.py --quiet
```

## Trusted gates and remaining external proof

The read/search-only reviewer profile and all skills/policy/manifest inputs are
read from immutable **trusted-base Git objects**. Candidate files/instructions
are separately labeled untrusted data; no candidate checkout/script is executed
by trusted-duty workflows. Staging is complete, digest-bound and bounded; excess
files/bytes fail rather than dropping cases. A staged JSON object is not a verdict.

The authority reconciles real PR/check/run/review facts, exact Android Actions
check-suite membership, isolated publisher binding/run/attempt external IDs,
current/historical base, source/manifest/policy and any actual bound human
acceptance records. A rebased head/base, wrong publisher/workflow/repository/attempt,
skipped check, malformed verdict, absent original-case family or model-written
approval cannot complete a WP. Repairs retain the existing worker/open PR and
bounded attempt counter; a cloud comment receipt must match that PR and feedback.

Publisher encoding retains the **complete** bundle/catalog and rejects summary
or text above 65,535 characters **or UTF-8 bytes before publication**. No unchecked
`details_url`, partial catalog or truncation escape exists. Large future catalogs
require a protected immutable-artifact reader/publisher amendment.
[Historical revalidation](historical-revalidation.md) is a versioned **blocked
migration interface**, not a fabricated bootstrap Android check or import.

Still external: authenticated isolated publisher/reviewer/native host,
complete live usage/shared ledger, genuine branch-rule/check identities,
protected historical receipt approval, user-selected limits and separate
activation. Ordinary dependent drafts are not a registered native stack;
later native-stack landing requires its asynchronous native API, not
`MergeBackend`. No settings, auto-merge, stack registration, fleet, signing,
release or downstream WP is authorized by this implementation.

### User-directed WP-003 review-authority amendment

This amendment supersedes the repository-created separate-person/formal GitHub
`APPROVED` requirement for code review, including the code-review portions of the
supervised foundation gates and ADR-006's protected-path approval wording. It does
not rewrite original plan/ADR acceptance history or activate automation.

The authenticated trusted publisher's exact-bound read-only review is the code
review authority for ordinary and protected code changes. An authenticated
authorized coordinator may arrange the same isolated review even when the
coordinator is the PR author. A name alone, candidate-provided PASS, staged input
file, or locally constructed bundle is not authority. Findings, complete
acceptance/case validation, actual required checks, publisher provenance and
exact repository/base/head/source/manifest/policy bindings remain mandatory.
Current-head changes requested by an authorized reviewer still block.

`review_policy` separates this code review from retained human acceptance.
WP-001's architecture/license decisions, WP-505's physical-device outcomes,
WP-506's signing/release acceptance, protected release/signing/legal paths and
explicit `needs-human` labels still require actual human acceptance. Generic
protected code paths do not require an additional person's GitHub review.
Ordinary unprotected WPs acquire no new approval requirement. Activation,
budgets, credentials, branch rules, hardware/legal/signing evidence and historical
receipt import keep their existing separate controls.

A new acceptance record uses `kind: human-acceptance` with the existing immutable
binding fields and a real maintainer review in `COMMENTED` or `APPROVED` state.
Its actual GitHub body must contain
`<!-- android-port-acceptance:<binding.key> -->`; reviewer, commit, state and
latest decisive review must match server facts. The sole maintainer may record
a truthful acceptance comment on their own PR; this is not a GitHub self-approval.
It cannot manufacture hardware/legal/signing outcomes or waive acceptance
evidence. Historical untyped `APPROVED` records and their original stamps remain
readable, unchanged. Neither format can substitute for authenticated code review.

### Native authenticated review publication (bounded WP-003 repair)

The manual path no longer requires a custom GitHub App, injected Copilot app-host
APIs, a second human, or a formal GitHub `APPROVED` review. An authorized coordinator
performs/arranges the trusted-profile read/search-only review and explicitly records
its complete acceptance decision as an authenticated `COMMENTED` PR review. This
is an accountable coordinator attestation, **not a claim that GitHub independently
measured the reviewer's tool isolation**. The producer must not mint their own
trusted verdict. Plain source feedback, a green build, PR prose and staged input
are not acceptance. Existing comments on feature PRs remain source feedback only.

`android-parity-review.yml` is manual, default-branch-only, ephemeral, serialized
with the controller lane, and uses GitHub's built-in ephemeral Actions token.
It has read-only contents/actions/PR access and **Checks write only**. No custom
credential, signing/dispatch/merge secret, privileged cache or new service is
installed. Candidate jobs remain entirely read-only. The publisher fetches candidate
Git objects as data, never checks out or executes candidate scripts/instructions.
All publishing code/profile/skills/manifest/policy come from the exact deployed
default-branch SHA. A feature-branch dispatch is not an installation or acceptance
test. Check output contains only receipt/binding/run references, not a second
CI-result bundle or copied original-case catalog.

Before the review, fetch the immutable candidate into the reviewer's own worktree,
then use the existing staging command with the actual WP and current base/head:

```powershell
python .\tools\android-port\controller\gate_runtime.py stage --wp WP-xxx --trusted-base <40-hex-base> --candidate <40-hex-head> --output <private-staged-input.json>
```

The review body must start exactly with
`<!-- android-port-native-review-v1 -->` followed by a newline and one JSON object:

```json
{
  "schema_version": 1,
  "binding": "<the complete staged binding object, not this placeholder>",
  "method": "authenticated-coordinator-read-only",
  "staging_sha256": "<controller.schema.digest of the complete staged JSON>",
  "review": "<the existing complete parity-review schema object>",
  "evidence": "<hosted acceptance schema v2 object described below>",
  "approvals": []
}
```

The placeholders above are explanatory, invalid inputs, not a ready-made PASS.
Record a real review using GitHub's supported REST API
`POST /repos/{owner}/{repo}/pulls/{number}/reviews` with `event: COMMENT`,
`commit_id` equal to the exact candidate head, and that body. Do not automate
fabrication of the verdict. The authenticated reviewer must be a human GitHub
`User` in the trusted policy's `authorized_coordinators`; the coordinator can also
be the PR author. The receipt must fit the complete 65,535-byte/character bound.
Oversized receipts block; there is no unverified attachment or truncation fallback.

Hosted acceptance schema v2 has `schema_version: 2`, all seven binding fields,
`changed_paths`, `acceptance` and `source_tests` with their existing complete
schemas, plus:

```json
{
  "runs": [{"check": "android-ci", "run_id": 123, "run_attempt": 1}],
  "retained_artifacts": []
}
```

Those numbers are shape examples, **not evidence**. Every acceptance/case evidence
reference must name the actual authoritative URL
`https://github.com/<repository>/actions/runs/<run_id>/attempts/<attempt>` or a
retained non-reproducible evidence file `{path, sha256}`. CI counts, results,
checksums and output copies are deliberately not accepted in this run-reference
schema. The actual CI implementation still fails on missing/malformed/zero/skipped/
failed tests. The publisher reconciles successful Actions run and latest Android
check, workflow path/ID, Actions application/check-suite, PR and exact base/head.
Original cases/families are validated against `docs/android/test-cases.json` read
from the trusted base, not a catalog supplied by the producer. Retained evidence
hashes are compared with actual candidate Git blobs; they do not manufacture
physical-device, legal, signing or human outcomes.

Conditional human acceptance remains separate: retain actual bound maintainer
reviews and the `android-port-acceptance:<binding.key>` stamp in `approvals`.
Architecture/license, hardware, signing/release and `needs-human` still block
without their genuine acceptance. Unresolved inline threads, current formal
changes-requested reviews, or later authorized source feedback invalidate the
source receipt. The publisher evaluates twice before success; subsequent edits,
new findings, reruns and base/head movement are rejected again by the authority
when acceptance/completion/merge is read. Renames retain both path identities.

A coordinator can inspect without any write operation using their existing
authenticated GitHub user credential in `ANDROID_PORT_GITHUB_TOKEN`:

```powershell
python .\tools\android-port\controller\native_review.py inspect --trusted-base <base> --wp WP-xxx --pr <number> --review-id <actual-review-id>
```

After **supervised review and merge of this repair**, dispatch the deployed workflow:

```powershell
gh workflow run android-parity-review.yml --ref main -f work_package=WP-xxx -f pull_request=<number> -f review_id=<actual-review-id>
```

Only this workflow may publish `parity-review` and `gate-integrity`; local
`publish` fails before mutation. Publication creates in-progress checks first and
records failures explicitly. Interrupted publication remains pending/failed, never
success. A successful check is authoritative only after its real default-branch
publisher run also completes successfully. App IDs/workflow IDs are obtained from
authenticated GitHub server facts, never invented or taken from candidate JSON.
The immutable Actions job identity includes the WP/PR/review ID and is reconciled
through the exact run-attempt jobs API; another PR's successful default-branch run
cannot be reused by changing a check's summary or `external_id`.
Legacy custom-publisher bundles remain readable when that publisher is configured.
`GitHubGateAuthority` and the existing completion/accounting path revalidate native
receipts rather than bypassing them with a new success flag.
Native completion validates the server PR's actual merged head/merge commit and
first-parent base for both a normal merge and the existing REST adapter's squash
strategy; it never invents a second parent for a squash commit.

**Project acceptance is not GitHub enforcement, producer completion or permission
to merge.** A source-reviewed open PR cannot complete a dependency. Current-base
serialization, paused/off activation, budgets, actual worker reconciliation,
protected historical foundation import and branch-rule/no-bypass proof still gate
the existing landing controller. This repair does not merge anything, change
repository settings, install credentials, clear failed feature CI, or enable a
schedule/fleet. GitHub supports an authenticated author's normal merge without
formal self-approval, but an admin merge is not evidence that project gates or
no-bypass protection were satisfied. At dispatch, main had no legacy branch
protection (HTTP 404); branch/ruleset proof remains explicitly unverified.

### Change-scoped acceptance for an already-landed WP repair

An open repair of an already-landed WP is not a request to complete that entire
WP again. The bounded path uses the same authenticated source reviewer and
default-branch publisher, but returns `acceptance_kind: "bounded-repair"`,
`wp_complete: false` and `dependency_unlock: false`. Evidence schema 3 is
deliberately rejected by full-WP gates and completion/accounting. Existing
whole-WP manifest verification stays unchanged, including unconfigured WPs.
This path does not enable the paused automatic landing controller or prove
branch protection. **Current paused/off policy still prohibits automatic
landing, even with valid bounded checks.** There is no new bypass merge API.
A supported supervised landing decision remains with maintainer `cbattlegear`:
it must retain exact candidate/current-base checks, conditional human gates,
serialization and actual no-bypass enforcement proof. That protected decision
is separate from this deployment; valid acceptance alone is not end-to-end
merge authorization and cannot import a WP completion. Do not substitute an
administrator `gh pr merge` for the blocked controller decision.

The authorized human coordinator first posts one structured scope comment on
the **existing repair issue**, not on an invented tracking issue. Its body is
`<!-- android-port-repair-scope-v1 -->` followed by strict JSON with these fields:

| Field | Required meaning |
| --- | --- |
| `schema_version`, `kind` | `1`, `"landed-wp-repair"` |
| `binding` | Exact seven-field repository/WP/current-base/head/source/manifest/policy binding used by native reviews |
| `repair_pr`, `repair_issue`, `landed_pr`, `owner`, `summary` | Actual PR/issue/merged producer, canonical manifest owner, bounded original issue scope |
| `paths` | Every server-reported changed path, including both rename paths: `{path, operation, capability}` admitted by trusted typed rules |
| `acceptance` | Nonempty issue-specific `{id, description}` criteria; do not reuse whole-WP criterion IDs |
| `coverage` | `{path, modules, source_paths, cases, native_methods, hosted_methods, adaptation}` for each changed Android behavior/test file |
| `verification` | Exactly `["android-scaffold"]`, mapping to the existing declared strict scaffold runner, not a guessed Gradle task |
| `native` | Explicit immutable independently retained native observations, or `[]` when affected behavior has a reviewed hosted native adaptation |

Coverage source paths must belong to the canonical WP and be nonexcluded.
Coverage `cases` items are `{path, id, parameter_family}` from the trusted frozen catalog;
the receipt must account for every selected parameter family. Every affected
module must be selected, including typed-capability-admitted support files.
The actual merged producer must be an ancestor of the current base and have
the canonical WP marker or legacy `[WP-xxx] ` title. Its actual server path
inventory must contain an actually repaired production path; added repair helpers
still need exact typed admissions and behavioral coverage. Legacy title provenance
does not manufacture a historical completion receipt or alter old reservations.

`hosted_methods` entries are `{path, blob_sha, id}` for actual Kotlin test methods
in a selected module's `src/test` tree. The publisher binds them to candidate
blobs, requires the real successful Linux Android build job and parses its
authoritative strict scaffold report with complete positive module counts.
The trusted runner now prints actual JUnit class/method identities, including
SDK/parameter suffixes, in that same hosted log. Selected methods must be present
in this executed inventory; a source function and green aggregate alone cannot
prove a filtered-out method ran. Older logs lacking this inventory cannot prove
a hosted adaptation; use the next actual exact-head hosted execution.
Missing, malformed, ambiguous, zero, failed, errored or skipped affected tests
fail closed. The candidate cannot redefine the trusted runner, its JUnit/count
validators, module graph or Android CI workflow. Extra selected tasks must also
be existing trusted scoped tasks. Hosted adaptations do not imply physical
TalkBack/radio/OEM/background/upgrade testing.

Each `native` observation supplies `{revision, transcript, sha256, api, methods,
bindings, fixture, out_of_scope_failures, execution}`. The transcript is fetched at its
immutable repository revision and checked against its actual bytes; `api` is
31 or 37; every selected `Class#method` must really pass. `bindings` explicitly
maps current production paths to unchanged blobs at that observed revision.
`fixture` is `{path, blob_sha}` at the observed revision. All other failed or
skipped methods must be explicitly retained in `out_of_scope_failures` as a
method-ID-to-explanation object, with actual statuses retained in the transcript; none may
be an affected method. This is **retained-source-continuity**, not standalone
execution of the latest head. For example an unrelated API37 integration
failure remains a failure; it never becomes a passing whole-suite claim.

Native transcripts must have complete ordered starts/outcomes with consistent
`current`/`numtests` and exactly one successful terminal runner record. Orphan
outcomes, missing starts, duplicate terminals, short messages/crashes and
post-terminal instrumentation are rejected.
The actual `INSTRUMENTATION_RESULT: stream=` completion must
include one `Time:` line and exactly one consistent JUnit `OK (N tests)` or
`FAILURES!!!` / `Tests run: N, Failures: F` summary. Lifecycle outcomes and the
completion summary must agree; missing/contradictory completion cannot be
accepted. Legitimate unrelated failures remain recorded, not forced to PASS.
Scope-supplied API labels alone
are not runtime evidence. `execution` currently supports the actual retained
`wp503-device-repairs-v1` family with `{format, integration, inputs, metrics}`;
each file reference is `{path, sha256}` at the observation revision. It reads
the existing `device-repairs-integration.json`,
`device-repairs-inputs.json`, `device-repairs-full-api<api>.txt` and matching
metrics rather than inventing a new execution manifest. These bind the real
WP503 integration head, both actual APK identities, runtime SDK profile,
complete transcript outcome accounting and executed source/fixture bytes.
Selected native methods must exist in the actual fixture at the execution head.
Other historical capture formats require a reviewed parser for their actual
records; no coordinator-supplied new schema can silently substitute for missing
execution provenance or imply a device replay occurred.

Using the existing authenticated read credential, stage without publishing:

```powershell
python .\tools\android-port\controller\native_review.py stage-repair --trusted-base <current-main-sha> --wp WP-xxx --pr <repair-number> --scope-comment-id <actual-issue-comment-id> --output <private-absolute-path-outside-repository>
```

The result is staged data only, not acceptance. The coordinator reviews the
complete immutable stage and posts the existing native review marker with
envelope `schema_version: 2`, `scope_comment_id`, and the normal `binding`,
`method`, `staging_sha256`, `review`, `evidence`, `approvals` fields.
Evidence schema 3 has the same fields as hosted evidence schema 2 plus
`repair_scope`, exactly matching the authenticated scope comment. Acceptance
records use the issue-specific criteria; review adaptation records explicitly
reference the selected originals. Real hosted run references and independently
retained evidence remain separate, and no second CI-result bundle is saved.
Do not turn source-only feedback into a structured receipt without this work.

After supervised deployment of this support, the existing `inspect` command
read-only verifies that actual receipt; only then does the authorized coordinator
use the existing manual workflow shown above. Scope edits, unexplained paths,
stale base/head, changed review bytes, unresolved findings, conditional human
gates and malformed evidence invalidate publication or replay. The publisher
adds only `issues: read` for the authenticated original-issue scope; it acquires
no issue writes, merge, dispatch, signing or fleet credentials.

For PR229/232/234, deployment alone is not acceptance: owners must supply
canonical ready/current-base metadata, and the coordinator must provide the
actual issue-scoped contract and exact receipt from real affected evidence.
This support neither publishes those receipts nor reruns their native cohorts.

### Bounded Rooms renderer support admission

The `rooms-incoming-avatar-repair` capability admits only WP-502's
`repair-room-incoming-avatar-bookends` operation on
`android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomConversationScreen.kt`
for issue #253. It does not admit generic `modify`, siblings, directories, auth,
services, state-holder changes or feature activation. WP-310 remains the primary
source owner; keep its frozen-source provenance and existing shared identity/theme
components, incoming gutter/bookends, self-row exclusion and once-only spoken label.

This candidate policy is not live authority. After independent protected review
and actual trusted-main deployment, the existing WP-502 producer must perform fresh
admission using that exact operation and evolve its same reservation with the actual
stored attempt/session/branch/worktree and current revision. Preserve original
authorization, identities and evidence; do not release/recreate or resume the retired
WP-310 producer. Reconcile disjoint intents; serialize/transfer real semantic overlap.
Transfer/reconciliation alone never grants renderer modification.

Mounted renderer assertions must cover the same-prefix burst, retained-row
reconfiguration, incoming gutter/avatar and self rows, plus once-only accessibility
and shared identity/theme behavior. State-only `showAvatar` assertions are not
rendered-avatar acceptance. Existing source, CI, original-case, human/protected and
exact-head/current-base gates remain. **Do not land this policy or alter native input
source while WP-503 holds either authorized native/Gradle operation; verified cleanup
must precede serialized landing.** Feature-branch preparation is not native authority.
Pause remains false, dispatch off and activation false.

The historical-policy reader reverses only this exact rule (including its
canonical position) before the existing debug/review projections. Original
policy receipts and acceptance remain historical; current bindings still hash
the full current policy. Broadened/duplicate/reordered rules or unrelated policy
changes fail rather than being silently projected away.

### Room message actions support (separate from avatar permission)

`rooms-message-actions` admits only WP-502's
`implement-room-message-actions` operation on seven explicitly named files in
`android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/`:
the existing `RoomConversationScreen.kt`, `RoomConversationStateHolder.kt`,
`RoomContracts.kt`, and new `RoomMessageAction.kt`,
`RoomMessageActionAvailability.kt`, `RoomMessageActionsSheet.kt`,
`RoomSendDMSheet.kt`. The operation covers directly necessary creation/modification
only; it is not generic `modify`/`create`/`delete`, a directory wildcard or authority
over App/services/shared models. These exact names were confirmed by the existing
producer in PR249 comment 6307954067. Existing App/resource capabilities stay
separate; primary WP-310 provenance and avatar-only authority are unchanged.

Preserve frozen Copy, Reply/Mention, Send DM and Send Again availability captured
at presentation, original incoming/outgoing/optional-RTT metadata, original text,
localized accessible presentation, dismissal/focus timing and long-press link
suppression. Consumer assertions must prove real clipboard, quote preference,
composer/focus, radio-scoped fresh blocked-excluding contact selection/navigation,
and original-text Room post without clearing an existing draft. Do not convert
Room messages to DM DTOs or add removed translation/actions/providers. No unrelated
auth, service, ordering, theme or activation changes are admitted.

The candidate is not live product authority. After reviewed trusted-main deployment,
the existing WP-502 owner must freshly admit the named operation and evolve the
same reservation using actual stored identity/attempt/current revision. Do not
rewrite authorization, release/recreate or infer permission from transfer. Semantic
overlap must serialize/transfer to the current producer. The exact new rule is
reversibly projected before avatar/debug/review history; old acceptance remains
historical while current bindings include the complete current semantic policy.

**The retained WP-503 native operation blocks main/policy landing.** This producer
performs no host/device/lease/recovery/local Gradle action. Publication may record
the exact candidate through `local.check.record_pending` and use an invocation-only
empty hooks path to avoid starting advisory Gradle under that hold, as explicitly
authorized for this repair. Installed hook configuration and normal nonforce
fast-forward push are unchanged. This is locally **UNVERIFIED**, never a successful
fast check or waived merge gate; exact isolated hosted CI and protected review
remain required. No runtime installation or native acceptance follows from admission.

### Mention picker contact metadata: local support successor

The independent `shared-mention-picker-repair` rule admits WP-502 only,
operation `repair-shared-mention-picker` only, and exactly three existing files
under `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/`:
`composer/ui/MentionSuggestions.kt`, `composer/ui/ChatConversationScreen.kt` and
`rooms/RoomConversationScreen.kt`. It does not expand either Room capability,
App/core paths or the shared `ContactMatchRow` implementation. Primary picker WP-308 and match-row
WP-306 provenance stay unchanged at frozen `db14559`.

Use the real Tap-style `ContactMatchRow` for matching picker rows: actual on-air
LAST_ADVERT (not recency), localized contact type, spaced key and location marker.
Preserve original contact order/identities, callbacks, dismissal, self/unknown/Done
and focus behavior, and source scrolling/large-font reachability with existing
theme/localization/identity/48dp semantics. Editor `MentionSuggestionList` and
`MentionSuggestionRow` bytes and behavior must remain unchanged. No fabricated
user location, distance or time; nil location does not establish full location
parity. No auth/service/radio/state/activation/provider change is admitted.

The actual WP-502 consumer report is issue135 comment6308826188 (source
`ba5073e6`, base dbb, same attempt/reservation r30). All three shared-sheet
callers must forward presentation inputs, including the actual App DM/channel
wrapper. Existing `app-build-launcher`/`update-launcher-or-composition` already
admits `AppChatConversationScreen.kt` and `AppChatsContent.kt`; no App path is
added here. Use actual `listFor(radio).clock` instants with the system display
zone, not forced UTC dates. Reuse actual authorized screen-local phone then
same-radio hasLocation node then nil; opening requests no permission/fix.
Room exposes only node fallback, not an app-wide phone cache: that integration
gap remains explicit. Bind existing approximate road-distance/away formatting
at App with current location/permission/device/locale inputs; no cross-feature
dependency or stale capture. Never claim exact Foundation/WGS-84 parity.

Preserve all matches (not the editor's 20-item cap), full keys and order; no
blocked-contact filtering or recency sorting. Require compact/200%/CJK/RTL
reachability, independent Done, dismissal before once-only full-contact-detail
navigation, no composer insertion/autofocus or Room/draft side effects. Keep all
35 incumbent methods/helpers/Fixture and frozen multiple-Alice test identities.

**Local and unpublished:** hold the successor for full bounded comparison and
current-base/consumer guards. No additional path is inferred; an actual newly required path needs
its own exact trusted mapping. Neither the existing seven Room paths nor generic
reconciliation/transfer admits this renderer. Candidate registration is not live
authority; reviewed deployment then fresh same-owner admission/evolution remain
required. Retained native/Gradle holds block all landing and local Gradle.

The exact picker-only reverse projection recovers the previous `ae583338`
candidate policy, and then reversing the unchanged Room-actions delta recovers
the entire actual dbb/e8 trusted policy. Current semantic bindings still hash all
rules; historical acceptance is never refreshed. Unknown/malformed/broad/
duplicate/reordered rules or unrelated semantic changes fail closed. Prior source
clearance and CI for ae583338 do not transfer to this successor.

### Narrow WP-503 standard debug packaging support

The `debug-test-packaging` typed capability admits exactly the support adapter
`tools/android-port/local/debug_packaging.py` and its regression test, not a
wildcard signing exemption. `CapabilityEngine.admit("WP-503", [], path,
"build-debug-test-apks-with-existing-local-identity")` is supported for that
adapter. Implementation ownership includes WP-003; actual assembly is restricted
to WP-503. Existing release/signing/hardware/legal human policy is unchanged.

The guarded WP-503 adapter calls `local.debug_packaging.assemble_debug_pair`
with its actual `manifest`, trusted `policy`, shared `ledger`, native-lane
`owner`, exact current source `revision`, existing pinned build-input `state`,
existing default `debug_home`, read-only `installed_apk` snapshot and new private
absolute `output`. The held native lock must identify that owner and
`payload.wp == "WP-503"`; this function does not release that caller-owned lock.
It separately acquires/releases the existing exclusive `gradle-execution` lane.
The only Gradle tasks are `:app:assembleDebug :app:assembleDebugAndroidTest`,
with strict dependency verification and one worker. There is no install,
instrumentation, uninstall, wipe, retained-APK resign or release task.
This full-assembly invocation explicitly selects the existing 2048 MiB Gradle
heap via `candidate_environment(state, local=True, full_assembly=True)`, not the
768 MiB local fast-check heap that exhausted D8 during real dex merging. Local
preflight/provenance, SerialGC, two-CPU/JIT/code-cache/metaspace safeguards,
environment allowlisting and the existing Gradle cache remain intact. Hosted
defaults and ordinary local fast checks are unchanged; no global/property
override or inherited arbitrary JVM options are used. This is a process budget,
not a claim that the reported build has been rerun or native execution passed.

The adapter checks exact clean committed Android production/fixture inputs,
pinned readiness/wrapper, and an already-existing default `debug.keystore` by
file metadata only. It never opens, copies, exports or changes private-key
bytes. Normal Gradle uses the already-configured local default debug identity;
no global HOME/tool configuration is changed. Public `apksigner` verification
and package inspection must match both newly built App/Test APKs to the
preserved installed debug APK certificate before returning packaging evidence.
Public signer output accepts exactly one SHA-256 identity in the pinned SDK37
`V2 Signer: certificate ...` format or the existing `Signer #N certificate ...`
format. Duplicate, mixed, unknown or malformed signer identities are rejected.
Java output and subprocess decoding use explicit strict UTF-8, not the Windows
legacy console codepage; a failed verification or decoding error remains fatal.
For a target the guarded caller has actually observed to be absent, pass
`installed_apk=None` explicitly: both current-built APKs still require their exact
packages and the same verified public certificate, but there is no preserved APK
to read or certificate to invent. The result records `installed_snapshot:null`
and `preservation_mode:"originally-absent"`. Non-None inputs retain the original
installed certificate and snapshot-stability checks and record
`preservation_mode:"originally-present"`; a missing/unreadable snapshot is an error,
never an implicit switch to absent mode. The adapter cannot observe a device:
absence, per-user selection, postbuild rechecks, install and removal of only
newly introduced packages remain the WP-503 guarded caller's responsibility.
Mismatch/build failure is explicit, releases only the acquired Gradle lock and
does not produce a success receipt or replace the installed app/data.

The new evidence records actual current source/tree, task/toolchain and APK/log
hashes and **zero tests started / no install / no acceptance**. Retain the
previous source/fixture continuity and actual failed install separately; new
hashes never overwrite historic APK identities. WP-503 must still independently
verify certificate/profile/data-preservation guards before any separately
authorized install/replay. This worker did not assemble, install or run a
device cohort.

Future failed Gradle commands emit available declared JUnit failure/error
package/class/test identities (including SDK/parameter test-name suffixes) and
actual totals before the bounded Gradle tail. Missing/malformed/zero reports
are explicit incomplete evidence, never a PASS. This writes no second CI-result
bundle or artifact. Lost historical f75 identities remain lost; independently
complete evidence for a new candidate is not invalidated forever by that gap.
The failure-log reader also streams every actual Gradle `Class > test[SDK] FAILED`
identity and test-count summary, with two bounded context lines on either side,
before the rolling12000-character tail. It never loads the complete log into
memory. Overlong physical diagnostic lines explicitly mark identity extraction
incomplete; the original command still fails with its real exit code.
Timeouts follow the same diagnostic path after the log is closed/flushed:
available JUnit identities/totals, streamed Gradle failure identities and the
bounded tail reach the authoritative hosted log even when `subprocess.run`
raises `TimeoutExpired`. Report discovery cannot prevent the log attempt.
Diagnostic failures are secondary and cannot replace the actual timeout, even
if stderr is unavailable. The original deadline and failure/no-success semantics
remain unchanged; this does not diagnose a test assertion from a timeout alone.

### Reference-oracle hosted capacity

Both reference oracles use the provider-documented standard `macos-26-intel`
host rather than the capacity-constrained ARM64 `macos-26` label. This preserves
macOS26, the frozen source, real SwiftData runtime, Swift6.2+ guards, original
test expectations and credential/ephemeral boundaries. The harnesses do not
require ARM64; actual compiler/host metadata remain execution evidence.
Provider references:
[standard labels](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
and [macOS26 Intel image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md).
The image lists the macOS26 SDK and Xcode26 family. This is supported-host
selection, not a claim that queued/cancelled historical jobs ran or that the
provider guarantees immediate capacity. Only actual exact-head hosted execution
proves the changed lane; no OS downgrade, self-hosted service or skipped oracle
is substituted.
