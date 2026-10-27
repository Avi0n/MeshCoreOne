# WP-404 evidence

## RADIO-R118-G09: source candidate after merged #350

Repository: `cbattlegear/MeshCoreOne-Android`; owner:
`platform-integrations-engineer`; START base
`610f606baaaf38eb2a2b14cb054b18fedba58a1f`, tree
`ced3cdbdc42cd7d22dc0bd33aef7b3c43e01eb6f`. The exact candidate is the
commit containing this section, not any earlier #350 candidate. Source remains
`db14559b39d32322b06477c6ae676112f583db50`. Fresh authenticated readback
confirmed prerequisite PRs #43 (WP-208), #49 (WP-209), #71 (WP-303), and #350
merged before the clean, authorized fast-forward START.

Retained group RADIO-R118-G09 assigns exactly these original families from
`MC1Tests/Intents/SendAdvertIntentTests.swift`:

| Original family | Authored native predicates |
| --- | --- |
| `gps gate is nil without A device()` | Null device returns no source and performs no preference read; policy-zero device performs no refresh/settings command. |
| `gps gate respects the per device preference()` | Original default-disabled, enabled/policy-zero, configured-DEVICE/policy-one predicates; foreign device opt-in isolation; real saved per-device preferences control actual advert and survive DataStore reopening. |

`ShortcutAdvertLocationTest` contains 17 mandatory, non-ignored methods. Beyond
the exact original predicates, they exercise actual App/service/protocol calls:
DEVICE read/enable/readback before advert; already-enabled GPS without a write;
PHONE verified coordinates before both reaches; declined flood confirmation with
zero refresh/wire effects and accepted confirmation with real refresh ordering;
no-device/disconnected/default/foreign-device/policy-zero gates; all three
unauthorized statuses without a permission call; typed unavailable location/GPS
refresh failures retaining the source's best-effort advert behavior; unchanged
cancellation; opt-out/public-policy changes during a fix; services-only loss
without stale-session writes; the existing real coarse-permission LocationManager
callback; actual OS permission revocation re-read; persisted preference reopening.
Literal firmware command order and coordinate bytes are independent assertions,
not values computed by the candidate helper. All earlier merged tests remain.

Production changes are limited to `AppAdvertLocation`, `AppShortcutRadio`,
`AppContainer`, and its real Android factory. The factory reuses the already
process-owned producer and real `DevicePreferenceStore.observe(id).first()`;
it never activates a new provider/permission/cache. `ContainerHarness` supplies
an explicit default-opt-out fixture whose location/prompt methods fail if
unexpectedly invoked, rather than silently accepting missing production wiring.
See the companion deviation for the existing absent cached-location operation.

Trusted path/operation admission passed for all six source/test paths and the
two owned evidence/deviation paths. Automatic same-owner reservation was
attempted and retained in this session's `wp404-g09-reservation.txt`; the
historical lease reports a different source/base/manifest/backend. It was not
released/recreated and no private ledger was edited. This historical advisory
binding is not a new edit veto under the current policy.

Actual lightweight checks:

```powershell
python -B .\tools\android-port\portmap.py
python -B .\tools\android-port\controller\validate.py
git diff --check
```

All exited 0 after the source/test integration. These checks do not compile
Kotlin or prove any G09 assertion passed. Android/JVM tests, lint, packaging
and the exact affected closure are **NOT RUN** for this new candidate. They
require the supported guarded remote route, an immutable candidate/declared
scope and actual capacity admission (maximum three heavy JVM jobs). No laptop
Gradle, new actor/session, push or PR has been run for G09. #350's successful
1,597/66 results do not provide G09 execution evidence.

## Guarded 822d result and bounded fixture corrections

Candidate `822d568de43b1bda5324bbf43896221512ed019c`, tree
`0a2c1fbefbeaf25bb9fd0cc615c6588d29c1c11c`, failed the supported guarded
affected check with actual OS exit 1 after 22m 51s (guard PID 127745, slot 1).
App JUnit reported 1,557 tests with two failures; shortcut JUnit reported 40
tests with no failures. Both suites had zero errors/skips. The managed snapshot
was still exactly this candidate before and after reading its XML: all 66
mandatory class/method identities were present, with 64 passing and two failing.
The retained 16 durable queue integrations all passed. These are results for
822d, not passing evidence for the corrected successor.

