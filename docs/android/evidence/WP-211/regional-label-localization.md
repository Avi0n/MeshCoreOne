# WP-211 regional-label localization repair

Repository: `cbattlegear/MeshCoreOne-Android`.
Batch: `SM-REGION-LOCALIZATION`.
Original authoring base: `f2f481d2e7c2a9029808943529c98c98ef879299`.
Assigned execution base: `3a292b6498e8dcef0c374022a93c8e30c68f5bd5`.
Branch: `cbattlegear-wp211-regional-label-localization`.
Source: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
Candidate: local-only source commit containing this document; unpublished
and not product-verified. Resolve its exact head with `git rev-parse HEAD`.

## Scope and admission

Acceptance references: `WP-211-behavior`, `WP-211-boundaries`,
`WP-211-source-test-parity`, limited to retained regional display names.
The tracked `services-porter` profile was read and applied in the assigned
DEFAULT runtime; no additional agent or session was launched.

Trusted-main capability `wp211-regional-label-localization`, operation
`repair-regional-label-localization`, admits the five exact existing catalog,
composition, factory, adapter-test and mounted-picker-test paths.
The first reservation was acquired under actual session/attempt
`36ef9683-27f2-428b-933f-9e704cad6200`, revision1; same-owner evolution to
revision2 adds only WP-owned deviation/evidence paths. Immutable authorization
context and the source pin are preserved. Historical overlapping advisory
intents do not represent an observed live semantic collision.

## Behavior and assertions

The production factory provides current app-localized resources, including
the existing API31/32 app-language configuration context. The catalog reuses
the generated subdivision resource mapping when invoking the existing
Android-free regional resolver. No resource/generator, persisted identifier,
radio preset, RxLog or ConnectedDeviceEditor change is included.

The added adapter tests cover app-language changes on one existing catalog,
French/German/English California, Pennsylvania, Texas and Australian Northern
Territory labels, US/CA/non-US display formatting, localized subdivision
ordering, unknown subdivision absence and unchanged catalog counts/IDs.
The country-order regression primes the core's global lazy ordering while
holding the system locale at US, then asserts locale-collated country IDs
and labels for French, German and English on the same app catalog. It also
asserts that at least one app-locale order differs from the cached core order,
so the original stale-cache consumer cannot satisfy the regression. This
assertion passed in the first full supported candidate run. No executed
old-code mutation/failing-before comparison is claimed.
Two added production-flow Compose tests select localized US states, switch
countries, select the localized Australian territory and commit the stable
`AU-NT` identity without applying a preset or completing onboarding.
The first supported full run discovered all these assertions; the adapter
methods passed while the two new mounted methods failed on the final
invalid scroll gesture described below. Corrected mounted assertions are
not yet verified.

## Local status

The declared `local-inputs` and `preflight --local` completed successfully
using session-owned state and existing installations before this source edit.
That environment result is not product evidence or transferable acceptance.
`git diff --check` passed. The lightweight
`python tools\android-port\controller\validate.py` passed with 1,866 tracked
reference inputs and the unchanged manifest digest.
`python tools\android-port\portmap.py` passed after retaining the incumbent
Android-only test-file disposition: the additional source reference is
`ReferenceInput`, not a conflicting `PortedFrom` claim, and the mounted
WP-502 test keeps its incumbent owner header. Named-capability admission and
this document trace the bounded support change; file headers are not parity.

## First supported product run and bounded test correction

The candidate integrated the coordinator-assigned installed execution
baseline `3a292b6498e8dcef0c374022a93c8e30c68f5bd5` once, producing
`f76cd93484f521f10668d8037c7a80a506c16c80` / tree
`2abac691e5d495e5ca69d3efb50f9ec3e609d7bd`. Against that baseline the trusted
actual-diff/reverse-consumer planner selects the complete `module:app`
runner, not filtered methods:

```text
python3.12 -B tools/android-port/local/check.py --commit f76cd93484f521f10668d8037c7a80a506c16c80 --affected-base 3a292b6498e8dcef0c374022a93c8e30c68f5bd5 --parallel-jvm
```

Executed on `meshcore-build` through the canonical installed supervisor and
shared ownership route, this command **failed, exit1**. The complete App unit
runner discovered **1,551 tests across 188 suites: 1,549 passed, two failed,
zero errors/skips**. All 16 mandatory method names were present: incumbent
adapter5, localized adapter2 and incumbent mounted7 passed; only the two new
French/German mounted methods failed. Their final `performScrollTo` targeted
the fixed Continue button, which has no scroll-semantic ancestor. The
localized state/territory selection assertions had already executed.

The corrected test asserts Continue is displayed and enabled, then clicks
it without inventing a scroll action. Stable `AU-NT` commit, disambiguated
display, no preset application and no onboarding completion assertions remain.
No production code or expected localized labels are changed by this correction.
The corrected candidate is **unverified** and requires a fresh supported run.

The derived tasks remain `:app:testDebugUnitTest`, `:app:compileDebugKotlin`,
`:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, `validateModuleGraph`
and `runtimeDependencyInventory`, plus Python and preflight stages. The
failed Gradle execution does not prove that every remaining selected task
completed. No overall build/lint/module-closure PASS is claimed.

Guard PID139284 exited; subsequent canonical read-only reservation status
showed no retained own slot2 owner. No ownership row was cleared manually.
No push or PR occurred. Existing `RegionalAreasTest` families are unchanged.
No API33+/hardware, native accessibility, signing or release result is claimed.

Exact-candidate hosted logs remain authoritative when publication is allowed;
this document is implementation scope/status, not a duplicate CI-result bundle.
