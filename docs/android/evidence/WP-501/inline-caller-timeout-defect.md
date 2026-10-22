# [WP-407] Inline content converts caller timeout cancellation into failure

**Describe the bug**

WP-501 established a real app-consumer defect: `AppChatContent.inline` catches a
caller's `TimeoutCancellationException` in the same catch as its own 30-second
deadline and throws `IllegalStateException("Inline image fetch timed out")`.
This breaks the WP-218 cancellation adaptation at the mounted WP-407 consumer.
This is not a physical-radio, signing, legal or missing-mapping report.

**To Reproduce**

1. Run the existing app task through `android\scaffold\invoke-gradle.ps1` with
   private native Gradle/Android caches and the shared `gradle-execution` lock:
   `:app:testDebugUnitTest --tests com.meshcoreone.android.parity.* --dependency-verification=strict`.
2. The `ContentCancellationAcceptanceTest` creates actual in-memory Room,
   DataStore and production `AppChatContent`; injected HTTP suspends with
   `awaitCancellation()`. The literal public-IP image URL avoids DNS. No HTTP
   request is sent by the fake.
3. Call `inline` inside an outer `withTimeout(1_000)` on the virtual test clock.
4. Exact case
   `com.meshcoreone.android.parity.ContentCancellationAcceptanceTest::callerTimeoutRemainsCancellationAndDoesNotPoisonInlineRetry`
   fails with `IllegalStateException` at `AppChatContent.kt:116`, whose cause is
   the **1-second caller** `TimeoutCancellationException`, not the internal
   30-second timeout.

Observed local discovery: 12 tests, 11 passed, 1 failed, 0 errors/skips.
The ten backup/text/Room cases and exact source radio-repeat regression pass.
The retry assertion is not reached after the failure; cache poisoning is not
claimed as an observed second defect.

**Expected behavior**

The outer timeout remains cancellation, never an ordinary image-fetch failure.
The URL remains retryable after cancellation. A genuine internal 30-second
deadline must still surface an explicit timeout failure. Add assertions for
all three outcomes without increasing the deadlines or catching arbitrary
failures in the test.

Pinned source `db14559b39d32322b06477c6ae676112f583db50`:
`MC1/Views/Chats/ViewModel/ChatViewModel+Prefetch.swift:424-429` checks
`Task.isCancelled` immediately after the actual cache fetch, removes the task
and resets orphaned loading instead of emitting a failed image state.
`MC1/Services/InlineImageCache.swift` supplies the original fetch/cache behavior.
The declared native WP-218 adaptation (`deviations/WP-218.md:1331-1338`)
propagates coroutine cancellation rather than converting it to failed results.
The actual native `InlineImageCache.fetchImageData` already preserves this
contract and removes in-flight state in `finally`; the app wrapper loses it.

**Screenshots**

Not applicable: deterministic JVM/Room coroutine-consumer reproduction.
The actual local JUnit failure is in
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.ContentCancellationAcceptanceTest.xml`.
This report summarizes the observed defect; it is not a second CI-result bundle.

**Device (please complete the following information):**

- Device: Windows x86_64 host, Robolectric API31 in-memory Room integration.
- OS: Windows; JDK 21.0.12.101; Android compile SDK37.2/build-tools37.0.0.
- Version: `com.meshcoreone.android.debug`, test-only candidate extension.
- Build: baseline `250dd50ed259c5d9fe1006d4f45a637e4aea0aff`;
  published WP-501 head `ee7981b5b2de150c50df55ed37f67ee5f0151f31` plus the
  uncommitted assertion below. No new hosted candidate success is claimed.

**Additional context**

Repair owner: WP-407 `platform-integrations-engineer`; affected path:
`android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatContent.kt`.
Original content owner/provenance remains WP-218 `services-porter`.
The product file is unchanged from the baseline, Git blob
`aa10de3bc1b314fd6fc985f7653a508add2363d1`.
New assertion path:
`android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/ContentCancellationAcceptanceTest.kt`,
SHA256 `108ae503e1dd6edab8b1fa64777aa8dcabbffb51a761bcec77adb292ca221cb3`.

Impact: caller/screen timeout is misclassified as a content failure and can
escape structured cancellation. Measurable acceptance is the exact failing case
passing while an actual internal deadline still fails explicitly.

Duplicate searches against all existing issues for `cancellation content timeout`,
`inline timeout` and `InlineImage` returned no matching issue. Per the user-directed
reporting route, this complete bug-template report is posted on PR165 for the
coordinator's app-native issue creation and immediate bounded product repair.
The WP-501 worker retains the failing assertion and does not edit this product path.
