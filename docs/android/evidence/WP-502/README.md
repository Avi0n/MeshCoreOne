# WP-502 Compose flow and rendering evidence

**Implementation in progress; full WP-502 acceptance is not claimed.**
The initial docs-only candidate is superseded by owned Kotlin assertions and
capability-admitted App test source registration. Missing formal verification
configuration or device observations are acceptance prerequisites, not evidence
that the installed Linux implementation lane is unavailable.

## Identity and ownership

Repository: `cbattlegear/MeshCoreOne-Android`; issue [#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135);
[PR #206](https://github.com/cbattlegear/MeshCoreOne-Android/pull/206) is merged;
remaining software work continues in
[PR #244](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244).
Owner: `test-parity-engineer`, canonical project/reservation session
`3bc8c1b8-20ec-4647-88ae-db3775268489`; runtime alias
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Branch: `cbattlegear-issue-135-wp-502-compose-flows-and-deterministic-534de7`.

Base: `d958471ab4835813881a5e7af36e8249a8b273a4`.
Immutable reference: `db14559b39d32322b06477c6ae676112f583db50`.
Original manifest: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
Deployed versioned manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
the WP-003 amendment preserves the exact inverse/original receipt lineage.
Original reservation policy binding: `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
Current integrated base: `9c430cb0f1666faacc443794899b68c01128a87a`.
At the prior integrated base `574d440b5b05ba6609f60d7ba0913c2d8bdaea0d`,
the trusted policy revision was
`c1e87cc1e69400e25d035b40349ec62cf3bd14143ce0bb17932e04d46e9dadb0`.
The original binding is audit history, not rewritten to the newer policy.
Current policy/controller bytes are unchanged authoritative main9c430cb0 bytes.
The existing typed reservation was evolved to revision 22, not replaced.
`android/app/build.gradle.kts` is admitted through
`dependency-resolution-metadata` / `update-directly-required-build-wiring`.
The shared ledger remains authoritative; no copied receipt is committed.
Revision 4 admits the coordinator-assigned #215 app send assembly at
`AppChatsFeature.kt` / `add-direct-support-wiring`; revision 5 admits integration
of published composer producer `b644c9a1d2522ae1519d9359e21b37988dbead68`.
That three-file correction is integrated as `2518046f5`; unrelated unintegrated
contrast claims were not imported during the disjoint documentation merge.
Revision 6 admits the existing producer's native keyboard diagnostics; revision 7
admits its published queue-shaped API/failure callback and associated tests.
The source owner remains WP-308. Integration of `a423da64e`, `6eb6f7b8c`,
and `a1c9c88da` is `710baeea0`; the authoritative App-only consumer is
`0ae51f7e1ca9c8a4c7aa962a0dfadae59923bf8e`, actually published on PR #206.

WP-407 is the only dependency. Its PR #164 merge
`250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, merged
`2026-10-11T16:22:30Z`, is an ancestor of this base.
WP-501's parity/accounting/binding surfaces are not changed or duplicated.
Disjoint WP-501 test-only and WP-504 release/R8 build additions must survive
coordinator integration.

## Implemented assertions and input families

| Owned surface | Assertions and provenance |
| --- | --- |
| `ConversationScreenCases` | Production `ChatTimeline`/`ComposerBar`, fixed synthetic identities/time, original CJK/Hebrew/ZWJ text; focus/draft retention, accessible retry, offline send rejection, exact UTF-8 boundary, distinct loading/empty/error recovery and 48dp send target. Mounted Enter/NumPadEnter with/without Shift exercises 24 connection/overflow/blank/cooldown/sending/enabled families at controlled 200% font. Consumer assertions informed by frozen `ChatInputBar.swift` and `ChatConversationMessagesContent.swift`; not a claim to port every Swift test. |
| `ConnectedConversationComposeTest` | Actual AppContainer/Room/service graph and existing protocol-speaking firmware harness. Reference `RESP_CODE_ERR=1`, direct `ErrorCode.notFound=2` and channel terminal `ErrorCode.illegalArgument=6` reject real sends. Separate mounted direct/channel cases click Retry and require the same saved message, original text/FAILED persistence, visible failure/recovery after both attempts, one terminal event per attempt, durable envelope metadata, queue cleanup, consumed draft and one connection owner. Channel notFound is intentionally retryable and is not substituted for the terminal reference error. New assertions require their own positive execution before credit. |
| `DraftInitializationComposeTest` | Two actual App/Room/service-queue/NativeNavigationShell mounted witnesses with independently divergent saved `"disk"` and visible `"in-progress"`/`"quick-reply"` inputs. Require visible Sending before actual terminal ERR2/refetch, retained focused field identity/entry/generation through seven adaptive widths at200% font, independent saved value and radio/contact isolation, and actual other-target initialization. Both pass at13de7b933 and actually execute within exact publishedccd App766/766. Primary WP-307 supports the bounded four-original no-notification native adaptation; not a privileged gate/full-WP verdict. |
| `OnboardingWiFiComposeTest` | Actual production `OnboardingFlow` and coordinators/shared modal fields with controlled dependency ports. Mounted nine frozen host families, seven port boundaries, comma normalization/clear and keyboard submit; held attempt blocks duplicate IME and toolbar cancellation, failed CJK/RTL error retains inputs and Retry alone reaches Region; benign cancelled outcome stays on sheet, cancel/reopen restores empty host/default port. Denied optional permissions do not gate these paths. Three sampled scheme/locale/window/font cases, actual modal title density/direction asserted. Not socket/OS-permission/radio/device or full onboarding acceptance. |
| `OnboardingPairingComposeTest` | Four mounted production Flow/coordinator methods: external/local busy guards and exactly one accepted Region entry; held other-app failure, dismissed alert and repeat failure preserve the exact Retry UUID; benign Cancelled/Ignored remain reusable without error/advance/completion; two title taps do nothing, third unlocks demo once without connecting and demo primary takes priority over the retained Retry target. Controlled pairing/demo ports, not OS chooser/BLE/radio/native-device evidence. |
| `QueuedComposerIntegrationTest` | Real Room/app/service pending direct/channel, retry and Send Again queue ownership; terminal event count/cleanup, real enqueue transaction rejection and typed provider failure, unless-delivered failure/visible-thread seen state, observer cancellation and replacement-generation hydration. Newly defined assertions require positive execution before being credited. |
| `ShortcutQueueIntegrationTest` | Real direct/channel durable acceptance, typed creation/enqueue/cleanup failure stages, cancellation provenance and generation replacement, SYNCING acceptance/drain, and actual file-store close/reopen. The resumed reopen case reconnects a fresh AppContainer to the same radio, requires both durable rows to drain through the real queue, exactly one wire command per message, terminal persistence/cleanup and no duplicate messages. The session-error conversion/presentation case separately injects synthetic port failures: six frozen MeshCore error families, both actual service-wrapper types and two locales require exact underlying identity, outer cause and localized presenter text, without persistence or wire sends. Its assertions are not evidence that the durable queue emitted those synthetic faults. |
| `CoverageMatrix` | Independently specified Ember forced-dark rule, all ten unlocked themes/19 effective frames, 20 SYSTEM requests. 57 theme/window/font/direction rows cover each effective frame at 360/744/834dp and both 100%/200% font scales/directions. 48 state rows cover all 176 pairs across 12 locales, four delivery/connection states, three widths, two fonts and two directions. Exact pair-set assertions reject omissions. |
| `LocalContentCases` | Self-authored schematic raster and cached metadata, not external map/terrain data: production inline-image failure reporting/retry/viewer, manual/loaded/malware preview actions, offline map loading/failure/retry and coordinate callback independence. Injectable local sources never fetch the network. |
| `ConversationComposeTest` | Native Robolectric API31 execution of the production-content flows and matrix; expected 127 distinct rendering inputs including local image/map content, two identical settled draws per capture, nonblank pixels and unclipped visible message text. Native host evidence is not emulator or approved golden evidence. |
| `RoomsComposeTest` | Production `RoomConversationRoute`/`RoomConversationStateHolder` with controlled service/data ports: held loading versus empty, guest/member/admin/disconnected transitions, focused draft across three widths, held/rejected post and exact-ID accessible Retry, held/missing contact and rejected/successful authentication with exact password/remember inputs. Separately renders production content in 57 effective-theme/window/font rows with 12 sampled locales and all nine width/permission-surface pairs. Not actual RoomServerService, Room-database persistence, radio or approved-golden evidence. |
| `ConversationDeviceTest` | Actual API31/37 wrappers, real current window rather than a pretend expanded phone, system-bar accounting, hardware-window PixelCopy, every effective frame at both font scales plus SYSTEM requests. Three earlier methods executed on API31; newer wrappers and hardware-key families require their own exact-binary observation. |
| `PlatformApiBoundaryDeviceTest` | Actual API33/34/36-only wrappers: denied notification remains advisory, pre37 LAN independence, connectedDevice manifest/normal permission, API36 public CDM removal availability. Queries only; never remove a bond, start a radio service or reconfigure a radio. Defined, not yet credited as executed. |

The matrix is deliberately layered, not the full Cartesian product and not an
assertion of all theme/locale combinations. Local image/map fixtures passed
native-host assertions at `38dea8f910`; broader feature flows, folding observations
and a spoken TalkBack audit remain gaps.
Existing prerequisite tests are prior art; newly defined consumer cases do not
replace WP-501's original-case audit.

Rendering uses no approved golden. Repeatability is an assertion about two
settled native draws, not visual parity with iOS or an approved baseline.
RTL shrink-to-fit paragraph allocation can exceed the measured text width
without clipping a visible glyph; assertions check visible glyph bounds,
height overflow and ellipsis directly, without geometric tolerance.

## Executed commands and observations

### Bounded production pairing primary/recovery slice

Same reservation22 admits only owned `OnboardingPairingComposeTest`; the
Rooms, adaptive, draft and WiFi test bytes are unchanged from publishede2d.
It mounts actual `OnboardingFlow` from Welcome through Permissions into Pair.
The real flow owns `DeviceScanCoordinator`, its busy/error/Retry/demo state
and Region navigation. Controlled pairing/demo calls suspend on explicit
deferred outcomes; no fixture completes a request by default.

| Frozen source / existing owner prior art | Added mounted predicate and limit |
| --- | --- |
| `DeviceScanView.primaryCTA`, `startPairing`, `retryConnection`; owner `pairingSuccessAdvancesAndMarksInitiated` | External busy prevents even the first pairing call. The real primary starts one held call, remains disabled through repeated clicks and external busy recomposition, and cannot advance until explicit Connected. Actual flow Back from Region immediately returns to Pair, proving one Region push rather than duplicate success navigation. Private haptic/initiation fields are not introspected. |
| `DeviceScanView` other-app warning observer, `retryConnection`; owner `otherAppFailureOffersRetryThenRetrySucceeds` | Held initial failure with a known Retry UUID displays exact CJK/RTL text. Dismissal keeps Retry. Held Retry failure with no new UUID still retains that original target; the next Retry sends the same UUID. Only explicit success reaches Region once; no extra fresh pair is substituted. The injected outcome is already mapped, not a BLE-error/ConnectionUIState routing test. |
| `AppState+DeviceActions.withPairingFlowErrorHandling`: `DevicePairingError.cancelled` and `alreadyInProgress`; owner `userCancelIsBenignAndDoesNotAdvance` | Actual native `Cancelled` and `Ignored` outcomes each release busy, leave Pair/Add reusable and produce no alert, Region or completion. A later intentional accepted attempt works. These are benign declared results, not coroutine cancellation, actual picker cancellation or all picker-unavailable/foreground recovery cases. |
| `DeviceScanView.primaryCTA`, `unlockDemoMode`, `connectSimulator`; owner `primaryActionPrefersDemoThenOtherAppThenAdd`, `threeTitleTapsUnlockDemoMode`, `demoConnectAdvancesToRegion` | Add is initially selected; other-app failure exposes Retry. Two real title taps do not unlock or connect; the third exposes the localized demo alert, unlocks once and still makes no new attempt. After dismissal Demo replaces Retry/Add, its one held demo call remains guarded, and explicit success reaches Region. No actual demo transport or simulator-only iOS branch is certified. |

All methods require no onboarding completion callback/flag or unrelated WiFi,
settings, preset, location, stale-pair clearing or permission-request effects.
Bluetooth is a granted controlled prerequisite; location/notification/LAN
snapshots remain denied. The other-app case uses German/834dp, the others
English/360dp, all at200% font with actual Pair heading density/height checks,
1400dp host height and the existing reduced-motion setup. This is native-host
layout/flow evidence, not OS font/device/keyboard/TalkBack or physical resize.
The test Back seam invokes the actual flow callback, not Android system Back.

At code `5794a96e71144908c5ff6e2c0c234ea3a9a3f5be`, installed-lane exact
preflight **PASS0.56s** and the existing strict selector:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingPairingComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**4 discovered/run/passed, zero failure/error/skip,37s build**, from the
actual four named JUnit XML cases. First execution passes; no failing product
predicate, foreign fix, weakened assertion or new task is hidden.
Portmap/controller validation, `git diff --check` and exact previous-test/
main9c owner-support comparisons all pass after the source/evidence additions.
Previous successful WiFi/Rooms/draft/adaptive cohorts are not manually replayed
or added into a manufactured54-case execution.

The five-case frozen `FreshPairingFailureRoutingTests` and two
`MacOSPairingRecoveryTests` were read but are **not** claimed covered by these
mapped-outcome UI consumers: PIN/dead-bond classification, ASK/CDM registry,
foreground prompt/removal/scanner recovery and transport wiring remain their
actual primary owners' responsibility. Full pairing/source-family and full
WP-502 acceptance remain unfinished.
Prior e2d [COMMENTED source review5589729220](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244#pullrequestreview-5589729220)
finds no bounded WiFi blocker, but does not review/certify this pairing successor;
its hosted39474101494 was still in progress when this delta was recorded.
No CI verdict, goldens or device observations are transferred or manufactured.

### Bounded production onboarding WiFi flow

Same reservation21 admits only owned `OnboardingWiFiComposeTest`; existing
Rooms/adaptive/draft test bytes are preserved exactly. It mounts the actual
`OnboardingFlow` from Welcome through Permissions/Pair into its actual modal
sheet, not a copied test coordinator/policy. Controlled ports hold every WiFi
outcome until explicitly released; unrelated Bluetooth/demo/region-location/
preset/settings effects fail the test. Location/notifications/Bluetooth/LAN
snapshots are denied, with no permission request, yet WiFi remains usable.
This is deterministic consumer behavior, not a real socket or OS permission.

Frozen `WiFiHostValidationTests.swift::classifies host` supplies all nine
independent host/expected pairs. Actual field/button semantics exercise all
nine, plus seven port boundaries; no port call occurs during validation.
Invalid70000 IME submit shows the actual localized error without connecting.
Comma replacement, accessible clear and host-Next/port-Done actions reach the
actual coordinator; whitespace is removed before the exact port input.

The held-call case asserts disabled Connect/toolbar Cancel and no duplicate
request through actual port IME. Explicit failure displays a polite CJK/RTL
error, retains address/port and stays on the sheet. Only a second, explicitly
accepted attempt closes it and displays the real Region screen, without
marking all onboarding complete. A separate benign `Cancelled` outcome
does not advance or publish error; Cancel/reopen resets only sheet inputs and
does not issue another connection. Coroutine cancellation and interactive
swipe/system-Back dismissal are not established by that outcome test.

Three bounded cases reuse existing `CoverageMatrix` frames: default/light
English at360dp/200%, forced-dark Ember Chinese/RTL at834dp/200%, and
default/dark German at360dp/100%, host height1400dp. Actual modal heading
font density, layout direction, no vertical overflow and no ellipsis are
asserted. Locale qualifiers configure the real modal host, not just a parent
CompositionLocal. These are sampled native-host flows, not a new full theme
matrix, screenshots, physical window/IME or approved goldens.

At code `2b10dd966e43a46a4e4dec28c857ca8d05d139eb`, exact installed-lane
preflight **PASS0.45s**, followed by the existing strict selector:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingWiFiComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**3 discovered/run/passed, zero failure/error/skip,26s build**, checked from
actual named JUnit XML. Failure history is retained:145fdd62f discovers3/
fails3 in3m29s because the real Welcome animation does not idle without the
existing reduced-motion setup;97678e298 discovers3/fails3 in25s on modal
visibility/focus assumptions;7a2a39877 passes1/fails2 in26s because the modal
host is still English while test-local resources expect Chinese/German.
cbcd787ae passes3/3 in26s after actual host locale configuration.
The strengthened8ada39588 discovers3/fails3 in25s because the heading query
also matches the underlying launcher label. Final2b10 selects the actual
Heading semantics and passes actual font/direction assertions; no product,
timing threshold, expected validation or owner policy is changed.
`python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py`, `git diff --check` and
exact preserved-test/owner-support byte comparisons all pass after these
owned source/evidence additions.

The exact prior publishedccd
[run39465426509](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39465426509)
actually passes all six jobs. Its authoritative
[Linux job118776483387](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39465426509/job/118776483387)
reports App766/766, zero failure/error/skip, including both mounted draft
method identities. That is ccd/base9c evidence, not a successor pass or a
separate combined47/50-case execution. No successful previous cohort/device
is manually replayed and no second CI-result bundle is retained.

### Mounted single-initialization draft followthrough

The same reservation20 admits the owned `DraftInitializationComposeTest`.
At code `13de7b9336b9c26996efc8631c51d8190a10d992`, exact installed-lane
preflight passes0.63s; strict existing `:app:testDebugUnitTest --tests
com.meshcoreone.android.app.platform.DraftInitializationComposeTest` with
the recorded strict options actually discovers/runs/passes **2**, zero
failures/errors/skips,34s. The preceding835 source passes2/2 in43s;
13de strengthens the actual visible Sending-before-Failed reload and durable
envelope assertions. There is no nominal new restore method/policy composable,
foreign composer edit, invented clobber or wait for archived WP-308.

[Exact original-case mapping, consumer lifetime, commands and limits](draft-initialization.md)
reuses existing empty-policy/constructor cases rather than adding duplicates.
The actual remembered production field retains a value different from its
saved DraftStore through real service terminal-event/store-refetch and
adaptive recomposition. Controlled protocol firmware is explicit; the
App/Room/queue are real. Draft defaults are an injected memory dictionary,
not physical disk. The primary WP-307
[assessment6297011991](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#issuecomment-6297011991)
now supports these four concrete no-notification originals as a bounded
fresh-owner/retained-owner native adaptation, with no remaining source-case
blocker or justified foreign composer change. Parent source-only
COMMENTED review5589003799 bindsccd, not a successor or privileged gate.
Reconnect/arbitrary teardown/process/physical preferences, general empty-field
external-store updates and notification priority/consume-once remain distinct
gaps; existing WP-307 owns its pending-notification consumer repair.
No full-WP acceptance or prior cohort/device/golden replay is claimed.

### Integration of deployed software verification

PR246 actually merged as authoritative main
`9c430cb0f1666faacc443794899b68c01128a87a`. Same-owner reservation19 admits
its fifteen changed paths through `merge-integration/reconcile-current-base`;
integration commit `4f8d53e080631221c3825618052c90613fd71698` preserves those
owner bytes/attributions exactly. All Android bytes, including both Rooms and
adaptive tests, are identical to published predecessor759. No producer-local
cohort, device observation or manual hosted run is replayed for this integration.

The deployed WP-003 software-verification amendment now declares the existing
controller `preflight`, `run --stage scaffold` and `inspect` commands for WP-502.
Full `configured:false` is intentionally retained for unfinished feature/original
families, candidate-device observations, approved goldens and physical/human
acceptance. It no longer says that Gradle/Android infrastructure is absent.
This is an inherited exact-inverse manifest amendment, not a new WP-502 policy
or catalog edit and not a dependency/acceptance unlock.

Local Rooms4/57 results remain bound to code160/base0183. Hosted759 results
remain bound to their own head/base; no pass or failure is transferred here.
Bounded [source-only review5588526416](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244#pullrequestreview-5588526416)
is COMMENTED at759, not a successor review, gate verdict or approval.
Published integration48b is actually projected by PR244 at base9c after the
ordinary non-force push. Its automatically created exact hosted
[39463187912](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39463187912)
was in progress at this update, not certification of this draft successor.
Historical759 run39460593770 completed CANCELLED: Linux Android and SwiftData
were cancelled and final guard failed, while classification/controller/codec
passed. This does not turn the cancelled suites into executed test failures
or erase local Rooms4/57; no duplicate dispatch or result bundle is made.

### Bounded Rooms production slice in PR244

The same producer/reservation18 adds only an owned native-host test file,
preserving the previously reviewed adaptive regressions byte-for-byte.
It uses merged WP-310 production route/state-holder/content and contracts, with
WP-307's existing room accessibility fixture as prior art, not as transferred
test acceptance. Frozen `RoomConversationView.swift` supplies disconnected,
read-only and posting branches; `RoomAuthenticationSheet.swift` supplies
lookup/loading/missing-contact branches. The underlying room label/status tests
remain owned by WP-307. Translation remains removed from active scope.

The actual existing strict selector is:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.RoomsComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

At code commit `160ba7d3a4018278081c001319b8a6235859c689`, exact
`python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit 160ba7d3a4018278081c001319b8a6235859c689 --stages preflight`
passes. The selector actually executes **4 discovered/run/passed, zero
failures/errors/skips, 33s**, through the installed serialized Git-bundle/ext4
lane. Its JUnit XML contains **57 distinct successful `WP502_RENDER` records**.
Each capture asserts equal settled native draws, nonblank pixels, measured
width/height, expected effective scheme, actual font-scaled body layouts,
unclipped visible glyphs and localized state/action labels. PNGs are generated
under `android/screenshot-tests/build/screens/sdk31/rooms/`, not committed
goldens or an additional hosted CI bundle.

The route tests assert real state-holder transitions, callback arguments and
typed `RoomServerError.PermissionDenied` identity/diagnostics. Failed posting
retains the draft and publishes no message; successful fixture acceptance
clears it once, and a service event/reload exposes FAILED and invokes Retry on
the same session/message ID. Lookup/join gates cannot return fake success;
missing contact has no password field, rejected join keeps the sheet/password/
remember choice, and only accepted join dismisses it. These are explicitly
controlled service-port results, not real firmware/service/store acceptance.

Failure history is retained: initial `a7f68d188` passes preflight but fails
App test compilation in35s by treating `CustomActions` as a callable semantics
action; no tests are credited. `fbc5f97cc` actually discovers4, passes2/fails2
in34s: the text query matches both the message and retained draft, and a
Robolectric main-looper debounce has not advanced. The corrected selector
excludes editable fields, reads/invokes the real custom-action list, and drives
the existing 50ms reload through the host looper. No production timing,
assertion threshold, queue policy or owner implementation changes.
Portmap/controller validation and `git diff --check` pass.

The original 127 conversation/local-content inputs and prior41 App executions
are not rerun or relabeled as current Rooms evidence. No new device/native
instrumentation execution, physical folding, spoken TalkBack or approved
goldens are claimed. Full Rooms server sync/history, ordering, links/actions
and persistence/radio integration remain outside this bounded slice; broader
feature-matrix/source-family and full WP-502 acceptance remain open.
At this historical759 execution/publication, WP-003's amendment was not yet
deployed. Its subsequent authoritative integration is recorded above, without
turning the Rooms results into whole-WP acceptance.

### Remaining software coverage after merged PR206

Broader issue135 remains open after bounded215/217 closure. The same producer
and reservation17 integrate authoritative main0183a2b206b9ba655d7fea5c281b8bfbfecf8847,
preserving other owners' merged timeline-restoration, node, appearance, CLI and
verification support. Historical source458 native observations remain intact;
none is relabeled as a new device run.

Two owned `ConnectedConversationComposeTest` methods now exercise actual
AppContainer/Room/session/NativeNavigationShell direct and channel conversations
after real rejected send and mounted Retry. Available-width transitions
360/599/600/779/780/834/360 cross the native rail and list-detail boundaries at
200% font. Assertions require focused CJK/RTL draft, actual text layout scaling,
unchanged persisted failed message and draft, selected navigation-entry identity,
textual FAILED/Retry semantics and one connection generation. Switching to
Settings and back must restore the actual conversation without an extra
enqueue, message, terminal event or wire send. This is deterministic native-host
layout evidence, not physical resize/folding or spoken TalkBack.

The existing declared `:app:testDebugUnitTest` selector for this class now
requires4 actual JUnit executions (two incumbent terminal-send/Retry cases,
two new adaptive cases); the existing five-selector App cohort requires41.
At initial64a343ed9 the test compile fails46s because the text-replacement
action returns Unit and cannot chain a focus assertion; no tests are credited.
Corrected `be383a28cf1f45b229f112bf8d1d602b4842f9f8` separates those actions:
exact supported preflight passes; class-only execution passes4/4 (38s) and the
existing full five-selector cohort passes41/41 (1m7s), zero failures/errors/skips.
Both are new positive execution, not historical206 or source-method counts.
Portmap/controller validation passes. Exact commands, method/family mapping,
device selectors and honest unfinished rows are in
[software-verification.md](software-verification.md). The supported formal
verification-config amendment and semantic command/inventory mapping are
requested from the existing WP-003 producer via
[issue135 comment6293815386](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135#issuecomment-6293815386).
That historical request is fulfilled by the deployed WP-003 amendment above;
it does not authorize invented tasks or whole-WP acceptance.

### Follow-up: actual advert consumer error mapping

Frozen `MC1/Intents/SendAdvertIntent.swift::mapToIntentError` preserves typed
AdvertisementError.NotConnected, wrapped session errors and raw MeshCore errors.
The real native AdvertisementService wraps MeshCoreException, but the App
advert branch still flattened its wrapper after the message-only correction.
At test-only `34f801b2a5807558b1f0217930d78199874c15bb`, after integrating
actual live main `8702db8b1b25a06eb596fb424d821bc3ce3b187b`, installed preflight
passes. The existing declared `:app:testDebugUnitTest` with the two selectors
`ShortcutQueueIntegrationTest.realAdvertServiceSessionErrorsReachAdapterAndExecutorForZeroHopAndFlood`
and `ShortcutQueueIntegrationTest.realAdvertBoundaryPreservesTypedNotConnectedFallbackAndCancellationForBothReaches`
(fully qualified under `com.meshcoreone.android.app.container`) discovers two
and fails both (1m21s), zero passes. The actual assertions report expected
NOT_CONNECTED versus ADVERT_FAILED and expected SessionError versus ADVERT_FAILED.
Those failures remain failures, not old hosted/device evidence.

The admitted App correction preserves the exact wrapped/raw session payload
and outer cause, exhaustive typed advert categories, existing ShortcutException,
generic fallback and cancellation. Actual independent firmware ERR bytes1/6
exercise real MeshCoreSession/AdvertisementService through AppShortcutRadio and
the executor for both reach values, then OK bytes0 prove ordinary recovery.
Typed/fallback/cancellation faults are injected at the existing transport-write
seam through that same service path, with a fresh generation per uncertain
write rather than suppressing correlation policy. The raw conversion case is
explicitly separate because this service normally wraps MeshCoreException.
No premapped FakeRadio error substitutes for these boundary tests.

At corrected-production `755e7d82f8d6665606d262cc5c06dad4b2ed3986`, preflight
passes and the full selected App task discovers39:38 pass/one fails (1m7s).
Session payload/executor recovery and raw conversion pass. The boundary fault
case fails `Collection contains more than one matching element` when its repeated
fixture connections create a new synthetic channel UUID at the same index in
the retained process store. The fixture correction reuses an existing index0
channel instead of inserting another one; exact single-row/no-message assertions
remain and production persistence/correlation policy is unchanged. The ordinary
push succeeded; its separate advisory exceeded180s/UNVERIFIED after also observing
this failure, not a pass. The failed candidate is historical, not a green successor.
At `50585778ef77a9de66f876e80b3c3268809d0bef`, preflight passes and39
selected cases are38 pass/one fail (1m13s). Actual coroutine stack recovery copies
the injected generic IllegalStateException, keeping its exact original cause.
The correction asserts direct identity or that exact original recovery cause,
and unchanged runtime type/message; the actual App-received outer failure is
still preserved. Typed advert/session payload and cancellation semantics are
not replaced by matching text, and neither failure is relabeled as passed.
At `315085ae9`, preflight passes but the App unit-test compile rejects generic
`assertEquals` inference between exception Class bounds (20s); no App test
discovery/pass is credited. Direct Class equality fixes only that assertion's
type inference, without casts or changed expectations.

At `29ef8bc25d0517805a9c46de3aa99e81233c60ef`, exact preflight passes.
The declared five-selector App cohort newly executes39/39 passing, including16
shortcut cases and all three advert methods, zero failures/errors/skips (1m7s).
The combined owner-module task is UP-TO-DATE with retained33/33 reports, not
freshly executed in that invocation. Portmap/controller/diff validation passes.
Actual commands and results remain in PR206; no new device observation,
approved golden, hardware pass or whole-WP acceptance is claimed.

Authoritative [exact29 hosted run39307276156](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39307276156)
finishes FAILURE: Linux Android118248447748, controller118247806580 and Swift
codec118248447869 pass; mandatory SwiftData118260603856 is CANCELLED without
acquiring a runner (runner_id0, no steps). Its actual annotation reports
"The job was not acquired by Runner of type hosted even after multiple attempts"
and macOS-arm64 capacity constraints. Finalandroid-ci118267809015 correctly
fails closed on BACKUP_RESULT=abandoned. No SwiftData tests ran or failed.
This is missing mandatory platform evidence, not a source-regression claim.
The hosted job/log remains authoritative; no second CI-result bundle is retained.

After merged PR242 advanced main, same-owner reservation16 admits
reconcile-current-base for its15 authoritative paths. Integration
`193f5be8ea0eda1fc0441c4385aa2b757d6cb4d1` preserves actual main
`574d440b5b05ba6609f60d7ba0913c2d8bdaea0d` and its WP-003 producer attribution;
all Android bytes remain identical to29. No already-running exact successor
existed when recovery began. Source feedback at reviews5576266699/5576353165
found no bounded advert blocker, but is COMMENTED/scoped to755/29 and is not
transferred as an exact-current-base gate. Current successor verification
remains required; no successful native/Android cohort is manually replayed
solely to manufacture evidence. Historical458/62 native results stay historical.

At published `62b601c58472ce9fbe150f8ee2340c723866d234`, the declared five-selector
App cohort passes 35/35, zero failures/errors/skips (2m12s), with preflight,
portmap and controller validation passing. Authoritative
[exact hosted run39197404487](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39197404487)
passes all six jobs; its actual log, not a second CI-result bundle, remains
authoritative. Separate declared target/instrumentation assembly passes24s.
Fresh matching-current APK observations pass12/12 on API31 (113.541s) and
full37.2 (167.426s), plus2/2 on33/34/36 (0.055s/0.485s/0.100s).
31/37 observe OS font2/no default network and retain60 PNGs each;33/34/36
observe noGMS/4KiB/no default network. Source/APK-bound native observations,
logs and captures remain private session artifacts, with exact commands and
hashes in PR206. These are native emulator observations, not physical-radio,
folding, approved-golden or spoken-TalkBack certification.

The existing WP-404 producer published the primary shared error-contract repair
at `2a0aa08afb4d84b1e82b6ba52d0b90c9caab9730`, based on `d81814186`.
All eight files are integrated byte-identically, with original ownership and
commit attribution retained. Reservation14 admits that exact disjoint
integration, not original WP-502 editing of shared shortcut model/text/ports.
Its `ShortcutError.SessionError(underlying: MeshCoreException)` contract is
consumer-confirmed. The admitted App mapping unwraps actual
`MessageServiceError.SessionError.underlying` and
`ChannelServiceError.SessionError.error`, retaining the outer exception as cause.
The separate conversion/presentation regression expects the pinned
`IntentError.swift` and `MeshCoreError+UserFacingMessage.swift` resource mappings:
timeout, not-connected, Bluetooth unauthorized, CJK/RTL invalid-input detail,
nested timeout connection loss and unknown device code255. Each is checked with
both service wrappers and English/Simplified-Chinese contexts (24 combinations).
The mapper is internal so the conversion contract can be asserted without
pretending the real queue produced these injected faults. This new preparation
requires its **own combined-candidate execution**; the 35-case62 result and62
native runs above do not apply to its changed production bytes. The owner's
reported 33-case module result is not substituted for that combined execution.

At `cabbde6ad003eb3f830ca269b0942de6e961945e`, the declared App unit-test task
with the five selectors below discovered and passed 33 cases, zero failures,
errors or skips (1m15s). This includes both mounted direct/channel initial-send
and actual Retry interactions, and real file-store reopen followed by fresh
AppContainer reconnect/drain. The authoritative exact-head
[hosted run 38409696559](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38409696559)
passes all six jobs; it is not current-successor or device evidence.
No duplicate CI-result bundle is retained.

The resumed shortcut assertions add held-confirmation decline/cancellation
checks for both recipient kinds: before and after refusal/cancellation there
must be no message, PendingSend, optimistic/terminal event or wire send.
Accepted-send cancellation/generation replacement is now defined for channel
as well as direct messages, retaining the exact cancellation or recovery cause,
one message and one terminal queue attempt after reconnect. These new assertions
require their own positive execution; cabb's 33-case result is not their pass.
The first new 12-case run at `61258e41fc2934a3999048c3bcb2a97104f9c74b`
discovers 12: ten pass, two fail (1m18s). Both cancellation cases incorrectly
expect one wire command total despite a legitimate unacknowledged first-generation
attempt followed by reconnect recovery. The correction explicitly waits for that
first attempt, asserts its persisted attempt count, and requires one command per
generation plus one terminal event/message; it does not suppress durable retry.
The full `f902926d0ef2984a86eead5aca474a7c665b904d` cohort discovers 35:
34 pass, one mounted channel test fails (1m37s), while all 12 shortcut cases pass.
That fixture compared the captured enqueue timestamp to the later resend-updated
message row; a one-second boundary exposed the invalid comparison. Frozen
`MessageService+SendChannel.swift::resendChannelMessage` deliberately updates the
row timestamp before a resend. The corrected assertion captures the real row
timestamp before clicking Retry and compares that exact value to the envelope,
without tolerance, clock-dependent equality or a product change.
The native session-error category/payload remains blocked by the existing
WP-404 ownership/admission gap; these tests do not resolve that category.

The fault-boundary candidate `66102ee7fd382c91be6cfcdd5bc9e14f86b66b35`
runs the declared `:app:testDebugUnitTest` with only
`com.meshcoreone.android.app.container.ShortcutQueueIntegrationTest`:
**ten discovered, nine passed, one failed, zero errors/skips** (35s).
Both direct/channel pending INSERT failures already return a typed shortcut
error without persisting a message. The new simultaneous `pending_sends` INSERT
and message `statusRawValue` UPDATE rejection case instead observes a cleanup
`PersistenceStoreException` as the shortcut cause, replacing the original
`ChatSendQueueServiceException.PersistFailed`. This is an actual provenance
failure, not successful cleanup. The owned App adapter now logs that narrow
typed cleanup failure while retaining the original enqueue error and leaving
cancellation untouched. Its successor results are not inferred from older passes.

Historical functional check at `fdbcc3472`: the installed serialized ext4 lane ran
`:app:testDebugUnitTest` with `ConversationComposeTest`,
`ConnectedConversationComposeTest`, `QueuedComposerIntegrationTest`,
`ShortcutQueueIntegrationTest` and the existing `DurableRetryIntegrationTest`.
**30 discovered, 30 passed, zero failed/errors/skipped** (1m15s). This includes
terminal timeline recovery and failure-before-seen ordering, honest paging,
24 mounted hardware key families, durable direct/channel shortcut acceptance,
real transaction rejection, typed preconditions, disconnect/different-radio
confirmation, exact cancellation provenance, SYNCING drain and file-store reopen.
At `f1c2a8fb7`, shortcut discovery was seven/five passed/two failed; at
`cbf9adaaf`, eight/seven passed/one failed. These are not relabeled as passes.
The fixes wait for a real held contact query and use a genuinely distinct
connection target for different-radio confirmation. Matching current-device
execution and exact hosted checks are separate prerequisites. Actual main
`cd436cb63c3b651241058d16db2fa576cbc6d412` is subsequently integrated at
`bad5aca388b98ac76ed482027b36150425fcfa60`, preserving the landed shared composer
and byte-identical App failure adapter. The same 30 selected cases pass there
(1m20s); exact hosted results remain authoritative, not this local observation.

### Matching native device observations at `bad5aca38`

The declared `:app:assembleDebug :app:assembleDebugAndroidTest` produced matching
target/test APKs. Target SHA256:
`7c049d30463bf7e653fdd1f188efb4eb13bc426cd2cf26a2ed31c9049aa457fb`;
test SHA256:
`be1c975557ad77d7d2099a73d01905d3d7156a558e6806803a6f214be97db52d`.
These identify actual device-installed bytes, not another reproducible CI bundle.

Scoped `adb -s emulator-5556 shell am instrument -w -e class` executed
`ConversationDeviceTest,NativeCapabilityTest,NativePlatformTest,NavigationKeyboardBackTest`
using their registered full package names and
`com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Actual API31: **12 discovered/12 passed**, 119.52s, SDK31/4KiB pages,
1080x2400/density420, AOSP/no GMS, observed OS font2/no default network.
Actual API37: **12 discovered/12 passed**, 151.66s, full37.2/16KiB pages,
1344x2992/density480, GMS present, observed OS font2/no default network.
Each retained 58 theme/font/SYSTEM and two local-content PNGs. Paging, 24 actual
mounted Return/NumPadEnter/Shift families and strict identical-draw local image/map
assertions pass. These are current-window observations, not folding continuity,
physical TalkBack/radio evidence or approved goldens.

The same command selected only
`com.meshcoreone.android.uitests.PlatformApiBoundaryDeviceTest` on actual isolated
AOSP API33/34/36. Each **two discovered/two passed** (0.055s/0.341s/0.083s),
with denied notification advisory behavior, pre37 LAN independence, connectedDevice
registration/normal permission and the public CDM method boundary checked without
removing a bond. All observed 4KiB pages/no GMS/no default network.

Missing images were installed using the actually available Linux SDK CLI into an
owned private SDK, using existing accepted SDK licenses without accepting new ones,
telemetry disabled and no shared SDK update. Extraction moved from a stalled
mounted Windows directory to ext4; the first fast-copy ENOMEM was repaired via
bounded streaming/Windows transfer. A startup transport miss and API37 `svc data`
exit20 occurred before test execution. A later API37 attempt timed out at450s
with an unresponsive emulator and remains failed/unverified, not a pass.
Only its verified owned QEMU child was stopped; completed owned staging was cleaned,
then the unchanged assertions passed on matching APKs. Both normal successful AVD
runs were shut down through scoped adb; the API31 font setting was restored.

Non-reproducible native logs/PNGs are retained in this worker's session
`files/api31-device-bad5`, `files/api37-device-bad5` and scoped
`api*-instrumentation-bad5-private.txt` / `api33-boundary-bad5-private.txt`.
Raw emulator logs are not committed. Earlier binaries and failures below retain
their original identities. Full WP acceptance/review is not claimed.

### Matching native device observations at `49a0a5fa6`

Exact source `49a0a5fa68790faa0687ef509815e8d42c5a4e49` includes actual live
main `d8181418614a5acaa46f4acde54b741b492ac4b0`. Its authoritative hosted
[run 38299465982](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38299465982)
passes all six jobs; that scaffold does not compile/execute `androidTest`.
Separate declared `:app:assembleDebug :app:assembleDebugAndroidTest` passes (38s).
Matching target SHA256 is
`481fdbf11e17cfba30176c83afa19bb4d9b76753fdd0fbc5677b3ae9758776dd`;
test SHA256 is
`8a3cc0e0a8fd8d4e1e43b15e55182521bcaa789235ec9e40bf056c16060b18e8`.
The same scoped instrumentation commands above actually pass
**API31 12/12** (107.004s), **full API37.2 12/12** (146.309s),
**API33 2/2** (0.032s), **API34 2/2** (0.058s), and **API36 2/2** (1.534s).
API31/37 observe OS font2/no default network, with 60 retained PNGs each;
31 has no GMS/4KiB pages,37 GMS present/16KiB pages. All targeted33/34/36
have no GMS/4KiB pages;33/34 observe no default network, but36 observes
active default network100, so no API36 offline-flow result is claimed.

Source/APK-bound non-reproducible logs persist in session
`files/api*-observation-49a0a5fa6-private.txt` and individual instrumentation logs;
PNGs in `files/api31-device-49a0a5fa6` / `files/api37-device-49a0a5fa6`.
The first local functional attempt terminated with Windows `0xC00000FD` before
discovery; the bounded-output retry passes30/30 (1m25s). The first API31
installation preparation became unresponsive before tests; only its verified
owned QEMU was stopped, and the unchanged retry passes. Successful AVDs shut
down normally, and31 font setting restored. None of these results is relabeled
as a successor pass, approved golden or whole-WP/hardware acceptance.

The installed supported lane is the Git-bundle/ext4 snapshot launcher:

```powershell
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit HEAD --stages preflight
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit HEAD --stages scaffold
```

The lane preserves the actual shared Gradle flock, credential-stripped
environment and pinned Linux toolchain. Never invoke Gradle on mounted Windows
`.git` or bypass another owner's lock.

- At `3fce7297eaf47b97ccf9f5ff9359e1699ac1ab01`, installed Linux pre-push
  `python/preflight/scaffold/inspect` **passed** (673.36s), before new tests.
- At `fee7a2dfe9f65808012293da6cb4167bf7094862`, scaffold compilation and
  positive App discovery succeeded; **684 tests, two failures** in new assertions.
- At `f005faf884` (abbreviated Git commit), existing `:app:testDebugUnitTest`
  with the two owned class selectors discovered **six tests, five passed,
  one failed**: the persisted FAILED row had not yet reached the displayed
  timeline assertion. The 87 earlier rendering inputs passed.
- At `5ee3e8fb0d26b345953484bb0dbf5f8d13cf1c80`, the expanded native matrix
  again discovered **six tests, five passed, one failed**. All 125 rendering
  inputs passed. The semantic diagnostic showed the visible row was still
  SENDING while Room already held FAILED. A later explicit 30-second consumer
  wait did not fix this failure.
- Published `38dea8f910040f33eeb238584a2bc7e2533f2a73`: installed scaffold
  discovered **687 App tests: 685 passed, two failed**. The targeted existing
  `:app:testDebugUnitTest` with `--tests
  com.meshcoreone.android.uitests.ConversationComposeTest` and `--tests
  com.meshcoreone.android.app.platform.ConnectedConversationComposeTest`
  discovered **nine: seven passed, two failed** (1m12s). All 127 rendering
  inputs completed, including local image/map content. The failures were the
  unchanged connected consumer state and a no-older-page fixture incorrectly
  inheriting `hasMoreMessages=true`.
- At that exact snapshot, the installed plugin-declared
  `:app:assembleDebugAndroidTest` **passed** (25s).
  The combined PowerShell command's exit zero came from this last build;
  it does not turn the preceding failing unit run into a pass.
- The no-pagination fixture and image-disposal ordering are corrected in the
  subsequent candidate. A new mounted hardware-key regression is registered in
  both runners. These changes require new positive discovery; the expected
  owned unit total is now ten, not a relabeling of the nine-case result.
- Ordinary `git push origin HEAD` published `38dea8f910` after the installed
  hook explicitly recorded **UNVERIFIED CANDIDATE**. The queued-hook defect is
  tracked separately in #211 with the existing WP-504 producer; no bypass or
  foreign-lock change occurred. Exact hosted checks remain authoritative at
  [run 38273168422](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38273168422).
- The `70cc1c413` hardware fixture had a compilation error; no tests are credited.
  The corrected `aaf3845bdca08d8a1e8f078b6881dd3d83bce0e0` discovered ten:
  eight passed/two failed (1m13s). Paging passed; all twelve unmodified key
  families passed, while all twelve Shift families reported zero callbacks
  but missing newline. The other failure was the connected send.
- With the real producer Shift fallback integrated, `d47193cf9` discovered
  **ten: nine passed/one failed** (1m28s). All 24 mounted key families and
  the corrected paging case passed; the old app send recovery still failed.
- Ordinary `git push origin HEAD` actually published App consumer
  `0ae51f7e1ca9c8a4c7aa962a0dfadae59923bf8e`: the installed repaired hook
  queued ten seconds, recorded this exact candidate **UNVERIFIED**, then
  pushed successfully. This is publication, not a current check or merge pass.
- `python tools\android-port\controller\validate.py` **passed** the frozen
  inventory: 1,866 tracked reference files, 64 WPs, 178 dependency edges.
  This proves traceability validation, not behavior parity.

On 2026-10-12 an isolated session-only API31 AOSP x86_64 AVD executed eight
selected instrumentation cases: **eight discovered/eight passed**, 91.53s.
These observations bind the earlier `5ee3e8fb0d26b345953484bb0dbf5f8d13cf1c80`
test APK, not `38dea8f910` or the new hardware-key regression.
Scoped adb observed `sys.boot_completed=1`, SDK31, 1080x2400px, density420,
no `com.google.android.gms` package and no active default network.
The three owned cases covered offline/UTF-8 input, retained draft/retry and
58 native theme/font/SYSTEM frames; five existing cases covered actual optional
permission/camera denial, cached offline conversation/full emoji insertion,
one process graph and real IME/Back/insets. It was shut down with scoped
`adb -s emulator-5556 emu kill`.
`settings get system font_scale` returned null; controlled Compose font cases
are not evidence of an OS-wide 200% setting on that run.
An earlier launch correctly rejected another owner's hard lock; it was not bypassed.
An isolated API37 AVD has now actually booted under the same trusted lock:
SDK37/full37.2, 16384-byte pages, 1344x2992px, density480 and GMS present.
With observed OS `font_scale=2.0` and no active default network, ten selected
cases executed in 127.384s: **eight passed, two failed**. The 58 theme/font/SYSTEM
frames and the five existing platform/navigation cases passed; the local
image/map two-draw comparisons failed. This is not complete API37 acceptance.
The known faulty loading case was not selected, and new hardware-key cases were
not in this binary. The AVD was shut down normally and its actual lock released.

This run used target APK `AD767D642E33933B4364A09597A615C754935ADC08D02E428861ABD8026C2527`
from the earlier `5ee3e8fb0d` build and actual `38dea8f910` test APK
`01E9A83D314489BB569721A3AD566A4921A277E4777D3F8D264DD1EBCB6AFDB4`.
These are device binary bindings, not a second reproducible CI-result bundle.
The instrumentation command's exit zero is Android's transport outcome, not
a green JUnit result. No newer candidate is credited with these observations.
Installed image directories for API33/34/36 are absent; directory inventory
is not substituted for any required actual platform observation.

The earlier Windows launcher failure is historical and host-specific:
`platforms\android-37.2\package.xml` is absent in the default Windows SDK.
The installed `android-37.0` was not substituted. No license acceptance,
SDK provisioning, signing or release action occurred.

## Consumer repairs awaiting exact-candidate verification

The connected assertion exposes a producer-owned app assembly gap:
`AppChatsFeature.sender().sendPendingDirect` bypasses the send queue and calls
`MessageService.sendPendingDirectMessage` directly. `queuedDM` intentionally
uses `sendBoundary(id, false)`; its failure persists FAILED without publishing
the terminal failure event. `ChatSendQueueService` normally publishes it using
`notifyMessageFailed`, while the active app timeline remains SENDING here.
The event adapter and ViewModel already handle the missing failure event.
The exact app path plus `add-direct-support-wiring` is admitted by the trusted
`app-build-launcher` rule. The coordinator assigned bounded #215 to this same
worker and reservation; app queue assembly is being repaired without changing
core `notify=false` or injecting fake UI state. The producer's actual published
queue-shaped port is now integrated, removing low-level timestamp flags and
wire-return receipts rather than ignoring arguments or inventing attempt counts.
The historical direct-edit admission rejection was resolved by serialized
primary-owner production and trusted disjoint integration, not a bypass.
The App-only consumer routes pending envelopes to the captured service queue;
manual DM retry still persists once then signals only. Channel retry/Send Again
uses genuine stored text/timestamp metadata. Non-cancelled local failure persists
unless delivered, marks only the visible matching thread seen and broadcasts
the actual failure event. The new tests and mounted recovery require current
positive discovery.

The hardware regression follows the frozen `handleHardwareSend` contract:
disabled Return returns false for normal multiline editing, with no send
callback; enabled Return sends once; Shift preserves newline editing.
#212 is serialized to the existing WP-308 composer producer. WP-502 changes
its owned mounted test fixture/runners and integrates the actual published
producer correction, rather than duplicating a product handler implementation.
The disabled guard and Shift editor fallback are integrated through actual
`b644c9a1d` and `149581edb` lineage; the `d47193cf9` host cohort passes all 24
families. This remains distinct from device keyboard evidence.

The next local-content fixture explicitly suppresses transient Material ripples
for static rendering inputs, advances the injected Compose animation clock,
and captures hardware windows only after their GPU frame-commit callback.
Two draws must still be byte-identical; no pixel tolerance or golden is changed.
Image owners are disposed and awaited before recycling their bitmap.
These corrections require new host/device evidence.

## Artifact and acceptance boundaries

Native candidate images are generated under
`android/screenshot-tests/build/screens/sdk31/{themes,system,states}` in the
managed ext4 snapshot. Device images are generated in the target app's external
files directory under `wp502/sdk<actual>/themes`. Do not commit a second
reproducible CI-result/image bundle; exact-candidate hosted results/logs remain
authoritative. Retain only actual non-reproducible device/human evidence.

`verification.configured=false` remains in the deployed manifest with actual
software commands now declared by the versioned WP-003 amendment. These
App/scaffold commands execute registered real tests but do not constitute
formal full-WP acceptance.
`WP-502-behavior`, `WP-502-boundaries`, and `WP-502-source-test-parity` remain
unaccepted pending complete implemented/observed coverage, exact-candidate
checks and review. Keep #135 open/assigned; no physical radio, legal, signing,
golden approval or human gate is claimed.
