# Testing

## Running Tests

### Xcode

Use the Test navigator (Cmd+6) or Cmd+U to run all tests.

### Command Line

Prefer the `make` targets; they take a per-simulator lock so concurrent sessions serialize instead of hanging:

```bash
make test-app    # full app suite on iPhone 17e / iOS 27.0
make test-store  # StoreKit/IAP SKTestSession suites on iOS 18.x
make test        # both of the above
```

The full app suite can take several minutes. `xcsift` prints its summary when the run ends. The periodic elapsed-time messages do not show whether tests are still making progress. Save the raw log when investigating a slow or stuck run:

```bash
mkdir -p tmp
make test-app XCSIFT_WITH_HEARTBEAT='tee tmp/test-app-raw.log | xcsift -f toon'
```

Check the command's exit status and Xcode's test summary in the raw log. `xcsift` can report zero failures even when a Swift Testing assertion failed. Look in the `.xcresult` bundle for failure details.

Pass filters, repetition settings, or a result-bundle path through `XCODEBUILD_TEST_ARGS`:

```bash
make test-app XCODEBUILD_TEST_ARGS='-only-testing:MC1Tests/LifecycleTransitionTests -test-iterations 5'
```

App tests run one at a time. Parallel testing is disabled in both the generated scheme and the make targets because several suites share the key window and keyboard. `@MainActor` and `.serialized` on a suite still allow other suites to run while a test is suspended. Keep parallel testing disabled for the full app suite.

Run the SPM packages from the repository root, with parallel testing disabled as it is in CI:

```bash
set -o pipefail

# Services package tests
swift test --package-path MC1Services --no-parallel 2>&1 | xcsift -f toon

# MeshCore package tests
swift test --package-path MeshCore --no-parallel 2>&1 | xcsift -f toon
```

Use `xcsift` to get structured output. Add `-c` for code coverage or `-w` for a detailed warnings list. See CLAUDE.md for the full flag reference.

The Build workflow runs both SPM package suites and builds the app for the iOS Simulator. GitHub CI does not run the app or StoreKit test suites for now. Run those locally with the make targets above.

For a local app test run that saves logs and an `.xcresult` bundle, use `scripts/test-app-ci.py`. It runs `make test-app` on iPhone 17e / iOS 27.0 and reuses `.derivedData`.

The script allows 12 minutes for building and testing. If the run exceeds that limit, it saves a process list and short stack samples to help diagnose the stall. It samples the test host only when it can identify it unambiguously on the pinned simulator. It then interrupts Xcode, gives it time to save results, and stops the process group it started. Failed runs are not retried.

The output directory contains `xcodebuild.log`, `runner.log`, `status.json`, the `.xcresult` bundle, and any stall diagnostics that were saved. If the process stops abruptly, these files may be incomplete. Choose a new output directory for each run:

```bash
python3 scripts/test-app-ci.py --artifacts tmp/app-ci-local
python3 -m unittest discover -s scripts/tests -v
```

StoreKit/IAP suites run on iOS 18.x (iPhone 16e); SKTestSession serves no products under iOS 26 simulators. `make test-store` selects the iOS 18 destination automatically.

## Test Targets

| Target | Package | Framework | Scope |
|--------|---------|-----------|-------|
| `MC1Tests` | Xcode project | Swift Testing | ViewModels, AppState, views, models, utilities |
| `MC1ServicesTests` | MC1Services (SPM) | Swift Testing | Services, transport, persistence, connection management |
| `MeshCoreTests` | MeshCore (SPM) | Swift Testing | Protocol parsing, crypto, transport codecs |

All test targets use Swift Testing (`@Suite`, `@Test`, `#expect`), including the byte-level protocol compatibility tests in `MeshCoreTests/Validation/` and `MeshCoreTests/Protocol/`. The keyboard and scroll suites cover these 25 cases:

| Suite | Cases | What it tests |
|-------|-------|-------------------|
| `NativeViewportScrollPreservationTests` | 13 | Keeping the bottom or reading position visible as the viewport, insets, composer, rows, or layout change; dragging, deceleration, and scroll offset limits |
| `ChatKeyboardTransitionTests` | 5 | Keyboard alignment, tall rows staying visible, initial and saved reading positions, and focusing the composer near the bottom |
| `ChatKeyboardTransitionClassificationTests` | 3 | Distinguishing modal, interactive navigation, and noninteractive navigation transitions |
| `ChatKeyboardSheetDismissalTests` | 3 | Focus and keyboard behavior when a sheet closes |
| `ChatChannelKeyboardTypingTests` | 1 | Typing in the channel composer used by the app |

The three transition-classification cases run as separate inputs to one parameterized test.

Transition tests attach `samples.json`, `notifications.json`, and all captured PNGs to the test results. Viewport tests attach text and images when they fail. Each test cleans up the state it creates: restoring windows and controllers, dismissing the keyboard, stopping recorders, and shutting down test instances of `AppState`.

