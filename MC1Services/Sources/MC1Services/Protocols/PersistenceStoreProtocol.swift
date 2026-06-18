/// Protocol for PersistenceStore to enable testability of dependent services.
///
/// This protocol abstracts the SwiftData persistence operations used by services,
/// allowing them to be tested with mock implementations. It is a composition of
/// the role protocols under `Protocols/Persistence/`; consumers that touch only
/// one or two store capabilities should declare those roles directly (for
/// example `any MessagePersisting & ContactPersisting`) so their signatures
/// reveal what they use, while broad consumers and conformers keep this
/// umbrella.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyService {
///     private let dataStore: any PersistenceStoreProtocol
///
///     init(dataStore: any PersistenceStoreProtocol) {
///         self.dataStore = dataStore
///     }
/// }
/// ```
public protocol PersistenceStoreProtocol:
    MessagePersisting,
    DevicePersisting,
    ContactPersisting,
    ChannelPersisting,
    TracePathPersisting,
    HeardRepeatPersisting,
    DebugLogPersisting,
    LinkPreviewPersisting,
    RxLogPersisting,
    RoomPersisting,
    DiscoveredNodePersisting,
    ReactionPersisting,
    NodeSnapshotPersisting {}
