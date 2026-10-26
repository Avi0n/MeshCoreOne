# WP-211 regional-label localization repair

Repository: `cbattlegear/MeshCoreOne-Android`.
Batch: `SM-REGION-LOCALIZATION`.
Base: `f2f481d2e7c2a9029808943529c98c98ef879299`.
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
Two added production-flow Compose tests select localized US states, switch
countries, select the localized Australian territory and commit the stable
`AU-NT` identity without applying a preset or completing onboarding.
These are authored assertions, not discovered passing tests.

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

Product execution remains blocked by the coordinator's explicit capacity
hold. No Gradle/native/remote run, push or PR has occurred. Required work
before publication remains the declared targeted `:app:testDebugUnitTest`
selectors for `OnboardingRegionPresetAdapterTest`,
`LocalizedRegionCatalogAdapterTest` and `OnboardingRegionPresetComposeTest`,
plus affected module build/lint and trusted ownership/traceability validators,
with actual nonzero discovery and no failures/errors/skips. Existing
`RegionalAreasTest` families must remain intact. No outcome is claimed for
these unexecuted commands, nor for API33+/hardware accessibility or signing.

Exact-candidate hosted logs remain authoritative when publication is allowed;
this document is implementation scope/status, not a duplicate CI-result bundle.
