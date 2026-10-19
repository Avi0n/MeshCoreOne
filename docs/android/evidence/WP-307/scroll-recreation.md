# WP-307 scroll restoration evidence

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / owner: `WP-307` / `chats-ui-engineer`
- Issue: #230, preserve cached chat scroll across native window recreation
- Current base: `574d440b5b05ba6609f60d7ba0913c2d8bdaea0d`
- Original implementation base: `d8181418614a5acaa46f4acde54b741b492ac4b0`
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`
- Manifest SHA-256: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`
- Acceptance: `WP-307-behavior`, `WP-307-boundaries`
- Scope: `ChatTimeline.kt`, `TimelineStateTest.kt`, mounted App
  `ChatTimelineRestorationTest.kt`, this evidence and the WP-307 deviation

## Current-main reconciliation

The feature branch was reconciled with authoritative `main`
`574d440b5b05ba6609f60d7ba0913c2d8bdaea0d` by merge commit
`10b66790659c1c710b4e712aff2c54470b6467a6` (first parent: prior WP-307
candidate `2b6159726223c69708522247db7a6eb6850c0f1b`; second parent: exact
current main). The typed WP-307 reservation evolved to revision 12 with
`reconcile-current-base` under the trusted `merge-integration` capability.
The deterministic merge preview had no conflicts. It incorporated the
disjoint current-main WP-003 controller/review infrastructure without
rewriting or modifying WP-307's product and test files.

Verified at the reconciled tree:

- `ChatTimeline.kt`: `3b10d361363e08af68225c9fba12e378c3eaf725`
- `TimelineStateTest.kt`: `00e0f7de91641e209d9e60febf98afb6b8f68cb4`
- `ChatTimelineRestorationTest.kt`: `187e1a7e6fcd43c2494e0b036706d5f25cd9015f`

