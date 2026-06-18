# Development Documentation

This guide provides information for developers who want to contribute to the MeshCore One project.

## Getting Started

### Prerequisites

- **Xcode 26.0+** (as specified in `project.yml`)
- **Swift 6.2+**
- **XcodeGen**: Required for project file generation.
  ```bash
  brew install xcodegen
  ```
- **SwiftGen**: Required for localization code generation (runs as an Xcode pre-build script).
  ```bash
  brew install swiftgen
  ```
- **xcsift** (optional): Transforms verbose Xcode output into concise JSON.
  ```bash
  brew install xcsift
  ```

### Project Setup

1. **Clone the repository**.
2. **Generate the Xcode project** from `project.yml`:
   ```bash
   xcodegen generate
   ```
   > **Note:** Do not edit `MC1.xcodeproj` directly — it is generated from `project.yml` and will be overwritten. Make project configuration changes in `project.yml` and regenerate.
3. **Open `MC1.xcodeproj`**.

## Building the Project

MeshCore One uses a modular structure with Swift Packages:

- `MeshCore`: The protocol framework.
- `MC1Services`: The business logic framework.
- `MeshCore One`: The main iOS application.

### Command Line Build

```bash
xcodebuild -project MC1.xcodeproj \
  -scheme MeshCore One \
  -destination "platform=iOS Simulator,name=iPhone 16e" \
  build
```

### Using xcsift

xcsift transforms verbose Xcode output into concise, structured JSON:

```bash
# Basic build with JSON output
xcodebuild build 2>&1 | xcsift

# Show detailed warnings
xcodebuild build 2>&1 | xcsift --warnings

# Quiet mode (suppress output on success)
xcodebuild build 2>&1 | xcsift --quiet

# Treat warnings as errors
xcodebuild build 2>&1 | xcsift --Werror

# Code coverage summary
xcodebuild test -enableCodeCoverage YES 2>&1 | xcsift --coverage
```

## Testing Strategy

MeshCore One emphasizes comprehensive testing at all layers.

### Unit Tests

- **MeshCoreTests**: Tests packet building, parsing, LPP decoding, and session state.
- **MC1ServicesTests**: Tests business logic services, actor isolation, and persistence.
- **MC1Tests**: Tests app state and view models.

### Running Tests

```bash
# Run all tests
xcodebuild test -project MC1.xcodeproj \
  -scheme MeshCore One \
  -destination "platform=iOS Simulator,name=iPhone 16e"

# With xcsift for concise output
xcodebuild test 2>&1 | xcsift

# With code coverage
xcodebuild test -enableCodeCoverage YES 2>&1 | xcsift --coverage
```

### Test Infrastructure

#### MockTransport

For testing without physical hardware:

```swift
let mock = MockTransport()
let session = MeshCoreSession(transport: mock)

// Simulate device response
await mock.simulateReceive(testPacket)

// Helper methods for common responses
await mock.simulateOK()
await mock.simulateError(code: 0x01)

// Verify sent data (sentData is an array)
let sent = await mock.sentData
#expect(sent.count > 0)
#expect(sent.last == expectedPacket)

// Clear sent data history
await mock.clearSentData()
```

#### MockPersistenceStore

For testing services without SwiftData:

```swift
let mockStore = MockPersistenceStore()
let service = MessageService(session: session, dataStore: mockStore)

// Verify persistence calls
#expect(mockStore.savedMessages.count == 1)
```

### Swift Testing Framework

We use the modern **Swift Testing** framework (`@Test`, `@Suite`, `#expect`) for all new tests:

```swift
@Suite("MessageService Tests")
struct MessageServiceTests {
    @Test("Send message creates pending message")
    func sendMessageCreatesAck() async throws {
        let service = MessageService(...)
        let message = try await service.sendDirectMessage(text: "Hello", to: contact)

        #expect(message.status == .pending)
    }
}
```

## Coding Standards & Conventions

### Swift 6 Concurrency

- **Strict Concurrency**: The project is compiled with `SWIFT_STRICT_CONCURRENCY: complete`.
- **Actor Isolation**: Use actors for shared state and services.
- **MainActor**: All UI-related code must be isolated to the `@MainActor`.
- **Sendable**: Ensure all data types passed between actors conform to `Sendable`.

### Naming Conventions

