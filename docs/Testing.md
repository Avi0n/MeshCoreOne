# Testing

## Running Tests

### Xcode

Use the Test navigator (Cmd+6) or Cmd+U to run all tests.

### Command Line

Prefer the `make` targets; they take a per-simulator lock so concurrent sessions serialize instead of hanging:

```bash
make test-app    # full app suite on iOS 26 (StoreKit suites auto-skip here)
make test-store  # StoreKit/IAP SKTestSession suites on iOS 18.x
make test        # both of the above
```

To run a single suite or the SPM packages directly:

```bash
# App-layer tests (MC1Tests): project standard destination is iPhone 17e / iOS 26
xcodebuild test \
  -project MC1.xcodeproj \
  -scheme MC1 \
  -destination "platform=iOS Simulator,name=iPhone 17e,OS=26.5" \
  2>&1 | xcsift -f toon

# Services package tests
cd MC1Services && swift test 2>&1 | xcsift -f toon

# MeshCore package tests
cd MeshCore && swift test 2>&1 | xcsift -f toon
```

Use `xcsift` to get structured output. Add `-c` for code coverage or `-w` for a detailed warnings list. See CLAUDE.md for the full flag reference.

StoreKit/IAP suites run on iOS 18.x (iPhone 16e); SKTestSession serves no products under iOS 26 simulators. `make test-store` selects the iOS 18 destination automatically.

## Test Targets

| Target | Package | Framework | Scope |
|--------|---------|-----------|-------|
| `MC1Tests` | Xcode project | Swift Testing | ViewModels, AppState, views, models, utilities |
| `MC1ServicesTests` | MC1Services (SPM) | Swift Testing | Services, transport, persistence, connection management |
| `MeshCoreTests` | MeshCore (SPM) | Swift Testing | Protocol parsing, crypto, transport codecs |

**Framework**: Swift Testing (`@Suite`, `@Test`, `#expect`) is used throughout, including the byte-level protocol compatibility tests in `MeshCoreTests/Validation/` and `MeshCoreTests/Protocol/`.

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
| `DeviceDTO.testDevice()` | `MC1ServicesTests/Helpers/DeviceDTO+Testing.swift` | Factory with sensible defaults for building test fixtures |
| `SimulatorMockTransport` | `MC1Services/.../Simulator/SimulatorMockTransport.swift` | No-op `MeshTransport` for creating sessions without hardware |
| `PythonReferenceBytes` | `MeshCoreTests/Fixtures/PythonReferenceBytes.swift` | Static byte arrays from the Python reference implementation |

## Conventions

- **`@MainActor` on test suites**: Any test interacting with `AppState` or `ConnectionManager` annotates the `@Suite` or `@Test` with `@MainActor`.
- **App-layer tests** instantiate `AppState()` directly without mock injection.
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
