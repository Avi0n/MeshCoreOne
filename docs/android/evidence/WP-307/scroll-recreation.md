# WP-307 scroll restoration evidence

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / owner: `WP-307` / `chats-ui-engineer`
- Issue: #230, preserve cached chat scroll across native window recreation
- Base: `d8181418614a5acaa46f4acde54b741b492ac4b0`
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`
- Manifest SHA-256: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`
- Acceptance: `WP-307-behavior`, `WP-307-boundaries`
- Scope: `ChatTimeline.kt`, `TimelineStateTest.kt`, this evidence and the WP-307 deviation

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
with no commands; its local scaffold preflight is blocked because the shared
SDK lacks `build-tools/37.0.0/package.xml`. Therefore this evidence establishes
the unchanged regression passing in the named combined product/test
integration, **not standalone #235 device acceptance**.

The declared WP-307 verification configuration is still `configured: false`
with no commands. The scaffold preflight also rejects the shared SDK because
`build-tools/37.0.0/package.xml` is missing. Running the focused module Gradle
task directly does not change that preflight result or establish standalone
#235 device acceptance.

The current WP-307 reservation is still leased at revision 9 to coordinator
session `bcb17a74-5fa6-47d0-a4be-b6b595e20559`, branch
`cbattlegear-android-port-planning`, historical base
`a19d6138038577d7e76c63e85b58c7ba6976092a`, and no PR number. It already
contains the timeline implementation/test paths and `test` operation, but the
trusted helper has no persistent same-owner identity/base/PR handoff operation.
The latest claim attempt for session `4b86bbbd-bd7d-44ce-8ef9-ffa67316ba0d`
and base `d8181418614a5acaa46f4acde54b741b492ac4b0` fails:
`Existing WP lease is bound to different source/base/manifest/backend`.
No manual ledger edit, release/recreate, or session impersonation was done.
WP-503's manifest owns `android/accessibility-tests/`; WP-307 does not, so
native test ownership stayed with WP-503.

No raw logs, device identifiers, candidate CI bundles, or reproducible CI
result bundles are retained here.
