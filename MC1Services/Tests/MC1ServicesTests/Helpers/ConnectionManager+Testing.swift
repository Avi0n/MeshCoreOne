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
    ) throws -> (ConnectionManager, MockBLEStateMachine, MockMeshTransport, MockAccessorySetupKitService) {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let stateMachine = MockBLEStateMachine()
        let mockTransport = transport ?? MockMeshTransport()
        let mockASK = accessorySetupKit ?? MockAccessorySetupKitService()
        let resolvedDefaults = defaults ?? .standard
        let manager = ConnectionManager(
            modelContainer: container,
            defaults: resolvedDefaults,
            stateMachine: stateMachine,
            transport: mockTransport,
            accessorySetupKit: mockASK
        )
        return (manager, stateMachine, mockTransport, mockASK)
    }
}