When a test waits for SwiftUI to lay out a change, use `await` and check the rendered size or position until a deadline. A synchronous nested `RunLoop` can prevent pending `MainActor` work from running. The row-growth test checks the reading position while it waits for the new row height, then makes its final assertions.

## Mock Patterns

Mocks are **protocol-based Swift actors** with a consistent structure:

```swift
public actor MockChannelService: ChannelServiceProtocol {
    // MARK: - Stubs
    public var stubbedChannels: [Channel] = []

    // MARK: - Recorded Invocations
    public private(set) var fetchChannelsInvocations: [Void] = []

    // MARK: - Protocol Methods
    public func fetchChannels() async throws -> [Channel] {
        fetchChannelsInvocations.append(())
        return stubbedChannels
    }

    // MARK: - Test Helpers
    public func reset() { ... }
}
```

- **Stubs** (`stubbedXxx`) provide configurable return values
- **Invocation arrays** (`xxxInvocations`) record every call for assertion
- **`reset()`** clears recorded state between tests
- Actor isolation provides thread safety under strict concurrency

Protocol-based mock actors live in `MC1ServicesTests/Mocks/`; app-layer test doubles are defined inline alongside the tests that use them in `MC1Tests/`.

## ServiceContainer.forTesting()

Creates the full service graph backed by in-memory SwiftData storage:

```swift
let transport = SimulatorMockTransport()
let session = MeshCoreSession(transport: transport)
let container = try await ServiceContainer.forTesting(session: session)
```

- Uses `PersistenceStore.createContainer(inMemory: true)` for zero disk I/O
- Takes an optional `radioID` (default: synthesized `UUID()`) to scope the per-radio stores
- Cross-service callbacks are established by `ServiceContainer.init`; `ServiceContainerWiringTests` verifies those connections via `forTesting()`
- `SimulatorMockTransport` is a production actor (`Simulator/SimulatorMockTransport.swift`) that satisfies `MeshTransport` with no-op operations

## Test Utilities

| Utility | Location | Purpose |
|---------|----------|---------|
| `MutableBox<T>` | `MC1Tests/Helpers/TestHelpers.swift` | Captures mutable values in async closures under strict concurrency |
| `TestClock` | `MeshCore/Tests/MeshCoreTestSupport/TestClock.swift` | Advances virtual time for MeshCore and MC1Services tests using an injected `Clock` |
| `DeviceDTO.testDevice()` | `MC1ServicesTests/Helpers/DeviceDTO+Testing.swift` | Factory with sensible defaults for building test fixtures |
| `SimulatorMockTransport` | `MC1Services/.../Simulator/SimulatorMockTransport.swift` | No-op `MeshTransport` for creating sessions without hardware |
| `PythonReferenceBytes` | `MeshCoreTests/Fixtures/PythonReferenceBytes.swift` | Static byte arrays from the Python reference implementation |

For delays and timeouts, inject a `Clock`. Before advancing a `TestClock`, check `sleeperCount` to confirm the code has started waiting. For event ordering, subscribe before sending events. For task ordering, wait for a signal that the relevant work has started or finished. A sleep or `Task.yield()` does not prove another task is ready. Use real-time deadlines when waiting for external systems or detecting stuck test setup.

## Conventions

- **`@MainActor` on test suites**: Any test interacting with `AppState` or `ConnectionManager` annotates the `@Suite` or `@Test` with `@MainActor`.
- **App-layer tests** instantiate `AppState` directly. Tests that change preferences pass an isolated `UserDefaults` suite and call `shutdown()` before cleaning it up.
- **Service-layer tests** use `ServiceContainer.forTesting()` or inject individual mock actors.
- **MeshCore tests** are self-contained with no external dependencies.

## File Organization

```
MC1Tests/
├── AppState/          # AppState sub-object tests
├── Extensions/        # Data extensions, battery info, error dispatch
├── Formatters/        # Message path formatting
├── Helpers/           # MutableBox and other test utilities
├── Localization/      # Localized label tests
├── Models/            # Data model tests
├── Protocol/          # CLI response, LPP display
├── Services/          # Elevation, preview cache, image detection
├── State/             # Message event stream, send queue
├── Theme/             # Theme structure and contrast
├── Utilities/         # Demo mode, mention utilities, scroll policies
├── ViewModels/        # ViewModel unit tests
└── Views/             # View-level logic tests

MC1ServicesTests/
├── Connection/        # Connection model/store tests
├── Helpers/           # Test fixture builders
├── Mocks/             # Protocol-based mock actors
├── Models/            # DTO and connection model tests
├── Services/          # Per-service unit tests
├── Transport/         # BLE phase and state machine tests
├── Utilities/         # Device identity, hashtag utilities
└── (root)             # ServiceContainer wiring, sync coordinator tests

MeshCoreTests/
├── Events/            # EventDispatcher and filter tests
├── Fixtures/          # Reference byte arrays
├── Helpers/           # Polling and other test utilities
├── Protocol/          # PacketBuilder command tests
├── Session/           # Session timeout and lifecycle tests
├── Transport/         # WiFi codec and transport tests
└── Validation/        # Byte-level protocol tests
```
