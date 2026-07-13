import Foundation
@testable import MC1Services
import Testing

@Suite("LastConnectionStore Tests")
struct LastConnectionStoreTests {
  /// Runs `body` against a per-test isolated `UserDefaults` suite,
  /// removing the persistent domain afterwards so no state leaks.
  private func withIsolatedDefaults(_ body: (UserDefaults) throws -> Void) rethrows {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defer { defaults.removePersistentDomain(forName: suiteName) }
    try body(defaults)
  }

  @Test
  func `persist then read round-trips deviceID, radioID, and deviceName`() throws {
    try withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      let deviceID = UUID()
      let radioID = UUID()

      store.persist(deviceID: deviceID, radioID: radioID, deviceName: "Test Radio")

      #expect(store.deviceID == deviceID)
      #expect(store.radioID == radioID)
      #expect(defaults.string(forKey: PersistenceKeys.lastConnectedDeviceName) == "Test Radio")
    }
  }

  @Test
  func `clear removes all three persisted values`() throws {
    try withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      store.persist(deviceID: UUID(), radioID: UUID(), deviceName: "Test Radio")

      store.clear()

      #expect(store.deviceID == nil)
      #expect(store.radioID == nil)
      #expect(defaults.string(forKey: PersistenceKeys.lastConnectedDeviceName) == nil)
    }
  }

  @Test
  func `empty defaults read as nil`() {
    withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)

      #expect(store.deviceID == nil)
      #expect(store.radioID == nil)
      #expect(store.disconnectDiagnostic == nil)
    }
  }

  @Test
  func `malformed UUID strings read as nil`() {
    withIsolatedDefaults { defaults in
      defaults.set("not-a-uuid", forKey: PersistenceKeys.lastConnectedDeviceID)
      defaults.set("also-not-a-uuid", forKey: PersistenceKeys.lastConnectedRadioID)
      let store = LastConnectionStore(defaults: defaults)

      #expect(store.deviceID == nil)
      #expect(store.radioID == nil)
    }
  }

  @Test
  func `bond verification round-trips for the stamped device and is nil for others`() {
    withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      let deviceID = UUID()

      #expect(store.bondVerificationDate(for: deviceID) == nil)

      let before = Date()
      store.persistBondVerification(deviceID: deviceID)

      let stamped = store.bondVerificationDate(for: deviceID)
      #expect(stamped != nil)
      if let stamped {
        #expect(stamped >= before && stamped <= Date())
      }
      #expect(store.bondVerificationDate(for: UUID()) == nil)
    }
  }

  @Test
  func `a later bond verification for another device replaces the slot`() {
    withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      let first = UUID()
      let second = UUID()

      store.persistBondVerification(deviceID: first)
      store.persistBondVerification(deviceID: second)

      #expect(store.bondVerificationDate(for: first) == nil)
      #expect(store.bondVerificationDate(for: second) != nil)
    }
  }

  @Test
  func `clear removes the bond verification`() {
    withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      let deviceID = UUID()
      store.persistBondVerification(deviceID: deviceID)

      store.clear()

      #expect(store.bondVerificationDate(for: deviceID) == nil)
    }
  }

  @Test
  func `persistDisconnectDiagnostic prefixes a parseable ISO8601 timestamp`() throws {
    try withIsolatedDefaults { defaults in
      let store = LastConnectionStore(defaults: defaults)
      let summary = "source=unitTest, reason=verifyFormat"

      store.persistDisconnectDiagnostic(summary)

      let stored = try #require(store.disconnectDiagnostic)
      let separatorIndex = try #require(stored.firstIndex(of: " "))
      let timestampPart = String(stored[..<separatorIndex])
      #expect(throws: Never.self) {
        try Date(timestampPart, strategy: .iso8601)
      }
      #expect(String(stored[stored.index(after: separatorIndex)...]) == summary)
    }
  }
}
