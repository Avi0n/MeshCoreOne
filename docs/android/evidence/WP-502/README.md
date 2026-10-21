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
[PR #249](https://github.com/cbattlegear/MeshCoreOne-Android/pull/249)
after actual PR244 merge.
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
Current integrated base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
earlier bases `c9ed1f9b78bcc322fb1ec281d211f99852cfec3b`,
`85871f40b1520a89ced6b9bec74aa8eec2f4f4de`,
`bfd7551565de3c5b1e5a71f3f16df1b72c7af00f`,
`5714ba3be50618b8d946a21e0db15feb67f90eb6`,
`918af38472a43bf24f9a8bc4762c3c1fb45851b4`,
`ec0a41e80ac8637baad524b777f774c10cf99f5b`,
`680246f7914a5b4e5bf5167a916b620f83a79dee` and
`aae3b10dd78f3d3314f51291687932f42f7d0ac9`.
Earlier software-command integration base:
`9c430cb0f1666faacc443794899b68c01128a87a`.
At the prior integrated base `574d440b5b05ba6609f60d7ba0913c2d8bdaea0d`,
the trusted policy revision was
`c1e87cc1e69400e25d035b40349ec62cf3bd14143ce0bb17932e04d46e9dadb0`.
The original binding is audit history, not rewritten to the newer policy.
Manifest/source remain unchanged. Current semantic policy revision is
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`;
merged254 changes operational paused=true to false only, preserving
dispatch_mode=off/activation_approved=false. Policy bytes equal actual maindbb,
including normally merged259's bounded renderer capability, not candidate policy
edits. The same typed reservation is now actual revision29: six-pathdbb
reconciliation returned28, then the exact named renderer operation returned29.
An initial expected27 renderer request correctly rejected stale revision before
any edit; the retry used actual28. Earlier six-path858
reconciliation returned27; later four-pathc9ed reconciliation was idempotent at27.
Earlier real eight-path918
evolution reached26, five-path5714/four-pathbfd reconciliations were idempotent
at26, and ec0/Rooms operations were idempotent at25. No inferred revision.
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
| `OnboardingPairingComposeTest` | Seven mounted production Flow/coordinator methods: four primary/held-recovery/Retry/benign-cancel/demo-priority methods, plus three just-in-time Bluetooth grant/denial/adapter-off recovery methods. Actual request-result callbacks refresh controlled snapshots; one grant resumes one held pair, repeated callbacks do not duplicate it, denial consumes intent without a prompt loop, Settings/WiFi remain reachable, and later grant/adapter recovery requires explicit Add. Optional location/notifications/LAN stay denied and independent. Controlled ports, not OS chooser/permission dialog/BLE/radio/device evidence. |
| `OnboardingRegionPresetComposeTest` | Seven actual mounted methods pass together at16a060a03 after the real canonical WP305 repair: picker resets/commits, held apply/typed errors/Retry/cancellation/completion, retained negative-to-positive max-remedy/fallback/reset assertions, independent acknowledgement/reset and Back/country-change invalidation. All seven named XML identities pass. Historical679/598 failures remain recorded, not weakened/relabelled. Controlled ports, not geocoder/radio/firmware/full onboarding acceptance. |
| `QueuedComposerIntegrationTest` | Real Room/app/service pending direct/channel, retry and Send Again queue ownership; terminal event count/cleanup, real enqueue transaction rejection and typed provider failure, unless-delivered failure/visible-thread seen state, observer cancellation and replacement-generation hydration. Newly defined assertions require positive execution before being credited. |
| `ShortcutQueueIntegrationTest` | Real direct/channel durable acceptance, typed creation/enqueue/cleanup failure stages, cancellation provenance and generation replacement, SYNCING acceptance/drain, and actual file-store close/reopen. The resumed reopen case reconnects a fresh AppContainer to the same radio, requires both durable rows to drain through the real queue, exactly one wire command per message, terminal persistence/cleanup and no duplicate messages. The session-error conversion/presentation case separately injects synthetic port failures: six frozen MeshCore error families, both actual service-wrapper types and two locales require exact underlying identity, outer cause and localized presenter text, without persistence or wire sends. Its assertions are not evidence that the durable queue emitted those synthetic faults. |
| `CoverageMatrix` | Independently specified Ember forced-dark rule, all ten unlocked themes/19 effective frames, 20 SYSTEM requests. 57 theme/window/font/direction rows cover each effective frame at 360/744/834dp and both 100%/200% font scales/directions. 48 state rows cover all 176 pairs across 12 locales, four delivery/connection states, three widths, two fonts and two directions. Exact pair-set assertions reject omissions. |
| `LocalContentCases` | Self-authored schematic raster and cached metadata, not external map/terrain data: production inline-image failure reporting/retry/viewer, manual/loaded/malware preview actions, offline map loading/failure/retry and coordinate callback independence. Injectable local sources never fetch the network. |
| `ConversationComposeTest` | Native Robolectric API31 execution of the production-content flows and matrix; expected 127 distinct rendering inputs including local image/map content, two identical settled draws per capture, nonblank pixels and unclipped visible message text. Native host evidence is not emulator or approved golden evidence. |
| `RoomsComposeTest` | Original four route/render methods, thirteen mounted ordering witnesses, six original label/action consumers and three actual-renderer consumers remain. Renderer results stay8PASS/1FAIL atacec then only failed frame1PASS at1e5. Three Swift-reachable load-failure consumers plus three supplementary Android-only defensive port-fault consumers pass together ata9a6be400; every26 incumbent source line/helper/Fixture is preserved. Source32 is not a combined32 or primary original-case approval. No actual RoomServerService/history sync/Room-database/radio/browser/spoken-TalkBack/golden/device/full-WP claim. |
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

### Rooms load failures: three Swift-reachable and three Android-only consumers

After actual52e source review COMMENTED5594110568 (no additional bounded
blocker), the coordinator directs the next genuinely missing existing Rooms
behavior family locally while preserving healthy published52e/39530338898.
Full frozen ViewModel.loadMessages/View consumers and actual holder/route/
existing Fixture were read. Frozen loadMessages has only three throwing calls:
`try await fetchMessages`, `try await markAsRead` and
`try await dataStore?.markFailedSendsSeen`. Those first three consumers exercise
source-reachable load failures. Notification removal and badge update are
nonthrowing `async` NotificationService methods, and
`SyncCoordinator.notifyConversationsChanged()` is nonthrowing. Injected faults
at those last three Android dependency callbacks are supplementary Android-only
defensive behavior, not reachable frozen Swift load failures. None of these
six consumers is an additional frozen test original or primary-case approval.
Source **a9a6be400161c58a91f5c7660eae073ae9522727/basedbb** adds165 lines/
zero deletions: six methods, one exclusive helper and coroutine-test imports.
Same-owner owned modify genuinely returns29 idempotently.

Each actual mounted route holds initial fetch (spinner, no empty flash), edits
the original200% CJK/RTL draft, then injects the same typed PermissionDenied at
exactly one of fetch/read/failed-seen/notification-removal/badge/change callbacks.
The full original session DTO is asserted on every room-scoped call. Exact
attempt prefix stops at the failing stage; for the last three stages this
short circuit is Android-only defensive behavior, not a Swift-reachable catch.
No later side effect is invented.
Fetch failure shows empty/error without cached rows; later-stage failure retains
the fetched/keyed/once-labelled row. Original failure identity, localized visible
error, exactly one diagnostic, hasLoadedOnce/loading/draft and no false service/
authentication actions are asserted. Dismissing error does not reload.

One real incoming event appends before reload; injected StandardTestDispatcher/
TestCoroutineScheduler asserts no new fetch at49ms and exactly one at50ms.
The held retry retains optimistic rows/draft without spinner/empty/error; actual
completion restores both history rows, exact independent row values and the
complete six-call sequence once. Owning-scope cancellation reaches zero event
subscribers; later event/50ms cannot mutate state/callbacks. Existing lifecycle
cleanup, all26 incumbent source lines/helpers/Fixture, production, renderer,
core, build, policy, manifest and Region7 remain unchanged.

Initial **4dae366b46d8f00716d21c5e56fe399669b3abb7** discovers/runs6 with
**0PASS/6FAIL,0errors/skips**,35s, XML02:14:17.607Z. Extra Compose idle/queries
let the real Looper's50ms debounce fire before the optimistic-phase assertion.
The new helper alone receives an injected event dispatcher/clock, retaining
the existing scope Job/lifetime and strengthening exact49/50ms assertions;
no delay threshold/product change. **e6fad1f835b868d2a3fa6e71a5ddb9270e85a689**
passes6/6,32s, XML02:16:05.523Z/preflight0.73s. Finala9a6 strengthens all six
with original-session and teardown guards, then one affected-six invocation
passes **6 discovered/run,0failure/error/skip**,31s,
XML2026-10-21T02:18:04.085Z/preflight **PASS0.61s**. Exact six XML identities
match selectors, not just source counts. Real exclusive Ledger Gradle context/
ext4 flock/native checks and only-own normal release remain.
[Exact commands and classification](software-verification.md#rooms-load-failures-and-android-only-defensive-port-faults).

This three-source/three-Android-defensive controlled-port family is not actual database/
service/history/radio sync, cancellation of live platform services, new frames/
goldens/device/TalkBack/full-WP acceptance. Published52e remains unchanged while
its automatic CI is healthy; its bounded review does not approve these new six.
At the latest read, completed exact52e Linux118995044121 is **SUCCESS** and its
authoritative log explicitly reports **App814 discovered/run/PASS,0failures/
errors/skips** at2026-10-21T02:20:15.456Z, including all three renderer and six
original label/action method identities. Subsequent exact workflow read confirms
**39530338898 terminalSUCCESS/all6**, including SwiftData119001327928 and
aggregate119002400794 SUCCESS. This814 belongs only to52e/basedbb,
not the new load-stage family. The log is consumed in memory, not a second bundle.
Bounded06a source review found the earlier all-six source-fault classification
incorrect; this documentation-only correction preserves all six tests and their
actual a9a6 preflight/XML/results. No product, fixture, test, scope or acceptance
change and no healthy-test replay. Published06a/39533421332 remains unchanged
while its automatic verification runs; this correction needs its own comparison.

### Admitted issue253 renderer repair and actual renderer observations

Normally merged259 deploys trusted main
**dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c** (parentsc9ed/475ffefa).
Same-owner merge942f8bbed imports its six owner files exactly, actual receipt28.
Fresh receipt29 admits only `RoomConversationScreen.kt` /
`repair-room-incoming-avatar-bookends` through `rooms-incoming-avatar-repair`.
No claim recreation, authorization rewrite, foreign release or policy edit.
Primary WP310 provenance and state/order/service/auth/core sources are preserved.

Production **acec78938d4328414954602134d9c460f886017e/basedbb** reuses shared
ContactAvatar(name,size,modifier), not a photo lookup/provider/new asset/identity
algorithm. Incoming diameter is28dp times max(fontScale,1), plus6dp gap; hidden
incoming rows reserve that same width and the visible author uses the same inset.
Only incoming showAvatar rows render the bottom-aligned decorative avatar.
Self reserves no incoming column/avatar/name. Original once-only parent message
label/custom actions remain intact. Frozen Rooms uses initials identity.

Actual pre-repair source **63eda930769efea86e080a1402afdeff0d0de2ef** executes
the live-burst selector: **1 discovered/run,0PASS/1FAIL,0errors/skips**,47s build,
XML2026-10-21T01:44:09.856Z. The unchanged renderer lacks actual displayed `A`,
not merely a new test tag. A prior real foreign-lock collision executed zero
tests and remains only serialization history, not this negative result.

Repairedacec executes one strict invocation of new3 plus directly affected6
original label/action consumers: **9 discovered/run,8PASS/1FAIL,0errors/skips**,
34s build, XML01:45:47.411Z. Live avatar handoff, self/no-gutter and all6 labels/
actions pass. The frame method fails its initial paragraph-width proxy. Focused
diagnosticsc805/e624 each execute only that failed selector,1FAIL: actual glyph
is7x24 within28x28 avatar, while unused paragraph allocation is28 wide.
Final **1e5ea4457eac0e5f50f06eeea4e0f91df17b64f5** replaces that proxy with actual single-glyph bounds inside
both measured text and rendered avatar, exact font scale, retained height check
and theme glyph/pixel assertions; no tolerance/golden/product change.
Only failed frame selector is rerun: **1 discovered/run/PASS,0failure/error/skip**,
38s build, XML2026-10-21T01:50:35.620Z; preflight **PASS0.63s**.

All57 existing theme/window/font/direction inputs complete actual28/56dp
diameter,6dp gap, hidden-row/name alignment including RTL, bottom alignment,
shared theme identity fill/readable glyph, once-only labels and draft assertions.
Each captures two identical settled nonblank native-host draws and checks actual
avatar fill pixels. These57 new host render inputs are not approved goldens or
API31/37 device observations. Live burst checks optimistic/held50ms/reconciled
state, original keys/full DTOs/retained first-bubble leading edge, two read cycles,
one subscriber and no false actions/errors. Self checks no incoming chrome at1x/2x.
Existing healthy cohorts are not replayed; no invented9/9/26 execution.
[Exact selectors and failure explanation](software-verification.md#admitted-rooms-avatar-renderer-and-directly-affected-consumers).

Predecessor **a3c629cf2f487846807dfce74664597b8c90d9f7/basec9ed** was left
published throughout its healthy automatic run. Actual
[39526597754](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39526597754)
is now terminalSUCCESS/all6. Authoritative Linux118983145710 explicitly reports
**App805 discovered/run/PASS,0failures/errors/skips** at01:46:30.481Z and all
five new ordering method identities. SwiftData118991144810 and
aggregate118992266560 SUCCESS. This is a3c evidence only, not successor CI,
renderer/golden/native/full-WP acceptance. No rerun/cancel/result bundle.
Earlier admission-pending/flags-only sections below are historical, not a current
renderer-admission blocker. Held renderer history/stashes remain untouched.
`python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py` and `git diff --check`
**PASS**. Exact comparison preserves all six incoming259 owner blobs, every
a3c incumbent source line/helper/Fixture and Region7. This is traceability/
preservation, not extra test executions or full feature equivalence.

### Next independent action-consumer family: six original accessibility cases

After complete grouping source COMMENTED5593825846, the coordinator directs
the next genuinely unrepresented coherent existing Rooms consumer family.
Read the full six-case frozen RoomMessageBubbleA11yLabelTests and Bubble/
status/View/action-dispatch consumers plus current renderer/formatter/
dispatcher/resources. Code **542097bbd409acbfdfc62ea00a9003fff57d89aa/basec9ed**
adds119 lines/zero deletions: all six original cases and two exclusively new
content/label helpers. All17 incumbent bodies/helpers/Fixture and Region7
remain unchanged. Same-owner modify is idempotent at actual revision27; no
production/build/policy/manifest edit or renderer-rule assumption.

Installed preflight **PASS1.77s**; one strict existing App invocation discovers/
runs/passes **6,0 failures/errors/skips,1m21s build**. All six exact named XML
identities are checked against selectors, fresh timestamp
2026-10-21T01:26:22.096Z.
[Exact commands and original mapping](software-verification.md#six-original-rooms-accessibility-label-and-action-consumers).
Real canonical-owner shared Gradle lock and ext4 flock remain; only this
worker's resource is normally released. A later different-owner Gradle hold
is left unchanged, not misreported as an unreleased own lock or a test failure.

Exact frozen prefixAA/Alice/100/`follow up`, prefixAA/Alice/100/`hello`,
prefix42/Me/self100/`hello`/SENT or FAILED, and
`see https://example.com` are preserved. Actual production content consumes
the original explicit timestamp/name/avatar row flags. Its parent exposes
exactly one original message description; actual author/status occurrence
counts and absent duplicate spoken name/status, visible raw text/keyed bubble,
displayed or absent author and unchanged CJK/RTL draft are asserted at200%.
Failed Retry invokes exactly the original message ID once; SENT exposes
neither custom nor visible Retry despite a supplied callback. HTTPS uses the
real formatter/resource-backed named custom action and MessageLinkDispatcher:
the exact original URL reaches the local route port then captured external
port once, with example.com host. It never resolves contact/radio/mention
ports or launches a browser/network request. Unexpected send/draft/auth/error/
link/retry callbacks throw rather than default-success.

These are mounted semantics/custom-action consumers, not spoken TalkBack,
actual service retry/delivery, browser/OS integration, avatar rendering or
primary equivalence approval. Earlier real-route failed-send/Retry evidence
is separate, not replayed or credited as this six-case execution. Source23 is
not local23/17+6; currenta3c629/run39526597754 and review5593825846 remain
predecessor-bound and preserved. Full Rooms sync/history/actions/store/radio
and current native/golden/physical/full-WP acceptance remain open.

### Remaining four ordering originals in one coherent increment

After source-only review5593585260 of3d743/base858, the coordinator explicitly
replaces the one-next-family stopping point with all four remaining frozen
grouping/tiled-row originals together. Code
**dc226292adc80f1a229b49d45fcebde7186a006d/base858** adds five methods and
one new, exclusively used assertion helper:163 insertions/zero deletions.
The300s/301s original becomes two independent mounted cases, not a three-row
adjacency substitute. All12 incumbent methods/helpers/fixture and Region7
are unchanged; no production/build/policy/manifest/screenshot edit.
Same-attempt owned modification is idempotent at **actual revision27**.

Installed exact-source preflight **PASS0.72s**. One strict invocation of the
existing App task discovers/runs/passes **5**, with **0 failures/errors/skips**,
**40s build**. Fresh named XML is2026-10-21T00:54:00.647Z; the runner checks
all five exact executed method identities in addition to strict counts.
[Exact selectors and four-original mapping](software-verification.md#remaining-four-ordering-originals-one-five-case-invocation).
The canonical owner acquires/releases the real shared `gradle-execution`
resource through the existing Ledger context; the ext4 runner also retains
its actual exclusive Gradle flock. Native absence is checked before/after
acquisition. Both resource lanes are empty after normal completion; no foreign
lock action, new device operation or healthy-incumbent replay.

Exact frozen vectors are preserved: prefixAA/Me/self100/160; prefixAA/Alice
incoming100/160; independent Alice pairs `[1000,1300]` and `[1000,1301]`;
Alice/prefixAA incoming/self/incoming100/160/220. Explicit independent
RoomMessageRow expectations prove every timestamp/name/avatar flag.
Actual keyed bubbles, original parent labels, raw `msg`, displayed name
placement/absence and literal UTC timestamp presence/absence are checked
before/optimistic/held50ms/reconciled. CJK/RTL draft, full DTOs, no empty/
spinner/error, one subscriber and exactly two full read sequences persist;
no false post/join/retry/diagnostic. Actual retained first-row value changes
for the two-message follow-up while its logical ID/DTO remain. Locale,
FORMAT/DISPLAY defaults and timezone restore in finally; existing scope/
activity teardown remains.

All12 frozen ordering originals now have these dedicated mounted witnesses
across the documented sources, not a new combined17-case execution or
primary-owner equivalence/acceptance. Rendered avatars253, full Rooms
sync/history/actions/store/radio, current native/goldens and full WP135 remain
open. The separately prepared renderer admission is not permission to edit
RoomConversationScreen. Existing3d743/run39522836727 was preserved to terminal
SUCCESS/all6; authoritative Linux118970922775 directly reports App800
discovered/run/PASS,0 failures/errors/skips at01:01:31Z and the distinct-prefix
method identity. SwiftData118977041858/aggregate118979177154 also SUCCESS.
Bounded source COMMENTED5593585260 is3d743-only. No result/review transfer,
manual replay, cancellation or duplicate result bundle.

Actual258 merged2026-10-21T01:12:38Z as
**c9ed1f9b78bcc322fb1ec281d211f99852cfec3b**. Same-attempt four-path
`reconcile-current-base` returns **actual27** idempotently; merge
**ab53bb70da9eb2858c8588fd72cac9e665b42913** preserves all incoming
ci.py/test_ci.py/CI-doc/WP003 bytes exactly. All16 incoming owner paths since
ec0 equal mainc9ed. Android/manifest/policy equal executeddc226; actual5
remains dc226/base858, not a new-base execution. This imports real258 timeout
diagnostic support, not a candidate tooling fix, altered deadline or deployed
renderer-admission rule. The coherent new-source/current-main successor
requires its own exact hosted CI and full source review; no successful cohort
was replayed or source successor created solely to record CI.

Coherent8e3c657ced2beb3740eb1a84492db42556a40dbf preflight passes0.68s;
portmap/controller/diff and exact16 incoming-owner/all12 incumbent preservation
pass. The pre-publication path comparison corrects the initial c9ed prose
count14 to actual16; admission still genuinely returns27 idempotently because
the two additional incoming paths were already in scope. No receipt or
execution count is inferred from that file count.

### Actual main858 reconciliation after merged heap support257

At publication preparation, fresh main ref/ancestry showed
[PR257](https://github.com/cbattlegear/MeshCoreOne-Android/pull/257)
actually merged at2026-10-21T00:17:44Z as
**85871f40b1520a89ced6b9bec74aa8eec2f4f4de**, not cached PR basebfd.
Same attempt's six-path `reconcile-current-base` genuinely evolves26->
**actual returned27**, adding only ci_environment.py and its test to the
existing scope. Identity/binding/immutable authorization survive.
Ordinary merge **330e75108bdf408bfe531757d22bf65bfb631c70** preserves
all six incoming owner files exactly. Android/manifest/policy equal executed
ed227; its1/1 remains basebfd, not a new-base result or replay.
This imports the existing003's real full-assembly heap support, not a WP502
heap fix, packaging/native action or deployed Rooms renderer-admission rule.
The latter remains separately pending; no foreign renderer edit or approval.
Actual prior3f3 CI/review remains3f3/basebfd, not successor verification.

### Next original family: different prefixes with the same display name

Coordinator's source-only COMMENTED5593292686 covers prior3f3/basebfd, not
this next family. Subsequent instruction explicitly permits the next missing
original locally; the actual503 native/Gradle lanes were released after its
D8/daemon OOM, not held indefinitely. Use the existing exclusive software lane;
do not change its heap/provider or touch the unadmitted foreign renderer.

Code **ed2276627f7df94bcc5800f759185e9d5d2ae176/basebfd** adds only the
frozen `different prefixes are two clusters even when display names match`:
0xAA/Alice/msg100 and0xBB/Alice/msg160. Installed preflight **PASS0.69s**;
smallest new-method-only App selector **1 discovered/run/PASS,0 failures/
errors/skips,44s build**, named XML2026-10-21T00:13:53.923Z/suite6.688s.
[Exact command and behavior inventory](software-verification.md#next-original-family-distinct-prefixes-with-matching-names).
All11 incumbent bodies/helpers/fixture/Region7 are unchanged and not manually
replayed; source12 is not a local12-case or combined cohort.

Real route/armed subscriber receives the second message before a held actual
data dependency can complete. Both original name/avatar-flag IDs are required
even though the names match. Two displayed Alice nodes sit above their own
keyed bubbles, with the second name below the first bubble; two original
message labels, complete DTOs/unchanged first row/draft and literal timestamp
flags survive optimistic/held50ms/reconciled phases. Exactly two full read
sequences/one subscriber/no false actions or diagnostics. No new helper,
locale mutation, fake identity policy, golden or native observation.

The actual avatar consumer remains source-confirmed253, not proved by flags.
The coordinator assigned its typed admission-rule repair to existing003;
no renderer edit until that real reviewed rule/ownership admission. Ated227,
four other ordering originals and full source/native/golden/physical acceptance remained.
At this local execution, healthy3f3/run39518219256 is preserved in progress,
not cancelled or relabelled; old428f799/799 remains its own exact hosted result.

### Actual mainbfd reconciliation after merged packaging255/256

While exact428f/base5714 CI finished, actual main advanced through
[PR255](https://github.com/cbattlegear/MeshCoreOne-Android/pull/255)
at23:24:21Z and [PR256](https://github.com/cbattlegear/MeshCoreOne-Android/pull/256)
at23:36:30Z to **bfd7551565de3c5b1e5a71f3f16df1b72c7af00f**.
Fresh GitHub main ref and fetched ancestry, not cached PR base5714, identify
the four incoming debug-packaging/helper-test/CI-doc/WP003 paths.
Same attempt's supported reconciliation is idempotent at **actual returned26**;
identity/binding/authorization unchanged, no inferred27 or replacement.
Ordinary merge **4a7f10acf8d49cce95c22d03de918ee16ab62d6c** preserves
all four incoming files exactly; Android/manifest/policy bytes remain428f-exact.
No packaging, APK assembly, key access, native/provider/device action or
positive-cohort replay. The existing local1f73/baseec0 and exact428f/base5714
hosted execution retain their own bindings, not successor verification.
This is genuine current-main integration, not a source successor solely to
record CI. The new exact-head result/review remains separate.

### Actual main5714 reconciliation after user-merged pause correction254

User-authorized [PR254](https://github.com/cbattlegear/MeshCoreOne-Android/pull/254)
merged at23:00:39Z as **5714ba3be50618b8d946a21e0db15feb67f90eb6**.
Fresh origin/main/merge ancestry and exact five-path delta inspected.
Same attempt's `reconcile-current-base` returns **actual revision26** again:
all five paths were already admitted, not an inferred27 or a replacement.
Ordinary merge **e270dc7504602fbd8cfa16c7da6848678714e6ae** preserves
the policy/CI-doc/WP003/gate-test/state-test owner bytes exactly. The only
policy value delta is paused=false; dispatch remains off and activation false.
Frozen pin/manifest, Android bytes and immutable authorization/binding survive.
This imports the user's actual merged policy, not a candidate activation.
No native/device/metadata action or unrelated positive-cohort replay.
Local1f73/baseec0 and predecessor7ca execution bindings remain distinct from
the successor's still-required exact-head hosted check and bounded review.

### Actual main918 reconciliation after merged tooling252

User merged [PR252](https://github.com/cbattlegear/MeshCoreOne-Android/pull/252)
at22:44:27Z as **918af38472a43bf24f9a8bc4762c3c1fb45851b4**.
Fresh origin/main/merge ancestry inspected, not cached PR base metadata.
Existing same attempt evolves25->**actual returned26** under
`merge-integration/reconcile-current-base` for the eight exact incoming
controller/ledger/reserve/tests/CI-doc/WP003 paths. Unlike the earlier
erroneously inferred26 at ec0, this is a real new receipt revision.
No reservation replacement, authorization rewrite or candidate rule activation.
Ordinary merge **35fa6772abec627451bb3a2b16d4a74b89054990** preserves
all eight incoming owner files byte-for-byte, Android/manifest/policy unchanged.
The bookend execution remains1f73/baseec0, not a new-base replay/pass;
current successor needs its own automatic exact-head hosted result.
Published7ca/run39508725617 and source review5592631796 remain7ca/baseec0.
The independent WP503 native preparation/pause work is not this software
increment's execution prerequisite, nor a claimed device observation.

### One next original family: mounted same-prefix burst bookends

Coordinator-directed single next family, code
**1f73d3daf5c3ecbef958fcb6d04833213d15c2e6/baseec0**: installed preflight
**PASS0.60s**, exact new-method-only App selector **1 discovered/run/PASS,
0 failures/errors/skips,29s build**. Named fresh XML22:45:29.540Z/suite6.234s.
[Exact command/family inventory](software-verification.md#next-single-original-family-same-prefix-burst).
Only65 lines added; all10 incumbent methods/helpers/fixture are untouched
and not manually replayed. Source11 is not an11-case or combined cohort.

Frozen `same-prefix burst books name on first and avatar on last` keeps
prefix0xAA/nameAlice/textmsg/timestamps100,160,220. Real route/subscriber/
holder, two same-loop arrivals and held actual data dependency prove
optimistic rows before reconciliation. Actual visible name count remains1;
three once-labelled original bodies/keyed bubbles retain chronology.
Its sole author node is explicitly displayed above the first keyed bubble,
not just counted somewhere in semantics. Initial7525's1/1/30s is historical;
strengthened1f73 reruns only the same changed method, not a second family.
Complete DTOs/draft and first logical message ID survive its row-value
change; name/last-avatar IDs and timestamp flags match independent original
expectations before/during/after the50ms coalesced reload. Exactly two full
read-side-effect sequences, one subscriber and no false actions/failures.
No locale mutation, new production path, fake policy or native dependency.

**Do not treat avatar state as rendered output.** Full frozen BubbleContent
uses IncomingAvatarGutter; actual native RoomMessageRowContent never consumes
`showAvatar`. No declared avatar omission was found in the WP-310 deviation.
Deduplicated template [bug253](https://github.com/cbattlegear/MeshCoreOne-Android/issues/253)
records the source-confirmed product prerequisite, primary118/146 and207/209
provenance and the absence of an executed avatar-rendering negative/device
claim. No foreign product fix or extra worker. Five other ordering families,
actual avatar/gutter rendering and full source/native/golden acceptance
remain open. Stop this bounded increment for review as directed.

### Retained Room timestamp chrome and corrected derived fixture fields

Code **d76a576af1960155931d12baa14a7e6e59554b28**, baseec0, installed
preflight **PASS0.60s**. Same existing strict App task selects the five
affected live-event methods plus
`liveMidInsertionReconfiguresTheRetainedLaterRowsTimestampBeforeReload`:
**6 discovered/run/PASS,0 failures/errors/skips,34s build**. Actual XML
timestamp22:00:18.876Z contains all six identities, suite6.899s.
[Inventory contains the exact selectors/options](software-verification.md#commeshcoreoneandroiduitestsroomscomposetest).
No old four-method/57-render, Region7 or native/device replay.

The next concrete frozen original is `mid-insert flips the next row timestamp
flag`: same-prefix0xAA/Alice `[100,500]+250`. Real mounted route retains the
original later DTO/identity while its timestamp flag changes true-to-false.
Two actual visible US/UTC timestamp headers become one before the reload;
the first header, exact visible row order and original CJK/RTL draft persist
during a held actual data dependency and after release. The source50ms
deadline is explicitly driven, two complete ordered read-side-effect
sequences and no post/join/retry/diagnostic failure asserted. Default
TimeZone and global/FORMAT/DISPLAY locales are restored in finally.
This is not an internal Compose-node-ID, avatar or full date-format claim.

While tracing this family, found a tightly coupled owned-fixture defect:
new `liveMessage` copied a template after changing timestamp/prefix/text,
retaining the old derived `deduplicationKey`. Read the frozen RoomMessage
initializer and current DTO; both derive the key at initialization.
Use a fresh RoomMessageDTO, also for same-ID150 replay, with distinct-key
guards and a changed-key assertion. Production is unchanged; no independent
codec/oracle or primary test-parity result is invented. The historical
ece5/5 below remains its own actual execution, not proof of corrected fields.
That shared correction genuinely affects all five previous consumers, so
this is one required six-method execution, not a gratuitous successful
cohort replay or an aggregate of the earlier five and a separate one.

Current source has10 methods; only these6 were selected. Old four method
blocks/fixtures and all Region/preset predicates remain unchanged.
Portmap/controller/diff and production/build/policy/source preservation
checks **PASS**. At that execution, existing owned modify admission is revision25.
No new goldens, device observation, grouping/avatar/all12-original or
whole-WP acceptance; exact successor hosted checks/source review remain separate.

Latest source **2b478f7acd64405b305e963f7866aed9381f23fd** strengthens only
this new timestamp method: one explicitly armed subscriber before emission
and complete displayed-row DTO equality before/after, retaining original
logical message IDs without internal Compose-node coupling.
Exact preflight **PASS0.60s**; actual strict **single method1/1PASS,0 failures/
errors/skips,31s build**, fresh XML22:12:00.252Z/suite6.075s.
The preceding six-selector result remains bound to d76; unchanged other
five methods/helper are not replayed, no combined7/ten-case result invented.
Exact command is in the inventory. No production/assertion weakening.

### Mounted Rooms live events and coalesced reconciliation

Code **ece2fa06182d7f6b2d28144ee30408ab9fdeb4a2**, baseec0, installed
preflight **PASS0.62s**. Same declared App task selects only the five new
`RoomsComposeTest.live*` methods by their explicit full names in the inventory,
with existing strict dependency/build-cache/no-parallel/4-worker/512m options:
**5 discovered/run/PASS,0 failures/errors/skips,43s build**.
Fresh XML contains all five identities, timestamp21:35:24.913Z.
No legacy four-method/57-draw, Region/preset, old combined cohort or device rerun.

Read the complete frozen RoomConversationView/ViewModel and all12
RoomConversationViewModelOrderingTests, then actual primary Android
route/holder/contracts/tests. First five original parameter families are
preserved: `[100,200,300]+150`, `[200,300]+100`, `[100,200]+300`,
`[100,200(first)]+200(second)` and duplicate existing200 ID replayed at150.
Fixed independent UUIDs/createdAt and unique raw body labels permit actual
rendered-order checks without a candidate-generated expected oracle.

Real route starts its actual holder/subscriber/load. A same-loop duplicate
received-event burst updates rendered rows optimistically, then the actual
50ms looper deadline starts exactly one held data-source reload. Visible
semantic bounds/order/full DTO identities and original draft survive before,
during and after reconciliation; cached rows never become empty/spinner.
Held fetch produces no read/notification/badge/change side effects until
released, then completes exactly one second ordered sequence. No post/join/
retry/diagnostic failure; one subscriber. Existing controlled ports are
reused unchanged, no production edit/new fake policy or provider/device claim.
At ece, actual already-owned modify admission is idempotent revision25.

These were five actual mounted consumer witnesses at ece, not acceptance of all12
ordering originals, grouping/avatar/reconfiguration, full history/store/wire,
notifications or whole-WP parity. Old four Rooms methods remain byte-for-byte
unchanged. That source has9 methods; no separate9-case execution is claimed.
Portmap/controller/source-pin/old-witness preservation and diff checks pass.
Earlier ea4 [COMMENTED5591884702](https://github.com/cbattlegear/MeshCoreOne-Android/pull/249#pullrequestreview-5591884702)
is nonblocking bounded source review of the unchanged seven-Region increment,
not this new Rooms delta, an approval or a transferred hosted/native result.

### Actual canonical main landing and same-owner reconciliation

PR251 actually merged **2026-10-20T20:44:12Z** as
**ec0a41e80ac8637baad524b777f774c10cf99f5b**, exact54aa head.
Fresh `origin/main` inspection confirms only the canonical renderer/WP305
note changed from680; manifest/policy/frozen pin unchanged. Same WP502
attempt **revision25**, existing `merge-integration/reconcile-current-base`,
admits those exact two paths. Ordinary merge retains the seven-case source,
all prior witnesses and producer ancestry. Android bytes remain identical to
executed16a; this is no new test/native/hosted verdict.

Renderer equals canonical54aa and current main exactly. **Full WP305 note
does not equal main**: the earlier primary1ec9 nine-line Issue250 provenance
section remains as a disjoint, attributed addition; main's canonical retry
bullet/all existing lines are retained. No competing fix PR or claim that
this whole support note is byte-identical to main. Both original owner
contributions stay traceable, without rewriting live production.

077 was normally published before this main landing: installed180s advisory
times out **UNVERIFIED**, no completed cohort credited. Its actual automatic
[run39493993907](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39493993907)
binds077/base680, not the reconciled successor. Preserve that run/binding;
the current-main successor needs its own automatic exact-head result,
not a manual repeat of the existing local seven or producer five/56.
Receipt correction: the reconcile request was idempotent; its actual returned
JSON remains revision25, as does the subsequent already-owned Rooms modify
admission. Earlier ea4 prose inferred26 incorrectly. No ledger reset,
reservation replacement or extra authorization occurred.

### Canonical producer repair and seven-case negative-to-positive execution

User-directed primary routing replaces the earlier proposed support-policy
route; no policy expansion was made. Actual ledger read verifies leased
**WP-305 attempt6e503330-9c0f-426b-86c3-fa52f404dcab/revision1**. Its published
1ec9ca8fa1a040346ef4ad871918e7c588dd4d13 was received first. Coordinator then
designated [PR251](https://github.com/cbattlegear/MeshCoreOne-Android/pull/251) /
**54aaab70bc01725376f0af712dab0ca26c361a5b** sole canonical repair; the same
existing producer confirmed the transfer/no competing PR. Both have parent
main680 and the same message selection, differing in formatting/provenance
wording only.

Same WP-502 attempt evolves24->**25** through the existing
`merge-integration/merge-disjoint-semantic-edits` rule for the exact producer
`PresetStepScreen.kt` and WP305 deviation note. No new reservation, rule,
policy, source pin or producer implementation. Merge1d909 receives1ec9;
merge **16a060a037860e524c062f3dfe49bfe52f414b77** receives requested54aa,
retaining both ancestries and primary documentation. The single textual UI
conflict is identical conditional semantics in one-line versus multiline
format; resolved to **exact54aa UI bytes**, not a newly authored fix.
Tests/threshold/actions/controller/manifest/policy remain unchanged.

The earlier1d909 preflight was **queued/unexecuted**, with no targeted-test
log or discovery. Only this session's own waiter was stopped to select54aa;
no other holder/lock was modified and no old negative replayed.
After the real shared lane became available, exact committed source16a060a03,
base680, installed preflight **PASS0.76s** and existing strict task:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingRegionPresetComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**7 discovered/run/PASS,0 failures/errors/skips,46s build**. Actual XML
contains all seven method identities in the inventory as PASS, including
`threeRetryableFailuresShowConnectionRemedyThenFallbackAndResetTheRetryBudget`.
The unchanged frozen remedy/no-raw-message predicate now passes, and its
previously unreachable no-Retry/Cancel/OK-to-fallback/reset/retained-selection/
same-ID explicit-success assertions actually execute/pass.
This is **one seven-case cohort including the negative-to-positive method**,
not separate manufactured1+7 executions or acceptance of whole original
families. Other original Region cases and both independent followthroughs
pass in this same actual cohort.

`git diff --exit-code 54aaab70bc01725376f0af712dab0ca26c361a5b 16a060a037860e524c062f3dfe49bfe52f414b77 -- android/feature/onboarding/src/main/kotlin/com/meshcoreone/android/feature/onboarding/ui/PresetStepScreen.kt`
and exact owned test/screenshot comparison to2655 both **PASS**.
[Producer result handoff](https://github.com/cbattlegear/MeshCoreOne-Android/issues/250#issuecomment-6300737908)
records the exact command/source/counts. This is scoped local software proof,
not approval/merge, current hosted success, native/device/radio or whole-WP
acceptance. The canonical producer remains the owner of the fix.
Historical [598 hosted39487023988/Linux118850747810](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39487023988/job/118850747810)
actually fails: available App JUnit790 discovered/789 passed/1 failure/0 errors/
skips, solely the same missing-remedy method. Controller/classification/Swift
codec pass; mandatory SwiftData is **SKIPPED**, final android-ci fails.
No unexecuted oracle test-failure claim, rerun or CI-result bundle.
Those results are not rebound to16a; no new screenshot/golden/device run.

### Back navigation invalidates the old visible preset

At **2655e7a10e77dff5f0cef1fe8926482d28178dfd/base680**, seventh method
`changingRegionThroughTheRealBackCallbackCannotApplyThePreviousPreset` selects
real USA/us-ca, then invokes the callback registered by production
`OnboardingFlow` through its real `InterceptBack` boundary. Actual Region
heading returns and Preset disappears. Choosing Australia/Queensland and
Continue commits exactly `[US/manual/US-CA, AU/manual/AU-QLD]`; old USA row/
Use USA disappear, Australia/915.800 row is initially unselected, and Continue
is disabled/no apply or completion occurs. New explicit Australia selection
alone enables one held `au-915` apply; explicit Applied alone completes once.
No resolver, duplicate call or reuse of the old preset.

Installed preflight **PASS0.62s**; existing strict App method selector:

```text
--tests com.meshcoreone.android.uitests.OnboardingRegionPresetComposeTest.changingRegionThroughTheRealBackCallbackCannotApplyThePreviousPreset
```

**1 discovered/run/PASS,0 failures/errors/skips,34s build**, actual named XML.
No other cohort or known negative was replayed. Fixture captures the real
enabled Back callback in SideEffect, reusing the existing pairing witness,
not direct `flow.back`/coordinator test access or fabricated route state.
This is a controlled platform callback, **not OS/predictive Back evidence**.
Frozen PresetStepView resolves selected IDs against visible regional presets;
primary `staleSelectionIsIgnoredAndApplyingBlocksReentry` is prior art. This
asserts the mounted invalidated-selection controls, not the entire arbitrary
invalid-ID/private coordinator parameter family. No preset identity reset or
coordinator teardown is inferred from the correct visible state.
Earlier primary/known-negative assertions stay intact; production at that
execution remained unchanged. This witness was retained locally until the
canonical repair; that individual run was not a seven-case result. The later
actual full seven-case execution is recorded above.

### Independent max-retry acknowledgement and budget reset

At **381726bd751c3fe98640d78b87861b487d0a76cf/base680**, sixth method
`acknowledgingRetryLimitShowsFallbackAndResetsTheNextAttemptWithoutCompleting`
reaches the production third-failure dialog through the same individually
held calls/real Retry controls, independently of the retained missing-copy
negative. Real max dialog has no Retry/Cancel; OK surfaces the exact generic
fallback without creating another attempt or completing. Dismissing that
error retains USA selection. New explicit apply4/Retryable shows first-failure
Connection Error/original message with Retry/Cancel again, not the exhausted
title, proving acknowledgement reset the actual remembered counter. Cancel
does not complete; explicit apply5/Applied alone writes the flag/calls completion
once. Exactly five `us-ca` calls and one original manual commit.
This additional witness was retained locally until coalescing with the real
canonical repair; original published249/598 contained five methods. No new
head/CI run was made merely to repeat the known failure during routing.

Installed preflight **PASS0.51s**. Existing strict App task with:

```text
--tests com.meshcoreone.android.uitests.OnboardingRegionPresetComposeTest.acknowledgingRetryLimitShowsFallbackAndResetsTheNextAttemptWithoutCompleting
```

**1 discovered/run/PASS,0 failures/errors/skips,28s build**, actual named XML.
No other feature cohort, four-method selector or known negative was replayed.
Shared helpers extract the existing real-action prefix/followthrough without
removing the fifth test's frozen `ensureConnected`/no-raw-message assertions.
The fifth test still requires the actual WP-305 copy repair; its own failed
execution remains679-bound, and its later assertions were not retroactively
executed. The sixth proves the separate control/state path, not missing-copy
parity, source-family completion or a current six-case green result.
No production, threshold, golden, dependency or native-device change.

### Retry-exhaustion unchanged-production negative

The next genuinely uncovered branch is the third retryable preset failure,
not another four-case replay. Same reservation24 owned/modify admission is
idempotent; one method was added to the same real mounted class:
`threeRetryableFailuresShowConnectionRemedyThenFallbackAndResetTheRetryBudget`.
Actual production Flow/Region/preset ownership, US/manual/US-CA, real USA row
and five original fixture boundaries remain unchanged. Three individually
held same-ID outcomes use explicit `Retryable("radio temporarily busy")`,
the real Retry button and no false completion. First/second connection-error
dialogs expose their original message and Retry/Cancel. The third actual
Unable To Save title appears, but its required connection-remedy text does not.

At **`679c099b2af75d2c226eb675280439c2af55d665` / baseaae3**:
installed preflight **PASS0.66s**; exact existing App task:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingRegionPresetComposeTest.threeRetryableFailuresShowConnectionRemedyThenFallbackAndResetTheRetryBudget
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**1 discovered/run,0 passed,1 failure,0 errors/skips;33s build**.
Actual named JUnit XML fails at test line274:
`Please ensure your device is connected.` is not displayed.
The four earlier methods were **not** rerun or combined with this negative.
No threshold/expectation, ignored test, fake policy or production edit hides it.
`git diff --exit-code aae3b10dd78f3d3314f51291687932f42f7d0ac9 679c099b2af75d2c226eb675280439c2af55d665 -- android/feature/onboarding android/app`
**PASS** proves unchanged production. This is host software evidence, not
actual radio/Settings/firmware or native device evidence.

Frozen `RetryAlertModifier.swift@db14559b39d32322b06477c6ae676112f583db50`
uses `ensureConnected` at retryCount >=3, retaining the original failure
message only below that threshold. At679/598 `PresetStepScreen.kt` changed the
title/buttons at the limit but **unconditionally** rendered `alert.message`.
Existing WP-305 `retryableFailureOffersRetryThenFallsBackAfterThree` checks
counter/fallback state, not this mounted copy. WP-305 deviations preserve
three attempts/fallback and authorize no omission of the first remedy.
The bounded production prerequisite was **WP-305 modify
`android/feature/onboarding/src/main/kotlin/com/meshcoreone/android/feature/onboarding/ui/PresetStepScreen.kt`**,
retry-alert text branch; historical producer is PR72/issue113. It was routed
to the coordinator/primary owner, not edited by WP-502. Deduplicated template
bug [#250](https://github.com/cbattlegear/MeshCoreOne-Android/issues/250)
tracks this exact copy defect/negative, not whole-WP closure. Producer/transfer
admission was still required then; an old closed issue was not an active-owner receipt.
Earlier actual WP-303 admission rejected this foreign feature-UI modify path.
The later **user-directed routing correction** assigns existing onboarding
worker6e503330-9c0f-426b-86c3-fa52f404dcab the bounded **primary WP-305**
repair through normal WP-owned admission/real initial reservation. WP-003
support-policy work is on hold; no rule expansion/new worker is requested.
Assignment alone was not successful admission or an already-published fix.
WP-502 retained the negative pending actual producer publication. The later
canonical integration/seven-case execution above provides that receipt and
negative-to-positive evidence without a foreign reimplementation.
The test's subsequent no-Retry/Cancel, OK-to-generic-fallback, budget reset,
retained selection and final explicit success assertions were **unexecuted**
after the679/598 failure, not accepted by those runs. They later execute/pass
at canonical16a as recorded above.

The publication hold was honored until PR244 actually merged at
2026-10-20T19:41:40Z as **680246f7914a5b4e5bf5167a916b620f83a79dee**.
Main680 has exactly the e317 tree. Ordinary same-owner merge10145ab50531c1af79b2c6b9416e4bc874fa839f
integrates that landing without changing any Android bytes relative to679.
At598 relative to main only this new owned test file/three owned docs differed.
No prior execution is rebound to main680, no CI replay/bundle is created.
At598 this candidate was **not green/completed** pending the real primary
repair/result. The later16a seven-case proof above resolves this bounded
software regression only; broader WP-502/#135 remain unfinished.

### Bounded mounted Region selection and preset recovery

Same reservation24 admits one owned `OnboardingRegionPresetComposeTest`.
The initial four-method increment was local/unpublished while the coordinator
offered e317/baseaae3 for maintainer merge. That explicit publication hold was
honored until actual PR244 merge; its offered head/body were never replaced.
The newer retry-exhaustion negative above is a separate execution/blocker.
The remaining-family inventory previously had **no mounted Region selection
or preset apply assertions**: earlier WiFi/pairing methods only reached Region,
and their catalog/preset ports could not apply. Existing WP-305 coordinator
tests and WP-303 adapter tests are reused as prior art, not duplicated as policy
tests. The new host mounts actual `OnboardingFlow` from Welcome/Permissions/
Pair Continue with an explicitly already-ready connection snapshot. No pairing
call is synthesized; only real Region/preset controls drive subsequent state.

| Frozen consumer / existing primary witness | Actual mounted predicate |
| --- | --- |
| `RegionPickerRows`/`RegionSelection.afterChoosingCountry`/`afterChoosingSubdivision`, `RegionStepView` manual commit; owner `manualSelectionRequiredToCommit` | Empty/cancelled picker cannot Continue; US/California remains on choosing the same US again. Canada has no subdivision catalog and hides both state/province rows. Australia restores an empty State row, Queensland is selected, and Continue commits exactly `AU/manual/AU-QLD` once. Production catalog exposes Australia/915.800 MHz; selecting its row and a true `canApply` are required. One held `au-915` apply rejects duplicate CTA attempts through canApply recomposition; explicit Applied alone writes completion true once/calls completion once. |
| `PresetStepView.apply`, `RetryAlertModifier` Cancel/Retry below the max threshold; owner `notConnectedSurfacesErrorWithoutCompleting` and retry/apply cases | US/manual/US-CA commits once; production USA/910.525 MHz row remains selected across typed NotConnected, explicit CJK/RTL Failed, Retryable/Cancel, then new Retryable/Retry. Each held call disables duplicate CTA attempts, exact localized dialogs appear/dismiss, no failure/cancel completes onboarding, and all five apply calls retain `us-ca`. Only explicitly accepted Retry completes once. German834dp/200%. |
| Native `PresetStepCoordinator.apply` cancellation propagation and owner App adapter cancellation contract; frozen demo no-radio completion branch | Complete the held apply exceptionally with CancellationException; the actual production launch Job becomes cancelled, loading clears, selection survives, no error/Retry/completion is fabricated. A new intentional same-ID apply and explicit NoRadioToConfigure result alone complete. This is real coroutine cancellation of the consumer job, not a benign enum result or actual demo/radio execution. |
| `RegionStepView` automatic fallback, `useMyLocation` clear/spinner/error and detected commit; owner `noInternetOrTimeoutFallsSilentlyToManual`, `userRetryFailureSurfacesErrorOnlyThen`, `grantedLocationResolvesAndCommitsDetected` | First held resolver shows Resolving, null silently yields disabled empty manual picker. Explicit location retry shows Resolving before second null produces the localized failure dialog. Dismiss/retry then explicit `US/location/US-CA/los angeles` exposes detected controls; Use This Region commits the original full value once and reaches production USA/WCMesh rows without applying/completing. |

The catalog is the **actual merged ServicesRegionCatalog/RegionalAreas/
RadioPresets**, fixed to Locale.US for deterministic catalog labels, not a
decorative or manufactured fixture. Independent frozen catalog constants
`au-915/Australia/915.800`, `us-ca/USA/910.525`, US-CA, AU-QLD and Los Angeles
county drive expectations; candidate functions do not manufacture expected
IDs/selections. The German host localizes UI controls while catalog locale
is explicitly US, not full catalog-localization parity.
All four use200% font, native SDK31 graphics and actual Region/preset heading
density/height checks where reached; English360dp/German834dp at1400dp height
are sampled, not OS resize/device evidence.

Controlled ports suspend each resolver/apply until a separate explicit result.
No default success, port-internal policy, standalone test coordinator or fake
UI navigation is introduced. Exact selection writes, apply IDs/counts, held
busy controls, flag writes and completion callbacks are asserted; unrelated
pairing/WiFi/demo/settings/permission effects fail the test. In-memory flag
completion is a bounded Flow callback assertion, not physical preferences,
App gate-to-Chats navigation or complete onboarding acceptance.

At code **`da45bc8c5d4a30de0e19c1e9f9d547c28aeaf733` / baseaae3**:
installed Git-bundle/ext4 preflight **PASS0.55s**, then existing strict task:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingRegionPresetComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**4 discovered/run/passed, zero failure/error/skip,31s build**, all four named
JUnit XML identities read. Failure history is retained:5d92b9c78 passes1/
fails3 in58s because the country query also matches the editable search;
1f2890a8a passes3/fails1 in32s because reselecting a country also matches the
underlying selected row. Scoped74a23c74b passes3/fails1 in30s, exposing the
test's false assumption of a Canadian subdivision catalog. Frozen/current
RegionalAreas both explicitly have no Canadian subdivisions. Finalda45 uses
the source-faithful Canada-hidden/Australia-Queensland transition. These are
harness/input corrections, not weakened assertions or product fixes.
Those first four methods found no product defect; the later negative above
must not be confused with these passing observations. No foreign edit.
Portmap/controller validation, `git diff --check`, exact five earlier owned
test/screenshot and inherited-main owner-byte comparisons all **PASS**.

The frozen four `SettingsServiceApplyPresetTests` originals (RF/path hash/
catalog-stamp ordering/failure/firmware versions) were read but **are not**
satisfied by these controlled apply results. Resolver/geocoder algorithms,
OS location/permission lifecycle, real SettingsService/radio/firmware writes,
three-failure max/fallback copy, locale-fallback/stale-ID families, full
picker/search locale policy, process/reentry, haptics, device/golden and full
original-case/onboarding/WP-502 acceptance remain separate.
Prior Bluetooth [COMMENTED source review5590546770](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244#pullrequestreview-5590546770)
finds no bounded blocker at e317, not a review/approval of these new methods.
Actual [e317 hosted39480142662](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39480142662)
has since completed **SUCCESS/all six jobs**; it does not execute/certify this successor.
No earlier green feature/native cohort is replayed or combined into a new
execution, no CI-result bundle or new screenshot/golden is produced.

### Consolidated current-main integration after Bluetooth implementation

The Bluetooth source/evidence candidateb11 was ordinarily published before
the coordinator's consolidated-main instruction arrived. Same reservation
**23** admits all20 incoming support paths through
`merge-integration/reconcile-current-base`; merge
`0c096dcf89b5e62037d78237a3d81a4c8fb79c47` integrates actual authoritative
`aae3b10dd78f3d3314f51291687932f42f7d0ac9`, including merged247/234/245/248.
All20 inherited owner paths exactly match that main, including the WP-307
notification-source generation repair, WP-407 settings/localization support,
WP-313 remote-region semantics and WP-504 performance corrections.
No owner implementation/accounting is originated, rewritten or duplicated.
Manifest/policy are unchanged; all WP-502 UI/screenshot sources remain exactly
byte-identical tob11, including the four unchanged pairing methods and three
new Bluetooth witnesses. The merge is disjoint, without conflict resolution.
Portmap/controller validation, `git diff --check`, exact inherited-support and
owned-source preservation comparisons all **PASS** after integration.

Local7/7 below is still **codee140/base9c**, not a new-main execution, and8f5
source review/hosted results remain historical to9c. Installed exact-head
readiness and automatic hosted checks, not a replay of successful selectors,
bind the ordinary publication successor. No native/device run, manual dispatch,
copied CI-result bundle, privileged verdict or full-WP acceptance is introduced.

### Bounded mounted Bluetooth permission and blocker recovery

The existing `OnboardingPairingComposeTest` witness/fixture is extended, not
replaced by another policy-only test. Same-owner `evolve --expected-revision 22`
for this owned path/modify is idempotently admitted at **revision22**; no
reservation release/recreation, new shared capability or foreign edit.
The four incumbent method blocks remain exactly byte-identical to8f5.
Shared helpers still default to zero requests/Settings calls and now assert
all optional snapshots stay denied. Rooms/adaptive/draft/WiFi bytes are unchanged.

| Existing source/adaptation/owner witness | New actual mounted predicate |
| --- | --- |
| WP-305's documented Android just-in-time Bluetooth adaptation; `OnboardingFlowCoordinatorTest.justInTimeBluetoothPermissionThenPairResumes` | `justInTimeGrantResumesOneHeldPairAndRepeatedResultCannotStartAnother`: Welcome/optional Permissions/Pair create no request. First actual Add requests Bluetooth once, calls no pairing port and stays on Pair while result is held. The callback actually supplied by production Flow refreshes independently controlled platform facts to GRANTED, starts one held pair, and repeated callbacks while held and after accepted Region do not add attempts or Region entries. |
| `DeviceScanCoordinatorTest.bluetoothBlockersAreReportedWithoutPairing`, denied Settings remedy in `DeviceScanScreen`, frozen `DeviceScannerSheet.unauthorized` explanatory state | `deniedResultOffersSettingsAndWiFiWithoutPromptLoopAndGrantNeedsNewIntent`: first request completes DENIED, actual localized notice/Settings appear, no transport/error alert/Region/completion. Explicit Add and repeated callback cannot request again. Real WiFi sheet still opens/cancels without transport. Later GRANTED refresh alone does not revive the consumed denied intent; explicit new Add alone starts one held pair and clears the notice. German834dp/200%. |
| Same owner blocker case, frozen `DeviceScannerSheet.poweredOff`, existing Android inline Settings remedy | `bluetoothOffOffersSettingsAndWiFiThenExplicitAddRechecksTheAdapterWithoutRequestingPermission`: Bluetooth granted but adapter off blocks pairing without any permission request, exposes actual off title/message/Settings, and preserves WiFi open/cancel. Controlled adapter-on refresh alone does not pair; explicit Add rechecks/clears the blocker and starts one held pair. |

The reused fixture separates platform facts from the published permission
StateFlow: only its port `refresh()` publishes the changed facts, so pre-setting
UI state cannot stand in for executing the real Flow result callback.
Refresh counts, exact request kind/count, settings-opener calls, held pairing,
completion flag/callback and unrelated-effect absence are asserted.
Location/notifications/LAN remain denied at every checked boundary, create no
request, and do not gate pairing or WiFi availability. The WiFi check only
opens/cancels its real sheet; it does not repeat its validation/connect cohort.
Accepted pairing returns through one actual flow Back callback to Pair.

Frozen `PermissionsView`'s unconditional Continue and `DeviceScanView`'s
independent WiFi button inform consumer boundaries; Android permission timing
and inline Settings are the **existing WP-305 native adaptation**, not identical
iOS permission APIs or newly accepted frozen original cases.
App-layer `AndroidOnboardingPermissionPort` API mapping and
`AppOnboarding.rememberPermissionRequester` were read; the actual OS launcher,
request history/denial classification and lifecycle settings round-trip are
not executed by this feature-contract fixture. No system dialog/CDM/BLE,
radio, actual Settings app, scanner list, process/Activity recreation, new
screenshots/goldens or full onboarding/native/device acceptance is claimed.

At code **`e140e9fe860517256362e6e500cf57a98725e933`** on base9c, exact installed
Git-bundle/ext4 preflight **PASS0.57s** and the same declared strict selector:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.OnboardingPairingComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**7 discovered/run/passed, zero failure/error/skip,35s build**. Actual named
JUnit XML contains all four incumbent and three new methods. First execution
passes; no product discrepancy/unchanged-production negative or foreign fix
was needed. This seven-case execution at e140 does not relabel earlier579's
four-case result or create a combined other-feature cohort.
Portmap/controller validation, `git diff --check`, exact incumbent method-block
comparison and older-test/main9c support-byte comparisons all **PASS**.
Predecessor8f5 [COMMENTED source review5589919731](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244#pullrequestreview-5589919731)
finds no bounded pairing blocker, but does not review/approve this successor;
hosted verdicts remain exact-candidate bound.

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
