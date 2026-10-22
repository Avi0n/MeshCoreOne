# Seven-row message-footer contribution original

WP-501 / test-parity-engineer, existing issue #134 and assigned worktree/branch.
Implementation parent: `4526d18bc347a551f803d50d556ea794c896163f`.
Compared current main: `5d970c8e95a184a1e299220a189f5e0f622cef42`.
Frozen specification: `db14559b39d32322b06477c6ae676112f583db50`.

## Missing family, not a missing filename

The complete frozen `MessageBubblePredicateTests.swift`, its complete
`MessageBubbleTestData.swift` helper, and the production footer builder were read.
The selected original is
`MessageBubblePredicateTests::hasFooter true iff any of hop/path/region contributes(testCase : HasFooterCase)`.
Its seven argument rows exercise the production builder, then assert the OR
of `footer.showHop`, a nonnull formatted path and a nonnull region.

All matching native footer assertions were inspected. The existing
`RenderingModelsTest` footer case combines hop, path and ambiguous region in
one positive input, then checks outgoing, legacy direct, legacy scope and
disabled-region variants. `MessageFragmentBuilderTest` covers footer counters
and send times. Neither exercises this seven-row contribution matrix; in
particular an all-on positive cannot detect a dropped path-only contributor
or an erroneously displayed direct-route region when every other contributor
is off. Existing timeline tests concern grouping and anchors, not this algebra.
No missing-join/header count is used as evidence of a missing executable family.

## Exact original-to-native assertion mapping

`MessageFooterCompositionOriginalPredicateTest.allSevenOriginalFooterContributionRowsUseTheProductionBuilder`
calls actual `MessageFragmentBuilder.makeItem` for every row and evaluates
the original OR on its actual returned footer.

| Row | Hop flag | Region flag | Route | Scope | Formatted path | Expected OR |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | false | false | direct | null | null | false |
| 1 | true | false | flood | null | null | true |
| 2 | false | false | direct | null | A3,7F | true |
| 3 | false | true | flood | US | null | true |
| 4 | false | true | direct | US | null | false |
| 5 | false | true | flood | US | A3,7F | true |
| 6 | true | true | flood | US | A3,7F | true |

This is one original parameter family, seven checked inputs and one JUnit
method, not seven discovered methods or seven new original families.
The original fresh IDs, incoming/delivered Test DM, nil contact/channel,
timestamp 0, path length 2, A3/7F bytes, read flag, empty region candidates,
helper grouping flags, unresolved Unknown sender, preview defaults and
path/hop/region flags remain. Only irrelevant ambient creation time is frozen
to `Instant.EPOCH`; none of the selected footer predicates reads it.
Swift MainActor construction is adapted to a synchronous pure JUnit4 method.
There is no mounted Compose, screen-reader, UI layout, radio, network, Room,
native/device, error/cancellation or whole-footer-suite coverage claim.

The selected native builder, DTO, input/environment and incumbent footer-test
bytes equal actual current main. No production, build, primary-owner test or
active SessionLifecycle/CorrelationRecovery/Rooms/mention path changes.
The explicit partial-source comparison in `review-proposals.json` binds the
one original ID, native method and actual inputs; archived generated groups,
accounting and review-binding outputs remain historical.

## Admission and verification boundary

Trusted `reserve.py admit` maps the new test to `wp-owned/create`.
The existing same-owner reservation evolves 118 -> 119 for this test,
the explicit comparison and directly related WP-501 evidence/deviation paths;
stored attempt/session/authorization are preserved.

The existing App source set already includes `parity-tests/src/test/kotlin`.
Declared runner: `:app:testDebugUnitTest`, focused selector
`--tests com.meshcoreone.android.parity.MessageFooterCompositionOriginalPredicateTest`.
Required exact class/method discovery is one non-skipped passing method;
no Kotlin discovery, compilation or execution result is claimed here.

`python tools\android-port\controller\ci.py preflight` returned exit 2:
`BLOCKED: Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
No state, resource release or runtime installation is fabricated; no native
or Gradle lane is acquired or bypassed. Local functional status is
**UNVERIFIED**, not passed. Source-only command results are recorded after
the implementation is checked; they do not substitute for this runner.

Source-only results for the four-path implementation:

- `python tools\android-port\controller\validate.py`: PASS; pinned source,
  manifest, ownership and dependency graph valid.
- `python tools\android-port\portmap.py`: PASS; the exact new test resolves both
  frozen source headers. Provenance is not behavioral evidence.
- `python android\parity-tests\semantic.py --check`: PASS; the new explicit
  partial-source join resolves one actual native assertion body and runner
  identity. 1480 candidate-compared families is not an accepted-family count.
- `git diff --check`: PASS.
- `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet`:
  PASS, 108 discovered/run/passed, zero failures/errors/skips. These are the
  existing accounting/semantic-binding/acceptance controller fixtures, not
  execution of the new Kotlin method or evidence of seven native passes.
- Direct read-only frozen/native tuple comparison: exactly seven equal rows,
  preserving original order, flags, route, nullable scope/path and expectation.
  This is source inspection, not Kotlin execution or seven discovered tests.
- `python tools\android-port\local\check.py --worktree --stages python`:
  BLOCKED before any check stage, exit 1,
  `Working-tree overlays are not supported by the installed merge verifier`.
- Exact implemented source commit
  `404f37773bc24da2ccd27dd5c95b20e830c9cbd7`:
  `python tools\android-port\local\check.py --commit 404f37773 --stages python`
  returned exit 1 before Python checks:
  `BLOCKED: Malformed installed reservation runtime manifest`.
  This does not negate the trusted owned-path scope admission, and it is not
  permission to reinstall/repair runtime or bypass the verifier. Functional
  status remains UNVERIFIED; no native or Gradle execution occurred.

Normal branch publication is not performed by pushing superseded integration.
Before this change, the existing branch already contained unpublished old
WP-207/208/303 consumer work, including queue/runtime accounting and App factory
wiring, plus superseded local counter-test preparation. A normal candidate PR
from this ancestry would not be limited to the independent footer family.
Those paths are not reverted, rewritten, silently republished or changed here.
The new bounded four-path commit remains available through shared Git objects;
coordinator serialization/current-producer reconciliation is needed before
normal publication. This is a concrete publication/ancestry boundary, not an
authorization-prose request or a blocker to the implemented owned test.
Actual three-dot PR-diff inspection against the stated current main identifies
19 inherited App/runtime/queue consumer paths, including `AppContainer.kt`,
`ConnectionManager.kt` and `ChatSendQueueService.kt`, outside this new four-path
commit. The working tree is clean after implementation commit 404f37773.

Read-only publication follow-up confirms actual remote main remains exactly
5d970c8e95a184a1e299220a189f5e0f622cef42. The active App producer is existing
PR281 at published `014b7f45186942482a428d0075b1f4e663c1ce99`, not PR282.
No remote branch, stack metadata or closed Map PR is changed. There is no
newly merged main to remove the inherited producer overlap, and no hosted
verdict is borrowed from that producer for this footer method. The local
checker/runtime guard is not repaired or bypassed.
