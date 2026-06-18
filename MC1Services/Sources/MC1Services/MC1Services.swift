// MC1Services - iOS-specific services for MeshCore One
// Re-exports MeshCore so consumers only need to import MC1Services

@_exported import MeshCore

/// MC1Services provides iOS-specific implementations on top of MeshCore:
/// - SwiftData models (Device, Contact, Message, Channel)
/// - PersistenceStore (@ModelActor for SwiftData operations)
/// - iOS BLE transport with state restoration and background mode
/// - Notification, Keychain, and AccessorySetupKit services
/// - High-level service layer (ContactService, MessageService, ChannelService)
enum MC1ServicesVersion {
    static let version = "0.1.0"
}

// MARK: - Type Aliases

/// Alias for PersistenceStore (backwards compatibility)
public typealias DataStore = PersistenceStore

/// Alias for StatusResponse (backwards compatibility with PocketMeshKit)
public typealias RemoteNodeStatus = StatusResponse

/// Alias for Neighbour (backwards compatibility with PocketMeshKit naming)
public typealias NeighbourInfo = Neighbour
