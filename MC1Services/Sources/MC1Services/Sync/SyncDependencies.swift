// SyncDependencies.swift
import Foundation

/// The narrow dependency surface `SyncCoordinator` needs to run a sync cycle.
///
/// Built from a live `ServiceContainer` via `ServiceContainer.syncDependencies`,
/// but constructible directly in tests so sync paths can run without the full
/// container. Members are protocol-typed where a seam protocol exists and
/// concrete elsewhere.
struct SyncDependencies: Sendable {

    /// Persistence store for device, contact, channel, message, and RX log operations.
    let dataStore: any PersistenceStoreProtocol

    /// Service performing the contact sync phase.
    let contactService: any ContactServiceProtocol

    /// Service performing the channel sync phase.
    let channelService: any ChannelServiceProtocol

    /// Service for message polling, auto-fetch, and ingestion handler wiring.
    let messagePollingService: any MessagePollingServiceProtocol

    /// Service for posting notifications and gating suppression during sync.
    let notificationService: NotificationService

    /// Service handling emoji reactions on direct and channel messages.
    let reactionService: ReactionService

    /// Service for advertisement events and contact discovery.
    let advertisementService: AdvertisementService

    /// Service maintaining the RX log decryption caches (private key, contact
    /// public keys, channel secrets).
    let rxLogService: RxLogService

    /// Service persisting signed room messages.
    let roomServerService: RoomServerService

    /// Service routing CLI responses from room contacts.
    let roomAdminService: RoomAdminService

    /// Service routing CLI responses from repeater contacts.
    let repeaterAdminService: RepeaterAdminService

    /// Optional provider for foreground/background state. When nil, sync
    /// defaults to foreground behavior (channels sync).
    let appStateProvider: AppStateProvider?

    /// Starts service event monitoring for the connected radio.
    let startEventMonitoring: @Sendable (_ radioID: UUID, _ enableAutoFetch: Bool) async -> Void

    /// Exports the device private key for direct message decryption.
    let exportPrivateKey: @Sendable () async throws -> Data

    init(
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
    var syncDependencies: SyncDependencies {
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
