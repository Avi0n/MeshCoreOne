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

## Native regression limitation

The native regression described in the issue was observed by WP-503 on the real
App AndroidJUnitRunner at API31 and API37 (26 discovered / 25 passed / 1 failed
per API; the retained middle-message assertion failed). That exact fixture is
at `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLauncherFocusAccessibilityTest.kt`
in candidate `5a8ee1990250e8bed32010b50fcda86174ab1097`, but that path is absent
from this base and outside WP-307's manifest `write_paths`. The trusted
capability validator rejects `test` and `add-regression-fixture-or-test` for
that path. It was not copied, edited or run here; no API31/API37 device result
is claimed for this fix.

The declared WP-307 verification configuration is still `configured: false`
with no commands. The scaffold preflight also rejects the shared SDK because
`build-tools/37.0.0/package.xml` is missing. Running the focused module Gradle
task directly does not change that preflight result or constitute the required
native acceptance.

The existing WP-307 reservation remains bound to coordinator session
`bcb17a74-5fa6-47d0-a4be-b6b595e20559`, branch
`cbattlegear-android-port-planning`, and a different historical base. This
follow-up worktree is session `4b86bbbd-bd7d-44ce-8ef9-ffa67316ba0d` at the
base above. Trusted admission confirms the changed implementation, unit-test,
deviation, and evidence paths are WP-owned, but there is no persisted reservation
identity handoff to this session. The native regression test path remains the
exact missing path/operation rule described above.

No raw logs, device identifiers, candidate CI bundles, or reproducible CI
result bundles are retained here.