Source diagnosis of the two actual failures:

- `ShortcutConsumerRepairTest.servicesOnlyDisappearanceAfterClassificationEscalatesBothRecipientsWithoutEnqueueOrRadioChange`
  failed at line 151: expected one confirmation, observed zero. Both parameter
  cases share the process Room store and the same radio, but each inserted a
  fresh random-ID channel with the same secret. `ChannelRepository.saveChannel`
  updates by row ID, not slot; a retained first-case row therefore makes the
  second target ambiguous. The correct duplicate-safe resolver rejects it before
  confirmation, so the intended services-loss path was not reached. The fixture
  now reuses the persisted slot's row ID and additionally requires exactly one
  actual picker target before confirmation. Both direct/channel cases, real
  teardown, unchanged READY/radio, foreground handoff, and all zero-write/wire
  assertions remain.
- `ShortcutIndependentOracleTest.actualChannelAdapterAcceptsExactUtf8BudgetRejectsOneMoreAndRechecksChangedLiveName`
  failed at line 106: the initial 137-byte send raised `MESSAGE_TOO_LONG`.
  `ConnectionManager.replaceConnectedDevice` replaces only the live snapshot;
  `MessageService.rejectChannelText` independently reads the persisted device.
  The fixture left `TestNode` in Room (eight UTF-8 bytes, a 129-byte budget)
  while claiming an empty live name. The fixture now saves the same device
  update to Room and the live snapshot at both name changes, asserting the
  persisted/live names and adapter byte count. The independent 137/138 and
  131-byte thresholds, confirmation-time six-byte CJK rename, exact messages,
  and pending-row counts are unchanged. This is consistent fixture setup,
  not a claim of exercising the radio settings write protocol.

No production behavior or expected value was changed to accommodate either
failure. All 66 source method identities and original assertions remain; no
family is removed or skipped. The previous ten-task check did not complete:
App unit tests failed; App/shortcut Kotlin compile tasks were up-to-date;
App Android-test Kotlin compilation ran; shortcut unit tests passed. The App
lint, shortcut check/lint, module graph and runtime dependency inventory tasks
have no completion evidence and are not claimed as passed.

The corrected candidate is the commit containing this section, based directly
on 822d without another base merge. Its mandatory inventory remains
40 platform + 4 first App + 6 independent App + 16 durable integration methods.
Lightweight traceability, whitespace and source-inventory checks do not establish
Kotlin compilation or product acceptance. Corrected-candidate Gradle/JUnit,
lint and the complete affected closure are **NOT RUN** pending a new supported
slot handoff. The prior guard's owned lock was released and its recovery
directory removed. No retry, runtime/base/cache/ledger change, push or PR was
performed for these corrections.

## First guarded union attempt: traceability repair

The coordinator released the installed guarded remote lane and approved one
base reconciliation to `3a292b6498e8dcef0c374022a93c8e30c68f5bd5`.
Merge candidate `512c32edd51842eb1f3ed51a1a7f15cf3385d1fe`, tree
`de1613bc31a5dff1ed793d89b2a012e66ac3e023`, retained all prior source commits
and selected the two affected modules and ten tasks, not full-repository
scaffold. The unexecuted predecessor ticket was superseded by this union; no
predecessor PASS is claimed.

The actual trusted remote command was:

```text
python3.12 -B tools/android-port/local/check.py --commit 512c32edd51842eb1f3ed51a1a7f15cf3385d1fe --affected-base 3a292b6498e8dcef0c374022a93c8e30c68f5bd5 --parallel-jvm
```