The frozen source remains `db14559b39d32322b06477c6ae676112f583db50`;
manifest SHA-256 remains
`39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
Current automation-policy revision after the main integration is
`c1e87cc1e69400e25d035b40349ec62cf3bd14143ce0bb17932e04d46e9dadb0`.
This is source/base reconciliation only: no device/native execution was
replayed or inferred from the merge.

## Change and behavior

When timeline state is temporarily empty during Activity recreation, positional
LazyList restoration can be clamped before rows reload. The subsequent default
`Latest` anchor previously forced index zero. The timeline now saves the visible
message UUID and scroll offset using Compose saved state and resolves that stable
message in the reloaded reverse-layout rows. Fresh latest initialization remains
index zero. An explicit message anchor (including unread/notification targets)
continues to override the saved viewport.

## Verification

```powershell
.\android\gradlew.bat -p .\android :feature:chats:testDebugUnitTest --tests 'com.meshcoreone.android.feature.chats.timeline.*' --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m
```

**PASS:** JUnit XML discovered 31 tests; 31 passed, 0 failed, 0 errors,
0 skipped. This includes three new resolver cases for restored middle-message
identity/offset, fresh latest, and explicit message-anchor priority.

```powershell
.\android\gradlew.bat -p .\android :feature:chats:lintDebug --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2
```

**PASS:** chats lint task completed with 768 MiB heap/metaspace after a first
attempt exhausted the default lint metaspace. The retry did not change product
configuration.

The successful lint invocation set this private process option before running
the task:

```powershell
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xms64m -Xmx768m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
```

```powershell
python tools\android-port\controller\validate.py
```

**PASS:** manifest and ownership validator; 1,866 frozen reference files,
64 work packages, 178 dependency edges; manifest SHA-256 as above.

## Exact-offset capture review follow-up

Review follow-up base: `bdc5eeec4261f851af83ab40f4df31865668df6b`.
The earlier resolver tests and combined native identity regression do **not**
prove capture of an offset-only scroll. Observing only visible indices lets
`snapshotFlow` suppress an equal list after such a scroll. The flow now observes
visible indices, first-visible index and pixel offset together, and derives the
stable message anchor from that captured index/offset. Paging still triggers
only when visible indices change, so offset-only emissions do not duplicate an
older-page request. Latest detection and explicit-anchor resolution are unchanged.

`ChatTimelineRestorationTest.pixelOnlyScrollSurvivesSaveAndRecreationWithAnInitiallyEmptyTimeline`
mounts the actual timeline and moves a middle message from 12px to 24px,
asserting the entire visible-index list stays unchanged. It saves the real
Compose registry, disposes/remounts at the same selected-conversation key with
a fresh **non-saveable** `LazyListState`, first presents empty/loading state,
then reloads rows. Assertions verify the same message and exactly 24px,
not the stale 12px. It also checks older-page request count stays one during
another offset-only scroll near the oldest rows and that returning to index
zero reports latest. No radio/session/send service is constructed.
App `key(selected)` was read and is preserved; no navigation code changed.

The serialized pinned Linux snapshot used the exact following implementation
and test Git blobs, also matched against this Windows worktree:

- `ChatTimeline.kt`: `3b10d361363e08af68225c9fba12e378c3eaf725`
- `ChatTimelineRestorationTest.kt`: `187e1a7e6fcd43c2494e0b036706d5f25cd9015f`

The Windows scaffold readiness check still exits 2 for missing
`build-tools/37.0.0/package.xml`. Direct WSL execution against the Windows
worktree also failed frozen-input verification because its `.git` references a
Windows Git-common path. Neither was bypassed. A session-owned managed Linux
snapshot imported the existing HEAD through a local Git transport bundle and
overlaid only the two admitted Kotlin files; its pinned readiness check passed
(Python 3.12.4, JDK 21.0.12.1, SDK 37.2, Build Tools 37.0.0). No SDK metadata,
license, dependency or repository configuration was changed.

The wrapper JAR/distribution pins were checked before invoking the same
declared Gradle tasks through `GradleWrapperMain`. Environment inputs came from
the existing allowlisted `candidate_environment(..., local=True)` helper.
The exact entrypoint/arguments below use `$repo` only to abbreviate the
session-owned snapshot path:

```bash
repo="$HOME/meshcoreone-work/local-checks/repos/wp307-offset-a5186fbf"
wrapper=("$HOME/meshcoreone-work/toolchain/jdk/bin/java" -Xmx128m
  "-Dorg.gradle.jvmargs=-Xms64m -Xmx1024m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
  -cp "$repo/android/gradle/wrapper/gradle-wrapper.jar"
  org.gradle.wrapper.GradleWrapperMain -p "$repo/android")
options=(--console=plain --dependency-verification strict --build-cache
  --no-parallel --max-workers=2 --no-daemon
  -Pkotlin.compiler.execution.strategy=in-process -PscaffoldTestHeap=512m
  --project-cache-dir "$HOME/meshcoreone-work/local-checks/wp307-offset-private/project-cache")

"${wrapper[@]}" :app:testDebugUnitTest \
  --tests com.meshcoreone.android.app.platform.ChatTimelineRestorationTest --rerun \
  :feature:chats:testDebugUnitTest \
  --tests 'com.meshcoreone.android.feature.chats.timeline.*' --rerun "${options[@]}"

"${wrapper[@]}" :feature:chats:lintDebug "${options[@]}"
```

**PASS:** selected App runner discovered/executed 1 test, 1 passed; focused
timeline runner discovered/executed 31 tests (23 ViewModel, 8 state/resolver),
31 passed. Both have 0 failures/errors/skips in inspected JUnit XML.
`--rerun` applies only to the two selected test tasks, not all build tasks.
The mounted test is **Robolectric SDK31**, not API31/API37 device acceptance.
**PASS:** chats lint, 0 errors and the same 3 existing WP-306
`LocalContextResourcesRead` warnings. Results are generated under the normal
module `build/test-results/testDebugUnitTest` and `build/reports` directories;
no report/log/result bundle is committed or uploaded.

**Negative witness:** in the owned snapshot only, use `ChatTimeline.kt` from
the follow-up base (unchanged index-only observation) with this same mounted
test, and run:

```bash
"${wrapper[@]}" :app:testDebugUnitTest \
  --tests com.meshcoreone.android.app.platform.ChatTimelineRestorationTest "${options[@]}"
