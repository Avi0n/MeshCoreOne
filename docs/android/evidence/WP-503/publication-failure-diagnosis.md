# Published f75: producer dependencies and incomplete failure identity retention

This is a source-based diagnosis, not a second CI result/log bundle, new
execution, or acceptance receipt. The authoritative
[run39300338554](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39300338554)
is **COMPLETED/FAILURE**, bound only to published
`f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab` (Android tree
`4dd87a44172c9c747eb7b77712db1d09e5517eb0`).
[Linux job118224232640](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39300338554/job/118224232640)
fails `:app:testDebugUnitTest`: **800 tests completed,4 failed**.
This is host/Robolectric execution, not800 Android-device starts. The
aggregate line alone does not establish pass/error/skip counts for every
selected scope or identify all four failing SDK-qualified cases.

## What the existing authoritative surfaces actually expose

| Exposed identity, class `com.meshcoreone.android.accessibility.NativeNodesAccessibilityTest` | Actual assertion | Classification from exact production and consumer |
|---|---|---|
|`offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner`|`OfflineNodesAudit.kt:96`: expected0 indeterminate progress nodes, actual1|Missing WP311 disconnected-cache loading repair, not evidence of an audit wait/clock defect.|
|`offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200`|`OfflineNodesAudit.kt:73`: `Reset Path` is not displayed after the original lazy-list and node scroll operations.|Missing WP311 wrapping path-action layout, not an assertion to remove or a vertical-scroll delay to add.|
|Remaining two failure occurrences|Not identifiable from retained hosted output or any published report.|**BLOCKED: exact identities, SDKs and causes unavailable.** Do not silently equate these with the two other configured SDK variants.|

The two exposed identities have **no SDK suffix** in this job's retained
output. The test class declares SDK31 and37, but configuration is not
proof of which of its variants failed. It is plausible that two methods
failed on both SDKs; it is not an observed four-identity result, so no
such attribution is made. Neither all four failures nor the entire800
may be classified from just these two method headings.

The missing detail has a concrete retention cause:

- At the exact published commit, `tools/android-port/controller/ci.py:105-113`
  writes Gradle output to the ephemeral runner's `gradle-scaffold.log`,
  then prints only its **last12000 characters** on nonzero exit.
  The retained job log begins that tail in the middle of a stack trace.
- Full existing job output, not just `--log-failed`, exposes only those
  two failure headings; it contains no SDK31/37-qualified names or
  `WP503_OFFLINE_DISCOVERY`/`WP503_OFFLINE_DETAIL_ACTION` metric lines.
- The run's existing artifact listing is empty. The workflow uploads
  only successful backup interop input, not failed App XML/HTML reports.
  The report URL printed by Gradle is a runner-local `file:` path, not
  a remotely readable published report.
- The existing check annotations contain only action-runtime deprecation
  and process-exit messages, not the missing test identities.

Complete four-case diagnosis requires the **original exact-f75
`android/app/build/test-results/testDebugUnitTest/TEST-*.xml` or full
`gradle-scaffold.log`** from the original hosted execution, if an authorized
holder retained it. No API available here exposes those discarded runner
files. Do not manufacture the missing identities from source declarations,
the interrupted local advisory run, or a fresh execution. No CI rerun,
new artifact upload, runner/workflow change, or alternate credential is
performed to fill this gap.

## Source diagnosis: disconnected discovery

The audit supplies a real cached radio ID and a nonnull deterministic
offline store, with `connectionState=DISCONNECTED` and
`connectedDevice=null`. Its `fetchDiscoveredNodes` returns an empty list;
it requires one fetch, zero indefinite progress nodes and visible localized
empty-state title/description. `compose.waitForIdle()` precedes inspection.

Publication `DiscoveryStateHolder.kt`
blob`6958f6d6a45f96d7ba449fdb2296c3d18ac92609` immediately returns from
`loadDiscoveredNodes()` when `session.connectedDevice()` is null.
It never uses the supplied cached radio or reaches the store. Default
`hasLoadedOnce=false` persists. The unchanged `DiscoveryScreen.kt`
blob`cb129b20c653cca4940fcce8ade8c474eb1b7d75` renders progress whenever
`!hasLoadedOnce || isLoading`. That explains the actual one-progress-node
assertion independently of a Robolectric timing hypothesis. Waiting longer,
removing the zero-progress assertion, or introducing a fake connected radio
would mask the disconnected-persistence requirement.

Existing producer PR233 exact
`fd9fa8e16c8a74043387010a192c3a9e36abd783` changes that holder to
blob`825b8815468c318c8c95717f379d85850223fe70`: connected radio falls back
to `currentRadioId()` for offline persistence; genuinely missing
prerequisites terminate loading with localized recovery. It is the
owner-produced dependency for this visible failure, not a WP503 product edit.

## Source diagnosis: path-action visibility

The audit scrolls the real lazy list to each action, then performs the
node's public scroll and requires it displayed, disabled, font200-unclipped,
with unchanged48dp predicates. The host profile sets actual resource
fontScale2, RTL and360x900 bounds.

Publication `ContactDetailScreen.kt`
blob`f81c30c403852299b58312af8118af2c17a83245` places Discover Path,
Edit Path and Reset Path in one nonwrapping `Row`. Vertical traversal
cannot recover horizontal overflow at that constrained, scaled profile.
Producer fd9 has blob`84b6da7bcdd5569446687669a067d20e9baf724f`,
replacing this action row with a wrapping `FlowRow`. The missing
owner-produced layout repair is the concrete source explanation consistent
with the actual not-displayed failure. This hosted tail does not preserve
pixel bounds, so it is not a new measured clipping dimension or proof of
the corrected producer's host result.

Both exposed stacks also contain a suppressed Robolectric
`UnExecutedRunnablesException`. That generic teardown diagnostic is
retained as uncertainty, not elevated to a causal harness bug over the
primary assertions and deterministic source paths. No shared helper,
dispatcher, font/RTL profile, predicate, timeout or production code changes
are justified by the retained evidence.

## Exact dependency, ownership and stop

At inspection PR233 is **OPEN/draft**, exact fd9, `mergedAt=null`;
main remains`8702db8b1b25a06eb596fb424d821bc3ce3b187b`.
[Source review5576016559](https://github.com/cbattlegear/MeshCoreOne-Android/pull/233#pullrequestreview-5576016559)
is `COMMENTED`, bound to fd9, not merge approval or proof the dependency
has landed. The publication intentionally restored both producer paths to
base bytes; [publication-scope.json](publication-scope.json) retains that
separation. **WP503 is blocked on the existing WP311 producer's ordinary
merge/current-base integration for these two visible failures**, plus
original-report availability for the other two identities. This is an
implementation dependency found by the audit, not an invented manifest
prerequisite or permission to re-import other-owner work.

The retained original Nodes7 API31 pass at a0/fd9 proves neither this
publication nor these two methods: `NativeNodesFlowAccessibilityTest`
contains seven different method identities. These two live separately in
`NativeOfflineNodesAccessibilityTest`. No seven-case success is transferred
to an unexecuted two-case successor or any host run.

All audit assertions and exact native source/fixture/APK/result records
remain unchanged. No device work, successful-cohort replay, producer import,
test exclusion, duplicate CI, new session, CI-result bundle or standalone/
whole-WP acceptance occurs. API37 remains **BLOCKED_BEFORE_LAUNCH with0
new starts**; the user's separately started endpoint is unconfirmed and is
not inspected, authorized, or used by this diagnosis.
