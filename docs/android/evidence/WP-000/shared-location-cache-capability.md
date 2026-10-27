# WP-000 shared pre-advert location cache support

## Separate assignment and source correspondence

This policy-only batch starts at clean trusted main
`d2b649421ae7f31b2ef94b7dee20894abe658d22`, including merged PRs #351,
#352 and #353. The existing producer follows tracked port-orchestrator/common
WP instructions, without custom profile injection, another actor or product
implementation. The source pin remains
`db14559b39d32322b06477c6ae676112f583db50`.

The coordinator shared the complete read-only WP-404 support proposal and
parent-endorsed minimal placement. Pinned `MC1/Services/LocationService.swift`
holds one app-wide optional `currentLocation`, writes a genuine delivered fix
before resuming its waiter, and retains it on denial/failure. Pinned
`MC1/State/AppState+DeviceActions.swift` reuses that fix after phone refresh
failure or unauthorized no-prompt advert preparation, subject to per-device
auto-update/public-location policy.

The actual native `LocationManagerLocationProducing` has request ownership,
cancellation and signal cleanup but no retained successful coordinate. The
shared frozen `3db4cbd567465b9071f4bad8afd5bd2ee90c2f47` factory feeds the
existing process producer to distinct RegionResolver and advert
`LocationService` wrappers; that advert seam is not yet on this batch's main
base. A wrapper-private cache would miss the other
wrapper's successes. The minimal proposed repair keeps one initially absent
immutable coordinate in the existing producer and binds a read-only getter
to advert preparation. No core contract or unrelated implementor change is
necessary or admitted.

Actual trusted-main admission of WP-404 operation
`restore-shared-pre-advert-location-cache` at the native producer was refused:
no trusted path+operation rule. The separately reported core `LocationService.kt`
`add-direct-support-wiring` refusal remains respected. No WP-218 actor/owner
is invented, and primary provenance is not reassigned.

## Exact capability and retained gates

`wp404-shared-location-producer-cache` admits only WP-404 named operation
`restore-shared-pre-advert-location-cache` at exactly:

- `android/app/src/main/kotlin/com/meshcoreone/android/app/content/LocationManagerLocationProducing.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/content/LocationManagerLocationProducingTest.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppAdvertLocation.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AndroidAppContainerFactory.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/container/ShortcutAdvertLocationTest.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/container/ContainerHarness.kt`
- `docs/android/deviations/WP-404.md`
- `docs/android/evidence/WP-404/README.md`

Producer scope is only the shared successful-fix cache and its typed read.
Factory scope only binds that read to advert preparation, retaining the existing
producer, RegionResolver binding and process cleanup. Consumer/test/helper scope
is the matching shared-cache fallback and its real guarded integration proof.
The two WP-404 docs record only this repair and honest evidence. Existing generic
WP-owned doc operations remain unchanged; this adds only the named operation.
The older `app-build-launcher` still admits its own `add-direct-support-wiring`
operation on App paths; that existing authority is neither expanded nor removed.
It does not turn a core-path refusal into permission for a shared core contract.

Require genuine owned success publication under the active-request lock before
waiter resumption; later success replaces it. Failure, null fix and denial retain
the prior fix. Cancelled, stale and late callbacks cannot publish a successor
fix. Genuine cancellation must propagate without cached write/advert fallback.
Preserve per-device/public policy, confirmation, no-prompt, current session/
generation and cancellation gates. No previous cache means the existing
best-effort no-location-write advert, not fabricated coordinates.

No content glob, cross-WP authority, new core API, fake implementor, private
wrapper cache, OS last-known lookup, TTL, persistence, provider, permission,
Google dependency, build change or unrelated WP-218 repair is authorized.
Preserve coordinates/secrets, original data and license obligations.

Consumer implementation requires trusted-main capability admission and actual
closeout of frozen diagnosed-run candidate
`3db4cbd567465b9071f4bad8afd5bd2ee90c2f47`,
tree `2addde0d74fa77e0a47d59b5f49b58fc794a59c0`.
Its 17 mandatory methods/six tasks and execution are separate, not acceptance
claimed here. Keep the existing owner/reservation/authorization; serialize or
transfer a real producer semantic collision with original evidence. No live
runtime/cache/ref/default installation, jobs, resource draining, settings,
dispatch or merge authority is granted.

## Admission, inverse and declared verification

The new rule is inserted before the unchanged RX source-regression rule.
Its exact inverse runs before the existing RX/backup/JVM/WP503/consumer/older
chain. Complete actual-d2b policy object and raw Git-blob equality precede all
retained older assertions. Every old capability and planner bridge is preserved,
including #353's nonancestor fix.

Six focused regression methods cover all eight positives, every other manifest
WP, wrong/cross-purpose operations, nearby content/core/build/docs paths,
traversal, mutation, duplicate/order/overlap/malformed-policy rejection, complete
predecessor equality/round trip and source/lifecycle/privacy/evidence fences.
These fixtures prove admission only, not cache behavior, JVM/native execution,
hardware or original-family acceptance.
The first 104-test run found six incorrect negative expectations: the older App
wiring operation was already admitted on all six App paths. Tests now separately
require new-rule-only rejection and exact preservation of those six predecessor
admissions; actual full-policy denials for other wrong operations remain. No
policy assertion or old rule was weakened to hide this fixture error.

Declared bounded commands:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\tests"
python -m unittest -q test_consumer_support_capabilities test_scope_amendment test_capabilities test_disposable.DisposableGuestTests.test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\controller\workflows.py
python .\tools\android-port\portmap.py
git diff --check
```

Corrected complete focused run: 104 run/passed, zero failures/errors/skips,
14.840 seconds, exit 0. `validate.py`, `verification_config.py --check`,
`workflows.py`, `portmap.py` (entire output consumed) and `git diff --check`
each passed with exit 0. Exact frozen-candidate results belong in the protected review handoff/PR and
authoritative hosted job, not a second reproducible CI-result bundle.
Full controller/local suites, all-stage fast/Gradle and native/hardware/iOS/
Android/signing/legal verification are NOT RUN / UNVERIFIED for this amendment.
Future actual cache tests must cover API31/API37 success-before-resume,
replacement/retention, callback/cancellation races, RegionResolver-to-advert
sharing, actual App/Room/radio literal write-before-advert, no-cache behavior
and all zero-forbidden-effect policy/session/cancellation cases, with exact
source-bound identities and nonzero discovery.

The supported own WP-000 claim attempt again reported an existing
source/base/manifest/backend mismatch. The historical typed receipt is bound to
a different stored identity/branch, so it was not impersonated, evolved,
released/recreated or manually rewritten. All six support policy/test/doc
paths separately passed trusted WP-owned `modify` admission before edits.
Read-only documented controller status is off/paused and does not authorize
execution or merge. Parent retains protected exact-head source review and normal
merge. No earlier publication grant is reused; no push/PR is authorized yet.
