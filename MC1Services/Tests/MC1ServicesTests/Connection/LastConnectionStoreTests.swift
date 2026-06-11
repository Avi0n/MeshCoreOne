import Foundation
import Testing
@testable import MC1Services

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

    @Test("persist then read round-trips deviceID, radioID, and deviceName")
    func persistRoundTrip() throws {
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

    @Test("clear removes all three persisted values")
    func clearRemovesAll() throws {
        try withIsolatedDefaults { defaults in
            let store = LastConnectionStore(defaults: defaults)
            store.persist(deviceID: UUID(), radioID: UUID(), deviceName: "Test Radio")

            store.clear()

            #expect(store.deviceID == nil)
            #expect(store.radioID == nil)
            #expect(defaults.string(forKey: PersistenceKeys.lastConnectedDeviceName) == nil)
        }
    }

    @Test("empty defaults read as nil")
    func emptyDefaultsReadNil() {
        withIsolatedDefaults { defaults in
            let store = LastConnectionStore(defaults: defaults)

            #expect(store.deviceID == nil)
            #expect(store.radioID == nil)
            #expect(store.disconnectDiagnostic == nil)
        }
    }

    @Test("malformed UUID strings read as nil")
    func malformedUUIDReadsNil() {
        withIsolatedDefaults { defaults in
            defaults.set("not-a-uuid", forKey: PersistenceKeys.lastConnectedDeviceID)
            defaults.set("also-not-a-uuid", forKey: PersistenceKeys.lastConnectedRadioID)
            let store = LastConnectionStore(defaults: defaults)

            #expect(store.deviceID == nil)
            #expect(store.radioID == nil)
        }
    }

    @Test("persistDisconnectDiagnostic prefixes a parseable ISO8601 timestamp")
    func disconnectDiagnosticTimestampPrefix() throws {
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
