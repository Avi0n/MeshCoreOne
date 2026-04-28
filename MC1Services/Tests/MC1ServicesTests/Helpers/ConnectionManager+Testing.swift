import Foundation
import SwiftData
@testable import MC1Services

extension ConnectionManager {
    static func createForTesting(
        defaults: UserDefaults? = nil
    ) throws -> (ConnectionManager, MockBLEStateMachine) {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let mock = MockBLEStateMachine()
        let manager: ConnectionManager
        if let defaults {
            manager = ConnectionManager(modelContainer: container, defaults: defaults, stateMachine: mock)
        } else {
            manager = ConnectionManager(modelContainer: container, stateMachine: mock)
        }
        return (manager, mock)
    }

    @MainActor
    static func createForPairingTesting(
        defaults: UserDefaults? = nil,
        transport: MockMeshTransport? = nil,
        accessorySetupKit: MockAccessorySetupKitService? = nil
    ) throws -> PairingTestEnvironment {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let stateMachine = MockBLEStateMachine()
        let mockTransport = transport ?? MockMeshTransport()
        let mockASK = accessorySetupKit ?? MockAccessorySetupKitService()

        let createdSuiteName: String?
        let resolvedDefaults: UserDefaults
        if let defaults {
            resolvedDefaults = defaults
            createdSuiteName = nil
        } else {
            // Per-test suite isolates UserDefaults so parallel runs don't leak
            // connectionIntent into one another's init paths.
            let name = "test.\(UUID().uuidString)"
            resolvedDefaults = UserDefaults(suiteName: name)!
            createdSuiteName = name
        }

        let manager = ConnectionManager(
            modelContainer: container,
            defaults: resolvedDefaults,
            stateMachine: stateMachine,
            transport: mockTransport,
            accessorySetupKit: mockASK
        )

        // Cleanup closure: tests `defer { env.cleanup() }` to remove the persistent
        // suite and avoid plist accumulation in `~/Library/Preferences`. No-op
        // when the caller supplied their own defaults.
        let cleanup: () -> Void = {
            if let name = createdSuiteName {
                UserDefaults().removePersistentDomain(forName: name)
            }
        }

        return PairingTestEnvironment(
            manager: manager,
            stateMachine: stateMachine,
            transport: mockTransport,
            accessorySetupKit: mockASK,
            cleanup: cleanup
        )
    }
}

/// Bundle of mocks and a cleanup closure produced by `createForPairingTesting`.
/// Tests `defer { env.cleanup() }` to release the per-test UserDefaults suite.
@MainActor
struct PairingTestEnvironment {
    let manager: ConnectionManager
    let stateMachine: MockBLEStateMachine
    let transport: MockMeshTransport
    let accessorySetupKit: MockAccessorySetupKitService
    let cleanup: () -> Void
}
