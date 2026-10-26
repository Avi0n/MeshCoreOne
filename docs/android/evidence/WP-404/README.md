# WP-404 evidence

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
