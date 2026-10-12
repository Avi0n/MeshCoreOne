# WP-503 attempt evidence

**Incomplete, not accepted.** Native suites execute through the existing App unit runner.
The baseline ran 24, passed 6, failed 18; later focused cohorts ran 26 (18 passed/8 failed)
and 10 (8 passed/2 failed), with zero errors/skips, at their distinct candidates below.
No screenshot golden comparison, device TalkBack observation or independent review of this
WP occurred. Bounded COMMENTED reviews of genuine producer repairs are recorded below.

## Latest bounded executable results

All commands below use the installed serialized Git-bundle/ext4 adapter, actual pinned
environment preflight and credential-stripped candidate environment. They invoke the existing
App task, not new acceptance tasks. Common exact options:
`--console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`.
Native graphics, SDK31/37; owned audits use real Resources font2/RTL. The producer-owned
keyboard cohort's different native window/default font is explicitly recorded below.
No device/TalkBack claim.

| Actual committed inputs / command (plus common options) | Discovered / result |
| --- | --- |
| Product06fceec24f920e87a4a267a55499236ca3c25959 + audit39fcaf873b53616720545f36f4121bb1e9037442; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit*` | Failed before native discovery. Localization converter74 cases/one error: trusted portmap rejects mixed AndroidOnly/PortedFrom dispositions on AppChatsFeature. Original producer corrected headers; no task bypass/zero-test credit. |
| Productd2b64b584892f0b63545f0e02c9c9e119b3d8b06 + same audit39fc; same contrast/direct/guard command | **8/8 passed**,0 failures/errors/skips; exactly760 painted records/0 below floors/min4.517812074094317. High-contrast identity38 records/0fail/min7.027937730337613. |
| Producte18ab1dac6186dfcdbef9debd0b2990e84797b4f + audit39fc/checked required-onLink patch; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.room* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.remember*` | 14 discovered,12 passed,2 failed,0 errors/skips. Author/status/link callback/Remember/no-sent-retry pass. Only cleared-child Retry displayed-text oracle fails; diagnosed against actual consumer and frozen custom-action/pointer-button contract, not called a product defect. |
| Same producte18 + audit80376e8f3e3d0adda276a78f791a9c2085b606dd; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.roomFailed*` | Test-only compile failure: missing installed `click` extension import.0 discovery rejected; corrected at01f430710. |
| Same producte18 + audit01f43071015d433ae2e54f66e3a63d7a922a0291; same roomFailed command | **2/2 passed**,0 failures/errors/skips. Exact named custom action and real pointer click independently invoke correct message ID. Effective pointer target86x54dp, bounds(16,196)-(102,250), fully within row(16,115)-(344,250). Body138x33px has nonempty realfont2/RTL lines/zero clipping. |
| Same producte18 + same audit01f430/checked required-onLink patch; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest` | **22/22 passed**,0 failures/errors/skips. All six frozen room distinctions/Remember/direct retry/live-region controls and all38 contrast frames. Exactly760 measured painted spans/0 below unchanged4.5/7 floors/min4.517812074094317, not a sum of earlier candidate results. |
| OWN83455603c14f95aa758e1a0bbea5c3dfc933a985; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest` | 6 discovered,2 passed,4 failed,0 errors/skips. Connecting text/disabled duplicate-connect/cancel passes. Same-copy Continue selector is ambiguous; direct initial-root focus assertion fails before editor flow. No product findings inferred. |
| OWN69819afacfbce7fee5abefd042287b43b54852b4; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.denied* --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.wifiNative*` | 4 discovered/4 failed,0 errors/skips. Separate request/Continue callbacks and54dp targets pass; entire638px paragraph cannot fit in561px card viewport. Correct oracle must check actual scroll-reachable lines. Additional root focus notification does not resolve initial attachment timing. Neither is filed as a product defect. |
| OWNdea8d072ff7498e70450f47b21015910b2dd5dac; same denied/wifiNative selectors plus `--tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.wifiInitial*` | **6/6 passed**,0 failures/errors/skips. Both APIs perform every one of40 permission lines via actual native ScrollBy; exact request/settings/Continue callbacks, native editor focus/Next/Done, actual coordinator failure/retry/cancel and controlled framework/WiFi autofocus pass. |
| OWN4d86cc00b6eaecdc7eb043d8b357557523a5121b after actual main663 integration; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest` | **8/8 passed**,0 failures/errors/skips. Full onboarding class, including actual pending-connect textual progress/disabled actions, in one actual adopted-main execution. |
| OWN33f9dea0d2a56c30e23505caa8b25e4e65fedc48; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest.disconnectedCli* --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest.disconnectedRx*` | 4 discovered/run,2 passed/2 failed,0 errors/skips. Actual Rx offline/recovery/filter selection/48dp/font checks pass on both APIs. Both CLI cases fail source description visibility: one enabled editable input, no localized disconnected explanation. Assigned defect220; unchanged normative regression retained. |
| Actual producer03753f129984598436b57218ee7f4b82ab99395e, its own existing test source/no owned fixture or App registration; `:app:testDebugUnitTest --tests com.meshcoreone.android.wp308diagnostic.HardwareReturnDiagnosticTest` | **8/8 passed**,0 failures/errors/skips. Actual1080x1000dp/default-font native window. Four complete methods cover five disabled-send gates, Enter/NumPadEnter, KeyDown-only valid send, connected/disconnected Shift newline and both Unicode selection directions/caret. Synthetic native keys/callbacks, not physical keyboard/IME/queue delivery or font200. |