It ran through the canonical external ledger and installed interlock, which
selected host fingerprint
`3ef4c5ed8f5cdc142f27b13d76551b492e5a9f8e961211dbe6ae05782b828fcc`,
slot 2, guard PID 55524. It exited 1 in the Python traceability stage:
`Conflicting traceability dispositions` in `ShortcutConsumerRepairTest.kt`.
This is an actual source-validation failure, not a memory, admission or test
failure. Gradle/JUnit did not run; product discovery remains zero.

The follow-up removes only the conflicting `AndroidOnly` header, retaining the
accurate pinned `PortedFrom` declarations and all test methods/assertions.
The complete 66-method union obligations remain required at the exact repaired
candidate. Earlier no-launch/NOT-RUN sections below describe their respective
source-preparation revisions, not a claim that this guarded attempt never ran.

## Independent shortcut oracles: RADIO-R118-G19, unverified successor

This is a separate source batch on the same WP-404 branch, based on
`6ed8daf2abc4127441244ed6410b96b3c05e7a95`. That first consumer-repair candidate,
tree `ef81bd3d9176bce6c6552c8de9d27b6c5ab45413`, and its prepared remote
snapshot/ten-task queue are unchanged. The successor is the local commit containing
this section; its exact head/tree are handed off separately. Neither candidate has
product execution evidence. No production changes or new defect claims are made
by G19.