- **Services**: Suffix with `Service` (e.g., `MessageService`).
- **Data Objects**: Suffix with `DTO` when used for cross-boundary data transfer (e.g., `MessageDTO`).
- **Persistence**: Use `PersistenceStore` (alias: `DataStore`) for data access.
- **ViewModels**: Suffix with `ViewModel` (e.g., `ChatViewModel`).

### SwiftUI Conventions

- Use `@Observable` classes, not `ObservableObject`.
- Use `foregroundStyle()` not `foregroundColor()`.
- Use `NavigationStack` not `NavigationView`.
- Use `Tab` API not `tabItem()`.
- Prefer `Button` over `onTapGesture()`.

### Persistence

- **SwiftData**: All persistence should use SwiftData models defined in `MC1Services`.
- **No Direct Store Access**: Services should interact with data via the `PersistenceStore` actor.

## Linting and Formatting

### SwiftLint

The project uses [SwiftLint](https://github.com/realm/SwiftLint) for style enforcement, configured via `.swiftlint.yml` at the repo root.

```bash
# Check for violations
swiftlint lint

# Auto-fix trivial violations (trailing whitespace, etc.)
swiftlint --fix
```

The configuration sets thresholds slightly above current codebase maximums. These will be tightened incrementally. Rules that overlap with planned refactoring work (e.g., `file_length`, `type_body_length`) are disabled until that work lands.

If SwiftLint is not installed: `brew install swiftlint`

### Pre-Commit Workflow

Before committing:

```bash
# Lint
swiftlint lint

# Build and test
xcodebuild test 2>&1 | xcsift --Werror
```

## Documentation (DocC)

The project uses DocC for inline documentation. All public APIs should be documented using standard Swift documentation comments.

To generate the documentation site:

```bash
xcodebuild docbuild -scheme MeshCore
```

## Project Dependencies

### Emojibase

The project integrates [Emojibase](https://github.com/matrix-org/emojibase-bindings) for emoji picker data. This dependency is configured in `project.yml`:

```yaml
packages:
  Emojibase:
    url: https://github.com/matrix-org/emojibase-bindings
    from: "1.5.0"
```

### AccessorySetupKit

For iOS 18+ device pairing, the project uses AccessorySetupKit. The `AccessorySetupKitService` in MC1Services handles the pairing flow.

Required Info.plist keys:

```xml
<key>NSAccessorySetupKitSupports</key>
<array>
    <string>Bluetooth</string>
</array>
<key>NSAccessorySetupBluetoothServices</key>
<array>
    <string>6E400001-B5A3-F393-E0A9-E50E24DCCA9E</string>
</array>
<key>NSAccessorySetupBluetoothNames</key>
<array>
    <string>MeshCore-</string>
</array>
```

### iOS 18+ Features

MeshCore One leverages several modern iOS 18+ APIs and frameworks:

**AccessorySetupKit**:
- Native device discovery and pairing interface
- User-friendly pairing experience (no manual Bluetooth device selection)
- Automatic permission handling during pairing flow

**Advanced SwiftUI APIs**:
- `@Observable` macro for modern observation pattern
- `NavigationStack` for programmatic navigation
- `Tab` API for tab bar configuration
- Modern layout APIs (`containerRelativeFrame`, `visualEffect`)

**Swift 6 Concurrency**:
- Strict concurrency model for thread safety
- Actor-based services for data isolation
- Sendable protocol for cross-boundary data transfer

**SwiftData**:
- Declarative persistence layer
- Automatic migration support
- Type-safe data models

### Bluetooth Requirements

The app requires Bluetooth and location permissions plus background BLE mode:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>MeshCore One uses Bluetooth to maintain connections with MeshCore radios, even in the background, so you can send and receive messages without opening the app.</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>MeshCore One uses Bluetooth to connect to MeshCore radio devices for mesh messaging.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>MeshCore One can share your location with contacts on the mesh network.</string>
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
</array>
```

## BLE Transport Architecture

- **MeshCore/Sources/MeshCore/Transport/MeshTransport.swift**: Transport protocol the BLE stack plugs into
- **MC1Services/Transport/iOSBLETransport.swift**: iOS BLE transport with CoreBluetooth integration
- **MC1Services/Transport/BLEStateMachine.swift**: Connection state management
- **MC1Services/Services/AccessorySetupKitService.swift**: iOS 18+ pairing flow

### WiFi Transport

MeshCore One also supports WiFi transport for MeshCore firmware devices:

- **MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift**: TCP transport (Network.framework) that conforms to `MeshTransport`
- **Connection Type**: Automatic detection based on device capability

**Testing WiFi Transport**:

```bash
# WiFi transport testing requires a MeshCore device with WiFi capability
# Connect to device's WiFi hotspot
# Use same connection flow as BLE - ConnectionManager handles both transports

# Debug WiFi connection
# Add debug logging to track connection lifecycle:
logger.info("Connecting to WiFi transport", metadata: ["ip": deviceIP])
```

**WiFi vs BLE**:

| Aspect | BLE | WiFi |
|---------|------|-------|
| Range | Short (~10-50m) | Long (~100-300m) |
| Power Consumption | Low | Medium |
| Setup | AccessorySetupKit (iOS 18+) | Manual hotspot connection |
| Throughput | Lower (~250 bytes/sec) | Higher (~1KB/sec) |
| Use Case | Mobile devices, battery-powered | Fixed installations, repeaters |

---

## iPad Testing

When developing or testing on iPad, consider the following:

### Split-View Testing

The iPad interface uses split-view navigation:

- **Test Both Panels**: Ensure list and detail panels work independently
- **Test Navigation**: Verify both panels maintain their own navigation stacks
- **Test State Changes**: Ensure updates in one panel don't disrupt the other
- **Test Orientation**: Verify layout adjusts correctly between portrait/landscape

### Testing Workflow

```bash
# Build for iPad Simulator
xcodebuild test \
  -project MC1.xcodeproj \
  -scheme MeshCore One \
  -destination "platform=iOS Simulator,name=iPad Pro (13-inch)"

# Test on physical iPad (requires development team)
xcodebuild test \
  -destination 'platform=iOS,name=My iPad'
```

### iPad-Specific Considerations

- **Larger Screens**: Test with various iPad screen sizes (11", 12.9", 13")
- **Keyboard Support**: Test keyboard shortcuts and hardware keyboard interaction
- **Window Management**: Test Stage Manager and multiple windows (if applicable)
- **Accessibility**: Test with iPad accessibility features (Dynamic Type, VoiceOver)

### Responsive Design Testing

```swift
// Test different size classes with the #Preview macro
#Preview("iPhone Portrait") {
    MyView()
        .previewInterfaceOrientation(.portrait)
}

#Preview("iPad Portrait", traits: .fixedLayout(width: 1024, height: 1366)) {
    MyView()
        .previewInterfaceOrientation(.portrait)
}