```

The runner discovered/executed 1 test and failed its **post-restoration offset**
assertion: expected 24, actual 12 (0 errors/skips). Restored message/index
assertions had passed. Restoring the fixed production blob and forcing both
selected test tasks produced the positive counts above. This proves the
mounted regression detects the actual capture gap, rather than supplying the
desired offset manually or relying on `LazyListState.Saver`.

`python tools\android-port\controller\validate.py` and `git diff --check` also
pass for this follow-up. The older WP-503 device record below binds producer
`2396ac64`, not this offset-capture follow-up. The later, bounded combined API31
result is documented immediately below; it is not current-base or
standalone-producer execution.

### Initial WP-503 API31 attempt: inconsistent geometry observer

WP-503 initially imported the exact product producer
`4b6629ac2bf215577ec1522530a77de61ed577fe` at integration/execution
`ba67b894766137a996cfc8c4e5e17a61e7d73533`. Its API31 AndroidJUnitRunner
cohort discovered 23 tests: 21 passed and 2 failed. The chat fixture stopped at
`NativeLauncherFocusAccessibilityTest.kt:425`: expected `Cached history 15` as
the bottommost visible message but observed `Cached history 13`.

The assertion sequence at the bound source first checks that the target's
measured within-row offset is 12px (line 424), then independently checks that
the target is the bottommost visible message (line 425). Thus the reported run
reaches the initial 12px condition but stops before the subsequent offset-only
scroll, unchanged-visible-set assertion, Activity recreation and restored
24px assertion. It was not a result for offset-only capture or restoration.

### WP-503 corrected-observer follow-up: combined API31 offset pass

The existing WP-503 owner then ran only the two affected native methods with
the same product at combined integration
`e8fa6b2994177e09931925800ed7696222a65af7`, Android tree
`654b489482ac6ec3833650cdd9bd4a6be759f78a`. The retained binding names the
previous terminal-23 source `ba67b894766137a996cfc8c4e5e17a61e7d73533`
and lists WP-307 production `ChatTimeline.kt` blob
`3b10d361363e08af68225c9fba12e378c3eaf725` from producer `4b6629ac2`.
`AppChatConversationScreen.kt` is byte/blob-identical across `ba67` and
`e8fa`; the e8fa binding records that its 109,723,777-byte App APK
(`9acce7551b8b090e609c6ea59d5bfce7ce48c0f93b12ed84f0933b4528db479c`)
is byte-identical to the previous verified production APK. The separate test
APK is 3,500,371 bytes, SHA-256
`fabb4ba69919d334752ce8a1c05313494389216a0e87f3710a7cd4008469a033`.

WP-503 subsequently committed the executed integration, APK/input binding,
runner transcript, metrics and viewport/row measurements in its published
evidence tree at
[`f75d4e4`](https://github.com/cbattlegear/MeshCoreOne-Android/tree/f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab/docs/android/evidence/WP-503).
The [integration binding](https://github.com/cbattlegear/MeshCoreOne-Android/blob/f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab/docs/android/evidence/WP-503/device-uipreconditions-integration.json),
[API31 runner transcript](https://github.com/cbattlegear/MeshCoreOne-Android/blob/f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab/docs/android/evidence/WP-503/device-uipreconditions-api31.txt),
[native measurements](https://github.com/cbattlegear/MeshCoreOne-Android/blob/f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab/docs/android/evidence/WP-503/device-uipreconditions-metrics-api31.txt),
and [published evidence section](https://github.com/cbattlegear/MeshCoreOne-Android/blob/f75d4e4f2c78cc93a3c055c8b69a8b0b38fe25ab/docs/android/evidence/WP-503/README.md#targeted-observer-correction-measured-api31-offset-restoration)
are now directly reviewable. The binding confirms the same product and test
APK hashes documented above. The fixture-only correction samples the target,
visible rows and viewport on the UI thread using actual placed/attached layout
coordinates; offset, bottommost-row, unchanged-visible-set, message-identity
and recreation assertions remain unchanged. No WP-307 product or test bytes
changed.

**Actual API31 targeted AndroidJUnitRunner result:** 2 methods started, 2
matching outcomes, 1 passed / 1 failed, 0 errors / 0 skips, 12.816 seconds.
`realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200`
passed on the real App graph/store at font scale 2.0. Retained geometry shows
the same visible message set while the measured target offset moves from 12px
to 24px, then reports 24px after both 900x900 and 360x900 Activity recreation.
The original target/bottommost and offset assertions now pass. The other
selected method, an unrelated saved-Line-of-Sight native-name case, fails;
this is **not a green two-test cohort** or full-suite pass.

The e8 API37 attempt did not start an API37 chat test. The remaining native
criterion for this WP is the same unchanged cached-chat offset/recreation test
passing on API37 with its source, APK and device profile bound to the result.
The existing WP-503 owner is assigned that missing execution. The later
profile handoff records actual 4-CPU count as an admitted diagnostic resource
identity, not a 2-CPU test requirement. The selected cached-chat case does not
assert visible-IME behavior; `show_ime_with_hard_keyboard=0` is admitted, and
the separate keyboard-visible test is not selected. These values do not relax
or change the 12px-to-24px, 0.5px geometry, full-visible-set or recreation
assertions.

The most recent missing-only API37 attempt is documented by WP-503 at
[profile admission](https://github.com/cbattlegear/MeshCoreOne-Android/pull/205#issuecomment-6276271523)
and [installation outcome](https://github.com/cbattlegear/MeshCoreOne-Android/pull/205#issuecomment-6276341384).
It passed the admitted device/profile and source/APK continuity guards, but
the temporary App install failed before instrumentation with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the existing debug App and source-bound
App/Test APK pair use different public signing certificates. The chat method
therefore remains at **0 starts/outcomes** in that invocation; the install
failure is not a test failure or pass. Compatible DEBUG packaging support is
routed through the existing WP-003/WP-503 owners. This is not release signing:
no key/security bypass, uninstall, data wipe, re-signing or release waiver is
authorized or claimed. The next acceptance is the unchanged API37 chat test
after that exact debug-package identity blocker is resolved. No API31 replay or
broader 23-case rerun is needed for this bounded criterion.

The API31 result is combined execution with WP-503's corrected observer,
**not standalone PR #235** or current-main native execution. The prior `ba67`
23-case result remains 21/23 with two failures; the targeted observer
follow-up does not retry, replace or reclassify it. No physical-device/TalkBack
result or independent approval is claimed. No product change, owner-fixture
edit, new worker, or extra CI/result bundle was made by WP-307.

The initial failure is now explained by the test observer mixing the target's
layout-coordinate geometry with visible rows' separately fetched/clipped
semantics bounds/positions. The corrected owner-side observer compares row
and viewport geometry from the same actual-layout coordinate sample and
executes the observation on the UI thread; the unchanged strict chat case
passes. This identifies the observed prerequisite failure as a fixture
measurement issue, not evidence of a product geometry or restoration defect.
The exact-offset product behavior has a **qualified combined API31 native
pass**. The remaining native acceptance is the exact unchanged chat
offset/recreation test on API37, owned by the existing WP-503 assignment.
The combined API31 result is not standalone PR #235 device execution or
then-current-main execution.

## Native regression: combined producer integration

WP-503 ran the unchanged `NativeLauncherFocusAccessibilityTest.realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200`
on the real App AndroidJUnitRunner. This is a **combined integration result**,
not standalone execution of PR #235: the product tree combined WP-307 PR #235
head `2396ac64f9d3bcee7fa3a7e4f33e87956508d31d`, WP-318 PR #232 head
`1b2cd0476b5f70bbbd9ccaf71590e4613898eca1`, and WP-311 PR #233 head
`c8708313310b76df40be9e75a763fe90dac876e8`. WP-503's
[`device-repairs-integration.json`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/04d7d46e2fd3df4d8e508e25b5ffeba93a23f260/docs/android/evidence/WP-503/device-repairs-integration.json)
binds those source blobs to integration head `41bdbfac45b4d3690f29a5363da02047642e2716`
and Android tree `6095dafdf9c59a43d202ff15e70d1c63ac21cf94`; it explicitly
records `standalone_producer_head_device_result: false`. Production was
unchanged from the initial combined integration. The recorded APK binding is
[`device-repairs-inputs.json`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/04d7d46e2fd3df4d8e508e25b5ffeba93a23f260/docs/android/evidence/WP-503/device-repairs-inputs.json).

The unchanged chat test passes on both APIs, exercising both 360dp-to-900dp
and 900dp-to-360dp Activity recreations and retaining the middle cached
message, route/stacks/selection, and CJK+RTL draft. In the full package
invocations API31 discovered and passed 32/32 tests. API37 discovered 32,
passed 31 and failed 1 with zero errors/skips; the failure is the existing
`realDisconnectedLauncherKeepsFiveTabsSelectedAndPlatformFocusReachable`
touch-exploration activation prerequisite before its method body, not the chat
test. Its cause remains unproven. The complete transcripts, metrics and inputs
are retained by WP-503 at
[`device-repairs-full-api31.txt`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/04d7d46e2fd3df4d8e508e25b5ffeba93a23f260/docs/android/evidence/WP-503/device-repairs-full-api31.txt),
[`device-repairs-full-api37.txt`](https://github.com/cbattlegear/MeshCoreOne-Android/blob/04d7d46e2fd3df4d8e508e25b5ffeba93a23f260/docs/android/evidence/WP-503/device-repairs-full-api37.txt),
and the WP-503 [combined producer evidence section](https://github.com/cbattlegear/MeshCoreOne-Android/blob/04d7d46e2fd3df4d8e508e25b5ffeba93a23f260/docs/android/evidence/WP-503/README.md#combined-published-producer-repairs).
These are linked in place; no reproducible hosted-CI bundle is copied.

The original standalone baseline at
`5a8ee1990250e8bed32010b50fcda86174ab1097` remains the historical
26-discovered / 25-passed / 1-failed per-API failure, not a fix result. The
native fixture remains owned by WP-503 and was not copied or modified in this
WP-307 worktree. WP-307's manifest verification remains `configured: false`
with no commands; its Windows scaffold preflight is blocked because the shared
SDK lacks `build-tools/37.0.0/package.xml`. Therefore this evidence establishes
the unchanged regression passing in the named combined product/test
integration, **not standalone #235 device acceptance**.

The declared WP-307 verification configuration is still `configured: false`
with no commands. The Windows scaffold preflight also rejects the shared SDK because
`build-tools/37.0.0/package.xml` is missing. Running the focused module Gradle
task directly does not change that preflight result or establish standalone
#235 device acceptance.

The WP-307 reservation preserves the historical coordinator identity:
session `bcb17a74-5fa6-47d0-a4be-b6b595e20559`, branch
`cbattlegear-android-port-planning`, historical base
`a19d6138038577d7e76c63e85b58c7ba6976092a`, and no PR number. The old
fresh-claim identity mismatch is historical audit context, not a current
path-admission blocker. The existing same-owner reservation evolved successfully:

```powershell
python tools\android-port\local\reserve.py evolve --wp WP-307 --expected-revision 9 --path android/app/src/test/kotlin/com/meshcoreone/android/app/platform/ChatTimelineRestorationTest.kt --operation add-direct-support-wiring
python tools\android-port\local\reserve.py evolve --wp WP-307 --expected-revision 10 --path docs/android/evidence/WP-307/scroll-recreation.md --path docs/android/deviations/WP-307.md --operation record-evidence
python tools\android-port\local\reserve.py status
```

**PASS:** exit 0; revisions 10 and 11 admitted the App test and WP-owned
evidence; revision 12 admitted current-base reconciliation through
`merge-integration`. The mounted App test maps to `app-build-launcher`;
evidence maps to `wp-owned`. Status preserves immutable
authorization/identity/bindings. There is **no missing
capability/path/operation** for this bounded work. No manual ledger edit,
release/recreate or identity impersonation was performed. WP-503 retains
`android/accessibility-tests/`; its fixture was not copied or edited.

PR #235 is **ready for review** by the user-directed state change. This
reconciliation does not constitute trusted review, merge approval, or
acceptance of a native/API37 result.

No raw logs, device identifiers, candidate CI bundles, or reproducible CI
result bundles are retained here.