The room oracle now preserves **both** the source named accessibility action and the actual
native pointer button. Cleared child speech is not native visibility. It measures the
real button and injects a real pointer click rather than substituting a generic row action.
The six frozen distinctions and all thresholds remain; producer bodies are not edited.
The required new room callback is still applied only to owned snapshot fixtures while
that product is unadopted. The actual Room/App wiring comes from the producer candidate.

Onboarding assertions mount real public content and the real WiFiConnectionCoordinator
with explicit pending/failure ports; no claimed successful transport or platform permission
dialog. Paragraph reachability checks all80 actual lines over both APIs with zero line
clipping, not simultaneous paragraph fit. Native-control and then WiFi subtree autofocus
both report focused=true; initial direct-root attachment timing remains a qualification,
not a product fix or a physical keyboard/IME observation.

Both bounded producer reviews were posted as **COMMENTED**, not APPROVED/protected gates:
[PR209 review5494169311](https://github.com/cbattlegear/MeshCoreOne-Android/pull/209#pullrequestreview-5494169311)
at exacte18 source/native22-case result, and
[PR213 review5494169481](https://github.com/cbattlegear/MeshCoreOne-Android/pull/213#pullrequestreview-5494169481)
at exactd2 painted8-case result.
They explicitly exclude business queue-event order, whole-WP and physical/device acceptance.

The current keyboard review at exact037 records only the independently executed native
eight-case result; the earlier d2 painted result is not transferred to037.
Assigned [issue220](https://github.com/cbattlegear/MeshCoreOne-Android/issues/220) contains
the real CLI head/consumer/source/measurements and exact failed command. Production stays
with the existing WP-316 coordinator; the regression is neither waived nor expected-passing.

Actual reviewed main663fa73fab99356f7097534288e820a126890195 is integrated at
OWN4d86cc00b6eaecdc7eb043d8b357557523a5121b. The single disjoint App build conflict preserves
both audit source-set registration and all main WP-504 release/R8/benchmark/profile settings;
relative to main the App diff is only the two-line audit registration.

Authoritative historical OWN0e44dcfc34961737d18fe80f10c131d71c1a973e hosted
[run38282515710](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38282515710)
**FAILED**:720 App tests discovered/12 failed against legacy room/contrast production.
The emitted outgoing source-delivered label and incoming-author assertions fail, not compile.
No suppressed-looper warning is assumed to be the cause. Those assertions pass unchanged
on the genuinely integrated producer e18 above. Codec oracle succeeded; restore skipped.
The exact hosted log is authoritative; no second CI-result bundle is retained.

## Identity and revisions

| Field | Actual value |
| --- | --- |
| Repository / issue | `cbattlegear/MeshCoreOne-Android` / [#136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136), open and assigned to `cbattlegear` |
| WP / profile | `WP-503` / `design-system-engineer` |
| Workspace creation handle / CLI session | `1666502a-0420-46ef-930f-e78651ba8b9f` / `d2980f28-620c-408b-9947-067e2da8eedf` |
| Managed branch | `cbattlegear-issue-136-wp-503-accessibility-audit-807238` |
| Inspected base/head before audit documentation | `d958471ab4835813881a5e7af36e8249a8b273a4` |
| Documentation candidate head | The Git commit containing this document; exact published head belongs in PR/issue handoff, not a self-referential file hash |
| Frozen source | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest SHA-256 | `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda` |
| Policy revision | `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20` |
| Typed reservation | Same canonical attempt, revision 3; `wp-owned` create/modify on the four WP-503 roots plus `dependency-resolution-metadata` / `update-directly-required-build-wiring` on `android/app/build.gradle.kts`; no release/recreate |

The advisory receipt correctly preserves canonical project handle
`1666502a-0420-46ef-930f-e78651ba8b9f` as `session_id`. The coordinator independently
resolved BOTH it and CLI/runtime alias `d2980f28-620c-408b-9947-067e2da8eedf` to this same
worker, branch/worktree, creation timestamp and PR205. The earlier hypothetical identity
mismatch is withdrawn; no guard requiring replacement was encountered. No second claim,
immutable identity replacement, release/recreate or manual ledger edit occurred.
Issue assignment and PR205 are independently observable GitHub facts, not invented ledger
bindings. No hosted publisher task or acceptance receipt is claimed.

WP-503 depends only on WP-407. GitHub reports PR164 merged on `2026-10-11T16:22:30Z`
as `250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, and that commit is an ancestor of the
inspected head. The coordinator additionally identified WP-407's
`advisory-terminal-merged` receipt: session `524fad60`, PR164, head
`e9f0febc7e53b09adb00ac56928e7073366023f6`, merge as above. Lease/completion fields are
not that terminal receipt and are not a pre-edit blocker. No device/source acceptance is
inferred from it, and no ledger was manually repaired.
WP-501 is not a dependency and its existing repair/parity surfaces were not edited.

The only reference-tree differences reported by Git are the two test-only paths enumerated
in `reference-amendments.json` (correlation serializer ordering and advertisement waits).
The source pin is not advanced. Manifest validation passes against those explicit exceptions.

## Actual commands and outcomes

Commands ran from this worktree on Windows on 2026-10-12.

| Exact command | Result / discovery | Claim boundary |
| --- | --- | --- |
| `gh issue view 136 --repo cbattlegear/MeshCoreOne-Android --json number,title,body,comments,assignees,labels,state,url` | Passed; original issue had no assignees and no comments | Required assignment context, not verification |
| `gh issue edit 136 --repo cbattlegear/MeshCoreOne-Android --add-assignee cbattlegear` | Passed; subsequent view confirmed assignee | Account owns tracking; dedicated worker identified in comment |
| `gh pr view 164 --repo cbattlegear/MeshCoreOne-Android --json state,mergedAt,mergeCommit,headRefOid,url` | Passed; state MERGED, exact merge recorded above | Not a WP acceptance attestation |
| `git merge-base --is-ancestor 250dd50ed259c5d9fe1006d4f45a637e4aea0aff HEAD` | Exit 0 | Actual merge ancestry only |
| `python .\tools\android-port\controller\ci.py preflight` | Exit 2, BLOCKED: `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness` | Initial invocation lacked explicit state; not proof of absent tools |
| `python .\tools\android-port\controller\ci.py run --stage prepare` | Exit 2, same explicit-state blocker | No strict dependency preparation occurred |
| `python .\tools\android-port\controller\validate.py` | Exit 0 | Manifest/source/ownership validation, not feature acceptance |
| `python .\tools\android-port\portmap.py` | Exit 0 | Traceability only |
| `python .\tools\android-port\theme_convert.py --check --self-test` | Exit 0; discovered/run/passed 22, failed/error/skipped 0; 10 themes, 44 color assets, 89 primary inputs, 23 generated outputs, 12 recovery locales | Python frozen converter/resource checks, **not Kotlin contrast or rendered feature tests** |

No test counts are inferred from filenames, source declaration counts or historical results.
Reproducible hosted checks remain authoritative at their exact candidate commits; no second
CI-result bundle is persisted here.

## Follow-up readiness and admission investigation

The initial missing-state errors above do not prove no toolchain is installed. A follow-up
WSL probe with `bash -lc` confirmed both the installed local adapter's declared
`$HOME/meshcoreone-work/toolchain/environment.json` and pinned Python executable exist.
An earlier quoted-variable probe was inconclusive and is not evidence of absent tools.

The documented `ci.py preflight --state <installed environment.json> --output <private
WP-503 directory> --local`, invoked through the installed Python in WSL, exits 2:
`Git command failed: rev-parse HEAD`. A direct `git rev-parse HEAD` in that WSL-mounted
worktree explains the failure: its Windows `.git` pointer is not a Linux Git path.
No `.git` pointer, shared checkout or global Git configuration was rewritten. The existing
Git-bundle/ext4 snapshot adapter is the appropriate execution route; its optional full
pre-push attempt previously remained queued on the shared Gradle lane and did not run.
This is not an absent-SDK claim or a native-test failure.

The trusted admission helper also confirms `android/app/build.gradle.kts` is admitted for
`update-directly-required-build-wiring` (`dependency-resolution-metadata`) and
`update-build-or-manifest` (`app-build-launcher`). A generic `modify` probe fails because
it is not the typed support operation. Therefore App runner support is **not** an unknown
path capability, and does not require new authorization prose. Same-attempt CAS evolution
revision 2 -> 3 admitted additive registration before the App edit.

The hosted result for initial documentation head
`ea4300e0e457dd9ad63767c244d908722f1e6984` is
[run38261041154](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38261041154):
scope/controller/android-ci succeeded; Linux native build and both Swift oracle jobs were
skipped for the classified documentation change. These are not native accessibility passes.
Only that hosted result/log is referenced; no CI bundle is retained.

## Acceptance and resumption gates

| Acceptance | Current evidence | Remaining requirement |
| --- | --- | --- |
| `WP-503-behavior` | Actual feature-consumer source review; four WP-310 findings; reusable tests identified | Native target/contrast/font/semantics measurements across actual flows and device focus/announcements |
| `WP-503-boundaries` | Textual alternatives and missing message semantics documented; no golden changes | Data-bound graph/status assertions, approved deterministic screen evidence and distinct device observations |
| `WP-503-source-test-parity` | All four WP-503 original test cross-references read, 23 source declarations inventoried | Executed acceptance, not a duplicate of WP-501's parity accounting |

## Actual native execution

The session-local targeted launcher uses the installed `local/run.sh` serialized
bundle/ext4 snapshot, pinned state and `candidate_environment`, then invokes existing
Gradle tasks. It is not a new canonical manifest runner. The installed adapter does not
reject the verified project-handle/CLI alias; no identity guard blocked execution.

| Exact task / candidate | Actual result |
| --- | --- |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.* --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `b42c9a41e8ccc62b3c82f255dca7dc7065a2c472` | Native preflight succeeded; compilation failed on test-only `CustomActions` key location; zero discovery explicitly rejected, not success. Fixed to `SemanticsActions.CustomActions`. |
| Same task/options, `86993ee65ca43fb5f7da26f537b3857e1cd4c1a9` | Compiled; discovered/run 24, passed 6, failed 18, errors/skips 0, SDK31/37. Passing families: disconnected diagnostics explanation, sent-room no retry, mixed CJK/Hebrew direct retry/font/targets. |
| Same task/options, `4f8b6a5d324d2d13dcbbce2502c200992c7e325a` | Discovered/run 24, passed 6, failed 18, errors/skips 0. Added paragraph/viewport diagnostics; partial grid-target artifact is not promoted to an intrinsic control defect. |
| `:core:designsystem:testDebugUnitTest :app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.roomFollowUp* --tests com.meshcoreone.android.core.designsystem.ThemeContrastTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `22309ff1eb9a1ccddecdd3375ec2de1aee77a812` | Discovered/run 26, passed 18, failed 8, errors/skips 0. Existing theme-role families 10/10 and actual-timeline guard controls 4/4 pass. Remaining failures: legacy room follow-up author, painted-span contrast, appearance allocation flags and populated diagnostics. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeFontAuditRegressionTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `1fad28d89f750675285c354c28f91239bc04890e` | Discovered/run 10, passed 8, failed 2, errors/skips 0. Appearance 2/2, font regressions 4/4 and disconnected diagnostics 2/2 pass; populated diagnostics 0/2. |
| Same focused task/options, `abc86cb0dfd885d4dabd14a07f637ea031ad9b29` | Same 10 run/8 passed/2 failed, zero errors/skips. Rejects empty line measurements and records all three graph widgets' zero heights before failing. |
| Same focused task/options, `d825ea3d2ec82a270012890767097c77a3aa95c9` | Same 10 run/8 passed/2 failed, zero errors/skips. Compiles native scroll-reachability support; current non-scrolling product still has all three numeric widgets at zero height. No normative failure reclassified. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeShellAccessibilityTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `8f2c73d0c25afa383af229809ecef9b3b18c7a12` | Discovered/run/passed 4, failures/errors/skips 0. Actual compact 360dp and expanded 1080dp native shell, Resources font 2.0/RTL, five localized Tab roles/selection/heading/unread, effective targets, tab-stack draft retention and real Activity Back. Content is an explicit shell-only editor focus probe, not feature-content acceptance or TalkBack traversal. Initial a1db797 test compile failed on missing assertion import/chained Unit; zero discovery was rejected and fixed before this run. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeNodesAccessibilityTest` with the same strict options; `da40e8bed9951119ba70dbd686bdd57d411e5359` | Discovered/run/passed 4, failures/errors/skips 0, SDK31/37. Actual non-null-dependency `NodesEntry` loads a cached CJK node while disconnected, exposes selected Contacts/textual blocked status, measures real font 2.0/RTL/targets and invokes the correct contact route. Actual Sync action shows localized recovery dialog; scaled text/dismissal retains cached content. Read-only deterministic store; unexpected transports/mutations/codecs fail explicitly, not successful stand-ins. No physical announcement, all-name-length or full Nodes acceptance. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest` with the same strict options; OWN merged-input candidate `1f341947246b0a67f7abd0185ca9cabf91a5afa4` | Discovered/run/passed 6, failures/errors/skips 0. Actual main `b621ed186b32f024dc4c7ddbdc60d18a449343f9` (PR216 merge) adopted through a normal Git merge; affected 600/900dp/disconnected native cases rerun on this branch, not merely producer snapshot proof. |

## Independent genuine-product candidate re-audits

The existing serialized managed snapshot mounts committed owned audit inputs against each
exact producer commit, importing both revisions. Only WP-owned audit sources and already
admitted additive App test registration are applied in that disposable snapshot. All producer
build additions are preserved; no product byte is edited, invented, reverted or merged here.
These are actual local native candidate executions, not borrowed producer results, hosted
acceptance, review approval or passes of this branch's unchanged baseline product.

All commands below use the same declared App task options shown above.

| Exact task selectors / product and audit inputs | Actual result |
| --- | --- |
| `--tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest`; product `29c9b530d9da9312dc1306c3d84c8d783cf4fc05`, owned audit `fff04369c106fcda15372297eec6f72c4739bfd4` | Discovered/run/passed 4, failures/errors/skips 0, SDK31/37. Minimum/Maximum/Average become 253x42/260x42/259x42 with contained nonempty lines; summary/count/current data and disconnected explanation pass at Resources font 2.0/RTL. At 900dp metrics fit; this first result alone does not prove offscreen scroll movement. |
| Same selector/product; owned audit `8f2c73d0c25afa383af229809ecef9b3b18c7a12` | Discovered/run/passed 6, failures/errors/skips 0. Adds a real 360x600dp case: all three widgets initially have zero visible bounds but actual height 42; native `performScrollTo` moves Minimum y611 -> 558, then Maximum/Average y600 -> 558. Each complete text box becomes visible with bottom 600, zero-tolerance line bounds, no truncation/ellipsis. Actual scroll movement is asserted, not simultaneous fit. |
| `--tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit*`; product `149581edb8067126d2845f971c3854fe89cba2c1`, owned audit `39fcaf873b53616720545f36f4121bb1e9037442` | Discovered/run/passed 8, failures/errors/skips 0, SDK31/37. Unchanged painted-feature assertions produce exactly 760 records, **0 failing records**, minimum **4.517812074094317:1**, Marine/light/high-contrast/outgoing hashtag (4.5 floor). All high-contrast identity floors remain 7. Actual timeline guard controls and mixed-direction retry/font/targets also pass. |
| `--tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.room* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.remember*`; product `83e9579176b1930c3e682d6f080e8598a3a1b608`, owned audit `39fcaf873b53616720545f36f4121bb1e9037442` plus its checked `fixtures/room-link-api.patch` | **FAILED before discovery**, not zero-test success: production `RoomConversationScreen.kt:272:21`, `No parameter with name 'backgroundColor' found`. Actual contextual room caller depends on WP-308's genuine MessageBody API absent from this standalone product commit. Serialized producer/coordinator integration is required; no room acceptance claimed. |

The committed room fixture patch changes only owned audit calls to the genuine required
`onLink` API using named arguments and verifies a native custom action invokes the exact
URL callback. It preserves all six room distinctions and the associated switch test. It is
snapshot-only until actual compatible product bytes are adopted on this branch, then should
be applied to the owned fixture and removed. No compatibility overload, fake production
wiring or old compilation against new APIs is asserted.

The room executable build overlap is handed off in
[comment6128524649](https://github.com/cbattlegear/MeshCoreOne-Android/issues/207#issuecomment-6128524649).
Actual graph movement is handed off in
[comment6128449712](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214#issuecomment-6128449712).

PR216/#214 is now genuinely merged as main `b621ed186b32f024dc4c7ddbdc60d18a449343f9`
(parents d958/29c9), not inferred from closure. Bounded independent source/native review
`5493735210` is **COMMENTED**, not a fabricated protected approval. Actual two-file diff
and frozen NoiseFloorView were read; no substantive remaining defect in this bounded
numeric-reachability repair was found. Authoritative hosted
[run38277362005](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38277362005)
is successful at exact29c9. Historical negative rows remain; #214 is retired as an active
defect. Its device qualification is not relabeled as device/full-WP evidence.

New genuine published room `9892e7c7f0d6a0466dec2abf84e87a34adef57ea` was independently
retried with the checked owned required-onLink fixture. It **still failed** production
compilation on the missing backgroundColor parameter, with zero discovery rejected.
This is actual new-head execution, not transfer of the earlier83 failure.

New genuine composer `948951f27a95149136c221e0a94ac6a698219683` was independently retried
with the same unchanged contrast/direct/guard selectors. It **failed before discovery**:
`AppChatsFeature.kt:232` unresolved `resendChannel`; line341 anonymous adapter lacks required
abstract members; lines347/349/351/358/359/361 old pending/send/resend direct/channel
overrides no longer match the queue-acceptance API. Real producer/App integration is
required and reported in #208. The earlier149 8/8/760 pass does not apply to948.
No product substitutes or duplicate edits were made.

Hosted [run38276221835](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38276221835)
at exact published `fff04369c106fcda15372297eec6f72c4739bfd4` **FAILED**:
App reported 710 tests completed, 14 failed, including normative legacy room/contrast
assertions. Swift codec oracle succeeded; restore oracle was skipped. This is the hosted
result, not an iOS/device/whole-WP pass, and is not transferred to later audit/product inputs.
No duplicate CI-result bundle is retained.

Both executing candidates measured **38 effective frames x 2 directions x 5 emitted ranges
x 2 SDKs = 760** actual foreground/background records. **162 fail** unchanged thresholds,
covering **81 distinct parameter segments**, including **22** high-contrast identity
records below 7:1. Lowest ratio: **1.0435430264922168:1**, Fern/dark/standard/outgoing
hashtag. Default/light/standard/outgoing hashtag: **1.285454842066801:1**. Each background
comes from native window pixels and is verified against the actual emitted bubble role;
effective overlapping base/link/span colors and mention fills come from `TextLayoutResult`.
No source-palette proxy or sampled text foreground is substituted.

Enabled targets on passing flows measured at least 48dp; observed examples include
70x48, 114x48, 165x48, 86x54 and 110x54dp. This is not all-feature target acceptance.
Real Resources font scale 2.0 was asserted; mixed-direction direct text was unclipped.
Short-label width-overflow flags were disproved as clipping: e.g. Default's native widget is
84x30 but paragraph allocation 312x30; actual line left/right 0..84, top/bottom 0..30 and
896 non-background pixels. All 20 theme/API labels have visible ink and contained lines.
The oracle now requires nonempty lines, no truncation/ellipsis and zero-tolerance actual
line bounds. SDK31/37 regression controls demonstrate valid shrink-wrap and reject genuine
32x4dp clipping; no threshold/golden change or font product finding.

Populated noise-floor Minimum/Maximum/Average widgets are 253x0/260x0/259x0, with zero
visible bounds on both SDKs inside a real 360x900 viewport. Count and all three summary
values/current reading are data-bound and pass. Unlike the font artifact, the actual
widgets have height zero. Source's unweighted/non-scrolling Column and fillMaxSize chart
consume the available height before statistics. Assigned
[#214](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214) is claimed in the existing
coordinator's WP-316 repair scope; no production edits or device observation here. A legitimate native scroll ancestor
may reveal metrics for measurement; all metrics need not fit simultaneously.

The confirmed contrast finding was filed/assigned as
[#208](https://github.com/cbattlegear/MeshCoreOne-Android/issues/208) at coordinator direction.
Four room findings remain assigned to #207. No product or WP-501 binding edit occurred.

The coordinator identified a test assertion weakness: checking only the artificial
`audit-root` does not prove actual timelines lack a broad live region. It is corrected in
owned tests to inspect actual ancestors/subtrees of two real rows. A negative control puts
a live region on the real `ChatTimeline`; a positive control permits a small failure-only
region. These are harness regressions, not product bugs or expected-passing room defects.
Actual guard controls and source/Material-role families passed at exact22309ff1e above.
Corrected all-theme name/font/target enumeration passed at exactabc86cb0d. Unchanged
normative room cases await genuinely repaired bytes, including their new required link API;
these passing selected cohorts do not turn legacy room/contrast failures into success.

Hosted [run38264623000](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38264623000)
for exact b42c9a41e failed at that test compilation error. Its authoritative result is not
transferred to later repaired heads. No automated result/JUnit/image bundle is committed,
uploaded, hashed or revalidated here.

**Identity blocker withdrawn:** the coordinator verified the valid alias relationship.
Implementation resumed with canonical identity/history unchanged. A session-local targeted
launcher uses the existing `local/run.sh` bundle/ext4 snapshot adapter, shared Gradle lock,
installed state and `candidate_environment` credential stripping. It invokes the declared
`:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` task with strict
dependency verification. It checks actual JUnit discovery and rejects missing/zero/skipped
tests. It is local diagnostic execution, not a manifest acceptance runner or hosted CI result.
An initial launcher call exposed CRLF in the mounted shell script; the adapter is copied
with LF into the private control directory, just as `check.py` already does. Product/tool
source files were not changed to work around host paths.

Separately admit WP-503's real runner/output acceptance contract: its manifest verification
still has `configured=false` and no commands. Existing App task registration executes owned
sources without claiming that the manifest acceptance contract has become configured.
No Gradle task was invented.

The coordinator routed A503-01 through A503-04 to the dedicated WP-310 producer in issue
#207, worker `5ca13f75-8466-4dd5-b630-02b30841a5d0`. Re-audit actual repaired bytes once
published/merged. There is no protected product decision requesting new colors or
relaxed thresholds. No nested worker, signing/release action or repository setting was changed.

The audit and execution contract are in [accessibility documentation](../../accessibility/README.md).
The earlier hypothetical ownership/execution blocker was posted to
[issue136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6125830322).
It is withdrawn; the resumed measured handoff is
[comment6126652467](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6126652467).
The native cross-session messaging tool is not exposed in this worker; this issue comment
and the draft PR are the persistent coordinator handoff, not a fabricated message receipt.
