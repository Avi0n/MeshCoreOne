# WP-404 evidence

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
