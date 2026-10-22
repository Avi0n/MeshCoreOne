# Same-key map snapshot failure recovery

WP-501 / test-parity-engineer; existing issue #134 and dedicated owner/branch.
Implementation base: `4c33f4fe8f2b115ecec7aa74491b6cede94af0ec`.
Frozen read-only specification: `db14559b39d32322b06477c6ae676112f583db50`.

## Original and incumbent comparison

The existing semantic inventory at `parity/groups/WP-312.json` identifies
`MapSnapshotStoreTests::clearFailures() drops failed entries so the next request triggers a retry()`.
The complete frozen test file, including FakeRenderer, request defaults and
all eleven original methods, and the complete frozen store were read.
The selected original at lines181-203:

1. Fails a render of `(37, -122, dark=false, offline=false)` and consumes its
   resolution; the request is resolved despite having no image.
2. Calls `clearFailures`, consumes the separate invalidation and requires
   that the SAME request is no longer resolved.
3. Makes the renderer succeed, requests the same key again, consumes the
   retry resolution and requires an image and exactly two renderer calls.

All eight complete incumbent `core:maps/MapSnapshotStoreTest` methods were
compared. `failureRetryAndClearInvalidateResolvedState` uses per-key `retry`,
not `clearFailures`; its later `clear` assertion does not consume an
invalidation. All ten complete App `ChatMapPreviewTest` methods and their
fixture were also compared. The offline recovery test changes the offline
and dark request bits, so the new key can render even if the old failure
remains sticky. The privacy test checks failure removal, not the selected
original's consumed invalidation and same-key retry. There was no test call
to `clearFailures` anywhere in the current Android test tree.

This is a missing original predicate/consumer path, not evidence that the
existing implementation fails. No incumbent test, production implementation,
historical accounting, proposal or review-binding archive is changed.

## Executable consumer regression

`MapSnapshotFailureRecoveryOriginalPredicateTest.originalFailedKeyEmitsInvalidationAndRendersAgainThroughTheAppOnlineEdge`
uses the actual `AppChatMapPreviews` and its actual `MapSnapshotStore`.
The existing injected network-availability seam changes offline to online;
real `refreshNetwork` must call real `clearFailures`. Each resolution waiter
is subscribed before the triggering action, and the invalidation is consumed
before requesting the retry. No per-key retry, manual clear, key substitution,
fake stream or copied cache implementation can satisfy the method.

The original coordinate/request bits, fail-then-succeed renderer, resolved
state assertions, non-null image and two-render predicate remain. The native
image is an actual 1x1 red Bitmap instead of UIImage; no MapLibre/GL, tile
network or physical radio is invoked. Additional assertions require the
exact unchanged key on both renderer calls, no render caused by invalidation,
the actual cached image identity/pixel and terminal process-cache cleanup.
The injected test scheduler replaces ambient yields. Bounded event waits
fail if the real stream does not deliver; they do not synthesize resolutions.

## Verification boundary

The existing App test source set already includes `android/parity-tests`.
The declared relevant runner is `:app:testDebugUnitTest`; the focused
selector is `--tests com.meshcoreone.android.parity.MapSnapshotFailureRecoveryOriginalPredicateTest`.
This is one JUnit4/Robolectric API31 method, not an API37/device, native-map,
network-reachability, mounted Compose or full-WP acceptance claim.

Local declared readiness
`python tools\android-port\controller\ci.py preflight` returned
`BLOCKED: Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
No state was fabricated or native/Gradle/resource operation started.
Kotlin compilation/execution therefore requires the candidate's own normal
hosted checks; local source checks are not a substitute.
Exact committed check outcomes and hosted job identities belong in the PR,
not in a second copied CI-result bundle.

Issue #261 remains separately blocked; no correlation, runtime, queue,
Rooms502, completed persistence135 or scope/prefetch165 work is replayed.
