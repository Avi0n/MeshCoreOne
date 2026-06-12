// SyncDependencies.swift
import Foundation

/// The narrow dependency surface `SyncCoordinator` needs to run a sync cycle.
///
/// Built from a live `ServiceContainer` via `ServiceContainer.syncDependencies`,
/// but constructible directly in tests so sync paths can run without the full
/// container. Members are protocol-typed where a seam protocol exists and
/// concrete elsewhere.
public struct SyncDependencies: Sendable {

    /// Persistence store for device, contact, channel, message, and RX log operations.
    public let dataStore: any PersistenceStoreProtocol

    /// Service performing the contact sync phase.
    public let contactService: any ContactServiceProtocol

    /// Service performing the channel sync phase.
    public let channelService: any ChannelServiceProtocol

    /// Service for message polling, auto-fetch, and ingestion handler wiring.
    public let messagePollingService: any MessagePollingServiceProtocol

    /// Service for posting notifications and gating suppression during sync.
    public let notificationService: NotificationService

    /// Service handling emoji reactions on direct and channel messages.
    public let reactionService: ReactionService

    /// Service for advertisement events and contact discovery.
    public let advertisementService: AdvertisementService

    /// Service maintaining the RX log decryption caches (private key, contact
    /// public keys, channel secrets).
    public let rxLogService: RxLogService

    /// Service persisting signed room messages.
    public let roomServerService: RoomServerService

    /// Service routing CLI responses from room contacts.
    public let roomAdminService: RoomAdminService

    /// Service routing CLI responses from repeater contacts.
    public let repeaterAdminService: RepeaterAdminService

    /// Optional provider for foreground/background state. When nil, sync
    /// defaults to foreground behavior (channels sync).
    public let appStateProvider: AppStateProvider?

    /// Starts service event monitoring for the connected radio.
    public let startEventMonitoring: @Sendable (_ radioID: UUID, _ enableAutoFetch: Bool) async -> Void

    /// Exports the device private key for direct message decryption.
    public let exportPrivateKey: @Sendable () async throws -> Data

    public init(
        dataStore: any PersistenceStoreProtocol,
        contactService: any ContactServiceProtocol,
        channelService: any ChannelServiceProtocol,
        messagePollingService: any MessagePollingServiceProtocol,
        notificationService: NotificationService,
        reactionService: ReactionService,
        advertisementService: AdvertisementService,
        rxLogService: RxLogService,
        roomServerService: RoomServerService,
        roomAdminService: RoomAdminService,
        repeaterAdminService: RepeaterAdminService,
        appStateProvider: AppStateProvider? = nil,
        startEventMonitoring: @escaping @Sendable (_ radioID: UUID, _ enableAutoFetch: Bool) async -> Void,
        exportPrivateKey: @escaping @Sendable () async throws -> Data
    ) {
        self.dataStore = dataStore
        self.contactService = contactService
        self.channelService = channelService
        self.messagePollingService = messagePollingService
        self.notificationService = notificationService
        self.reactionService = reactionService
        self.advertisementService = advertisementService
        self.rxLogService = rxLogService
        self.roomServerService = roomServerService
        self.roomAdminService = roomAdminService
        self.repeaterAdminService = repeaterAdminService
        self.appStateProvider = appStateProvider
        self.startEventMonitoring = startEventMonitoring
        self.exportPrivateKey = exportPrivateKey
    }
}

extension ServiceContainer {

    /// Builds the sync dependency surface from this container's services.
    ///
    /// `startEventMonitoring` captures the container weakly: the wired sync
    /// closures hold a `SyncDependencies` copy, and a strong container capture
    /// here would keep a torn-down service graph alive across reconnects.
    public var syncDependencies: SyncDependencies {
        SyncDependencies(
            dataStore: dataStore,
            contactService: contactService,
            channelService: channelService,
            messagePollingService: messagePollingService,
            notificationService: notificationService,
            reactionService: reactionService,
            advertisementService: advertisementService,
            rxLogService: rxLogService,
            roomServerService: roomServerService,
            roomAdminService: roomAdminService,
            repeaterAdminService: repeaterAdminService,
            appStateProvider: appStateProvider,
            startEventMonitoring: { [weak self] radioID, enableAutoFetch in
                await self?.startEventMonitoring(radioID: radioID, enableAutoFetch: enableAutoFetch)
            },
            exportPrivateKey: { [session] in
                try await session.exportPrivateKey()
            }
        )
    }
}