#Preview("iPad Landscape", traits: .fixedLayout(width: 1366, height: 1024)) {
    MyView()
        .previewInterfaceOrientation(.landscapeLeft)
}
```

## Testing Diagnostic Tools

When working on diagnostic features (Line of Sight, Trace Path, RX Log), keep these considerations in mind:

### Line of Sight Tool

**Testing without internet**:
- Elevation service uses Open-Meteo API (requires internet for first fetch)
- Mock elevation data for offline testing
- Test with cached elevation data after initial fetch

**Testing terrain analysis**:
- Test with known obstructed paths (verify red clearance status)
- Test with clear paths (verify green clearance status)
- Verify Fresnel zone calculations with different frequencies
- Test edge cases (very short paths, very long paths)

### Trace Path Tool

**Testing path discovery**:
- Mock network topology for repeatable tests
- Test with single-hop and multi-hop paths
- Test with no available paths (verify error handling)
- Test saved path persistence and retrieval

### RX Log Viewer

**Testing packet capture**:
- Test with MockTransport to simulate various packet types
- Verify real-time updates in SwiftUI
- Test filtering by packet type, source, destination
- Test log export functionality

### Debug Logging Infrastructure

**Testing PersistentLogger**:
- Verify SwiftData persistence
- Test automatic cleanup of old logs
- Verify log redaction (sensitive data masking)
- Test log export with time range filtering

**Testing DebugLogBuffer**:
- Test circular buffer behavior (overflow handling)
- Verify thread safety of concurrent log writes
- Test memory usage with high log volume

---

## Further Reading

- [Architecture Overview](Architecture.md)
- [MeshCore API Reference](api/MeshCore.md)
- [MC1Services API Reference](api/MC1Services.md)
- [BLE Transport Guide](guides/BLE_Transport.md)
- [WiFi Transport Guide](guides/WiFi_Transport.md)
- [Diagnostics Guide](guides/Diagnostics.md)
- [iPad Layout Guide](guides/iPad_Layout.md)