Authority is the parent's explicit same-owner G19 assignment and the same R118
grouping input SHA-256 `62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
The immutable reference remains `db14559b39d32322b06477c6ae676112f583db50`.
Trusted admission accepted the new App test through `app-build-launcher` /
`add-direct-support-wiring`; shortcut tests and this WP's evidence/deviation paths
are `wp-owned`. Automatic claim again returned the preserved historical binding
mismatch, without deleting/recreating a receipt or mutating a private ledger.

Five methods in `IndependentShortcutOracleTest` (platform) and six methods in
`ShortcutIndependentOracleTest` (App) supply the following original-vs-new
predicates. Family labels below refer to the exact assigned Swift tests, not
execution on Apple frameworks:

| Original family | New independent predicate / native equivalent |
| --- | --- |
| EntityIdentity: contact ID derives from public key not row ID | Full literal `cd` public-key ID survives row replacement. |
| EntityIdentity: channel ID derives from secret digest not raw secret/index/row ID | Full literal `bb9c...` ID survives slot/row/name replacement; raw synthetic secret absent. An additional nonuniform UUID/secret vector pins uppercase UUID text and RFC UUID byte ordering. |
| EntityIdentity: contact and channel IDs distinct even when key matches | Identical key text produces independently literal `/contact/` and `/channel/` IDs. |
| EntityIdentity: distinct channel secrets do not collide | All eight original repeated-byte inputs match eight fixed full IDs and remain unique. |
| IntentBridge: adopt stores adopted app state | Native dispatcher drains the actual held request once to its installed owner. |
| IntentBridge: re-adopt replaces previous app state | A different second handler receives the actual next request; the first receives nothing further. Uninstall holds rather than delivers. |
| IntentErrorLocalization: localized string resource matches error description | Real Android text/executor output matches pinned English literals for the five owned categories and typed timeout. Android has one localization accessor, not Apple's second resource accessor; no new API or Apple accessor execution is claimed. Retained tests cover all 12 compiled resource locales. |
| SendAdvert: general error mapping never leaks raw errors | Actual adapter mapping plus executor/presenter, both reaches: raw unknown diagnostic is absent, timeout retains its payload, existing MESSAGE_TOO_LONG exception passes through by identity. |
| SendAdvert: map to intent error routes every case | All four AdvertisementError cases plus raw timeout, unknown error and existing ShortcutException; pinned literal feedback, cause/payload identity. |
| SendAdvert: success dialog reach-specific and honest | Actual advert service/session sends literal `07` / `0701` frames; real presenter must say exactly "Sent your advert to nearby nodes." / "Sent your advert across the mesh.", never "delivered". |
| SendMessage: channel message at limit accepted | Independent 137-byte zero-name and 131-byte six-byte-name limits reach the actual adapter and durable Room queue. |
| SendMessage: channel message revalidated against live node name | Confirmation changes actual connected node name from empty to two CJK characters (six UTF-8 bytes); the previously valid 137-byte body is rejected without another row. |
| SendMessage: channel service errors bucket into intent errors | All nine declared ChannelServiceError buckets have fixed expected categories/literal feedback; session payload identity retained. |
| SendMessage: connected without services never enqueues | CONNECTED must hand off before recipient access, confirmation or enqueue; tripwire collaborators fail if reached. |
| SendMessage: must foreground does not bump conversations version | Actual services-only teardown leaves READY/radio unchanged; real StateFlow observer and counter remain unchanged, with no added durable row. |
| SendMessage: overlong channel message rejected | Independent 138-byte zero-name body is rejected; original accepted row and pending-send counts remain exact. Platform also rejects 132 bytes against a six-byte name. |
| SendMessage: ready send bumps conversations version | Actual durable send increments by exactly one and reaches a real collector; original message and pending-send row exist. |
| SendMessage: service errors rewrap to localized intent error | Seven MessageServiceError cases, both queue cases and unknown fallback go through actual mapping and real executor with fixed category/copy; no rows/version changes on these injected failures. |
| SendMessage: status-unless-delivered fails pending but spares delivered | Actual Room pending row becomes FAILED through `chatsFeature.failPending`; ACKed DELIVERED row/text remain delivered/unchanged. |
| StatusQuery: absent battery reports no reading, not zero percent | Actual adapter/presenter maps both raw zero and null to the literal no-reading line, never `0%`. |
| StatusQuery: connected with battery speaks name and percent | Real READY adapter/presenter uses "Base Camp"/93 with no raw 4100; all three original connected-rung parameters additionally exercise the pure cached-snapshot dialog with that adapter-derived reading and literal expected copy. This is not execution of runtime transitions for the two other rungs. |
| StatusQuery: millivolts convert to percent through OCV curve | Actual adapter uses the original custom eleven-point curve at 4100 mV and must produce exactly 93, not a value derived from the production percentage helper in the assertion. |

Digest literals were independently calculated with Python `hashlib.sha256` over
the pinned Swift domain UTF-8, `uuid.UUID.bytes` and synthetic secret bytes. Ten
fixed SHA-256 vectors (the original eight, `0x5a`, and a nonuniform UUID/secret)
are not generated by candidate Kotlin. English expectations are literal pinned
source copy, not a second call to the same production provider. Error injection
exercises the actual mapper/executor but does not pretend to execute every
service failure; retained `ShortcutQueueIntegrationTest` methods still cover
actual Room rollback/reopen, session failures and SYNCING-to-READY queue drain.
Those 16 methods are unchanged, not replaced by fake queue tests.

Source review and `git diff --check` are the only candidate-source checks so far.
Kotlin compilation, Android/JVM tests, lint, controller/traceability verification
and device tests are **NOT RUN** for this successor. No OOM, test failure or
authentication failure is inferred from the absence of an assigned guarded
remote slot. No laptop Gradle/native process, remote heavy job, runtime install,
hook/config change, push or PR was started.

The affected verification contract remains the ten declared tasks listed in the
first batch's preparation section, using a separately bound exact successor plan
and the supported guarded remote route. Expected source inventory is now **40**
platform shortcut methods, **10** methods across the two App shortcut consumer
classes (four retained plus six new), and the **16** unchanged queue-integration
methods within the complete App suite. These are inventory expectations, **not
discovery or passing counts**. The first candidate's eventual result cannot count
as verification of this successor. G09 GPS, G18 adaptation, Apple-only second
localization accessor, full-WP acceptance and device/human gates remain excluded.

## Shortcut consumer repair batch (2026-10-26): unverified local candidate

Repository `cbattlegear/MeshCoreOne-Android`; owner
`platform-integrations-engineer`, implemented by the assigned DEFAULT runtime using
the tracked role and android-port-wp/Swift-to-Kotlin/Compose skills, not injected
custom-profile execution. Session `a8b04b96-985a-4d80-a85d-bf5188cd6d8a`;
branch `cbattlegear-wp-404-shortcut-consumer-repairs`; base
`8a14eb2d90014f793de8e16e32fbcc5af5becee3`. The candidate is the local commit
containing this section; its exact head is reported to the coordinator for later
immutable-input verification. Local persistence is not verified publication.
Source pin remains `db14559b39d32322b06477c6ae676112f583db50`.
Base manifest Git blob `582edce11e1cb7761bc1b8f2de7e5c1caaeae27a`;
base policy Git blob `b1c1ef30533f2f424d1c33e23ede04fafebce014`.
Prerequisites were checked as merged PRs #43 (WP-208), #49 (WP-209), #71 (WP-303);
later repairs do not replace that prerequisite lineage.

The assigned read-only R118 grouping input's SHA-256 was checked:
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
The retained groups and exact source families are:

| Group | Consumer repair and authored regression |
| --- | --- |
| RADIO-R118-G06 | App picker/publisher use duplicate-safe targets; `EntityQueryScopingTests` duplicate-digest picker family in `EntityIdentityTests.swift`. App/Room regression covers duplicate named slots, a named/unnamed collision, current/foreign radio scoping and real ShortcutManager publication. Existing platform test remains; a new unnamed-collision method supplements it. |
| RADIO-R118-G07 | Status reads the existing persisted cached name; `StatusQueryIntentTests.swift` connecting and disconnected-known-radio families. App regression holds the actual connection transport at CONNECTING and asserts cached name with no invented live name/battery. |
| RADIO-R118-G08 | Services-only disappearance with READY/radio unchanged returns foreground recovery before any pending write; `SendMessageIntentTests.swift` services-nil-after-classify family. Actual App graph teardown during confirmation covers direct/channel executor handoff and zero durable/wire sends. The platform original-case annotation now belongs to a distinct services-only regression, not a state-changing disconnect test. |
| RADIO-R118-G10 | Advert fallback uses existing advertisement copy; `IntentErrorLocalizationTests.swift` own-key and session families plus `SendAdvertIntentTests.swift` every-case/general-error families. App regression checks five keyed cases across all 12 locales, both reaches through the real executor/presenter, adapter-mapped fallback causes and unchanged typed session payload. Fault injection here is not claimed as wire/service execution. Existing real advert-service integration tests are retained unchanged. |

Acceptance IDs affected: `WP-404-behavior`, `WP-404-boundaries`,
`WP-404-source-test-parity`. Four new App regression methods and two new platform
methods are authored; **zero product tests executed for this batch**. This is not
full-WP acceptance or a new passing original-family count.

Trusted `reserve.py admit` accepted the owned shortcut/evidence paths and App
support using `app-build-launcher` / `add-direct-support-wiring` and
`update-launcher-or-composition`. Generic `modify` correctly rejected App and
ui-tests paths; the new consumer tests therefore live under the admitted App test
tree. Both actual automatic `claim` attempts returned
`BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`.
This historical advisory mismatch was preserved, not deleted, impersonated or used
as a local-edit veto under current AUTOMATION/ADR-006. No live semantic overlap was
identified in the assigned consumer surfaces.

Readiness command executed successfully from this worktree:

```powershell
& 'C:\Users\camer\.copilot\session-state\902ae61f-c20e-498b-8312-966b19bf8be6\files\prepare-worker-readiness.ps1' -Artifacts 'C:\Users\camer\.copilot\session-state\792767ba-cf3e-4b83-b321-b5c0d0cd39b0\files'
```

It creates this worker's own isolated inputs and invokes the declared
`ci.py preflight --state <own environment.json> --output <own result> --local`.
`worker-readiness.json` reports `environment_ready: true`; this is environment
readiness only, not product test/build/native acceptance. `git diff --check`
passed. Frozen Intents source/test paths showed no diff against the immutable pin.
Admission/claim diagnostics remain in the worker's session artifacts.

**Execution blocker:** the coordinator's explicit memory-pressure hold forbids
Gradle, emulator and heavy full validators until a supported serialized lane and
capacity decision are supplied. No resource failure was observed by this worker;
the hold is not misreported as an OOM, failed test, authentication failure or
environment-preflight failure. No runner/lock/runtime bypass, provisioning,
process termination, dependency changes, push or PR occurred.
The coordinator explicitly requested one local commit for exact-candidate
persistence while keeping the hold. **Android execution: NOT RUN.**

After that hold is lifted, run the actually declared relevant strict verification
route, not the historical lenient command below. The documented full fallback is
`ci.py run --stage scaffold --local`, with the required preparation/inspection and
ownership/traceability checks; targeted execution must be a supported declared
route. Actual nonzero discovery and passing required tests/build/validators are
mandatory before this batch's single finished push and template PR. The exact-head
hosted job/log will be authoritative; no duplicate CI-result bundle is retained.
No physical/device, signing, legal or human-gate acceptance is asserted.

### Parallel-work resumption and exact affected selection

User-directed resumption releases the old blanket idle hold for existing owners;
it does not authorize laptop Gradle/native execution or an unassigned remote
heavy-test slot. The original local source commit is
`1388858bda904bd0e2dc0e7173543a69ee9f3b25`. Current trusted main
`a8c624bcb095f81a30e5fee671099fa519f608b7` merged without product-file overlap
as `d0b8f3b2d50bd44efa7991079c44c6642b17cced`, bringing in the landed
affected verification selector. The working candidate is the commit containing
this preparation section; its final hash is supplied to the coordinator.

The read-only `controller.local_scope.candidate_plan` evaluated the exact current
base/head/tree/diff and selected `module:app` and `module:platform/shortcuts`,
not a full-repository fallback. Its actual declared tasks are:

```text
:app:testDebugUnitTest
:app:compileDebugKotlin
:app:lintDebug
:app:compileDebugAndroidTestKotlin
:platform:shortcuts:testDebugUnitTest
:platform:shortcuts:check
:platform:shortcuts:compileDebugKotlin
:platform:shortcuts:lintDebug
validateModuleGraph
runtimeDependencyInventory
```

The documented owned launcher request is
`python .\tools\android-port\local\check.py --commit <exact candidate> --affected-base <current trusted main>`.
The derived stages are `python,preflight,scaffold`; arbitrary Gradle/test-filter
overrides are not accepted. A remote transport is not inferred from the existing
WSL/Linux launcher. The coordinator assigns the actual supported remote slot
before any heavy command starts.

Expected source inventory: 35 platform shortcut methods, four new App consumer
methods, and 16 retained `ShortcutQueueIntegrationTest` methods (not a substitute
for the selected complete App suite). Missing, zero, failed or skipped required
execution is a failure, not evidence inferred from this inventory.
**Product test discovery/execution is still zero at this preparation revision.**
Owned worktree cleanliness, whitespace and trusted App support admission were
rechecked; no policy, hook, runtime, dependency, source pin or test assertion was
weakened.

## Historical original implementation and earlier repairs

External contribution; no lease. No device verification claimed.

## Scope
Dynamic App Shortcuts (status, zero-hop/flood advert, per-recipient send), exported text share target,
typed request parsing, cold-start dispatcher and confirmation-gated flows in `android/platform/shortcuts/`,
behind platform-owned ports (no `core:services` dependency). See `docs/android/deviations/WP-404.md`.

## Source-test parity
Swift sources `MC1/Intents/*` and tests `MC1Tests/Intents/*` at db14559b are the spec. Test cases
are named by `@SourceCases` annotations in the JVM tests:

- Adapted: EntityIdentityTests (id round-trip, malformed ids, contact/channel identity, radio scoping,
  duplicate/zero digest resolution, picker filtering), SendMessageIntentTests (routing matrix,
  validation, confirmed queueing, state change during confirmation, node-name revalidation, error
  mapping), SendAdvertIntentTests and StatusQueryIntentTests (reach ids, honest dialogs, errors,
  cached status).
- Not applicable on Android (framework-specific): `supportedModes`, `IntentBridge` adopt/re-adopt,
  `OpenRadioStatusIntentTests`, GPS-gate tests (owned by the services layer), localization-resource
  tests for App Intents metadata (the l10n WP owns the resource conversion).
- Android-only boundary tests: invalid/ambiguous/oversized input, stale route, denied confirmation,
  cold-start hold/expiry, shortcut plan caps.

## Verification
```text
android/gradlew -p android :platform:shortcuts:testDebugUnitTest :platform:shortcuts:compileDebugKotlin \
  :app:assembleDebug validateModuleGraph --dependency-verification lenient
python3 tools/android-port/portmap.py
python3 tools/android-port/controller/validate.py
```
Results are recorded in the pull request description.

## Bounded session-error contract repair (2026-10-19)

This section records new work for issue #217, not acceptance of the original PR #151 or
full-WP completion. Repository: `cbattlegear/MeshCoreOne-Android`; current-base input:
`d8181418614a5acaa46f4acde54b741b492ac4b0`; source pin unchanged:
`db14559b39d32322b06477c6ae676112f583db50`. The new candidate head and hosted check
identity are in the repair PR, not a retained second CI-result bundle.

The supported helper acquired an initial WP-404 reservation for the existing session
`c90213ab-e764-4161-a7c2-40fd250bbd33` (no previous WP-404 record was present).
Revision 2 admits only the directly required
`android/gradle/dependency-locks/platform-shortcuts.lockfile` via
`dependency-resolution-metadata` / `add-resolved-lock`, in addition to the manifest-owned
shortcuts/evidence/deviation paths. No release, recreated historical claim, SQL edit,
protected approval, or new worker was used.

Contract: `ShortcutError.SessionError(underlying: MeshCoreException)` is a payload-bearing
case; the existing uppercase cases and their `name` values remain. `ShortcutException`
retains a supplied outer cause, defaulting to the session payload when none is supplied.
`ShortcutText.error(ShortcutError)` and the executor preserve the full variant; Android
text selects the underlying compiled localized MeshCore/firmware copy, including format
arguments. The App owner in WP-502 remains responsible for mapping actual service
wrappers to this variant. No App adapter changes or complete #217 resolution are claimed here.

`SessionErrorTest` ports the recursion/no-own-key families in pinned
`IntentErrorLocalizationTests.swift`: 17 MeshCore case/payload variants plus six known and
three unknown firmware code cases in each of 12 locales. It also verifies actual
executor presentation for direct/channel sends and both advert reaches, payload identity,
outer-cause preservation and cancellation propagation. These five new methods supplement,
not replace, the existing 28 methods. They run with the existing pinned offline Robolectric
API-31 convention and real AAPT-compiled resources, not device/hardware evidence.

Native Windows verification (environment initialized from the provisioned pinned toolchain,
explicit private Gradle/Android cache roots, and the wrapper's existing credential allowlist):

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 2048m -TestHeap 512m -GradleArguments @(':platform:shortcuts:dependencies','--write-locks','--dependency-verification','strict','--quiet')
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 2048m -TestHeap 512m -GradleArguments @(':platform:shortcuts:testDebugUnitTest','validateModuleGraph','--dependency-verification','strict')
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 2048m -TestHeap 512m -GradleArguments @(':platform:shortcuts:check','--dependency-verification','strict')
python tools\android-port\portmap.py
python tools\android-port\controller\validate.py
```

Results: resolved test-only locks; readiness passed for JDK 21.0.12.1, compile API 37.2,
build-tools 37.0.0; Gradle passed in 2m12s with **33 discovered/run/passed, zero
failures/errors/skips**, including all five new methods; 30-module graph passed;
portmap and controller validation exited 0. Localization conversion ran 74 assertions
successfully as a build prerequisite. The module check (lint plus tests) also passed
in 1m34s. The first run correctly rejected mixed
`AndroidOnly`/`PortedFrom` dispositions in the new mapping header; the header was corrected,
not the validator weakened.

Formal WP-404 manifest verification remains unconfigured. This is bounded implementation
and regression evidence for the session-error contract, not renewed historical acceptance,
independent review, physical-radio verification, or a hardware/legal/release gate approval.
