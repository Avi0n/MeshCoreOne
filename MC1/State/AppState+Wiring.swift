import Foundation
import MC1Services

// MARK: - Service Wiring Helpers

extension AppState {
  /// Consume the sync coordinator's data event stream for SwiftUI observation
  /// (actors don't participate in SwiftUI's observation system).
  /// Message events are ignored here; `MessageEventDispatcher` owns them.
  /// Re-subscribes per connection because `ServiceContainer` is rebuilt.
  func wireSyncDataEvents(services: ServiceContainer) {
    let store = connectionManager.persistenceStore
    wireSyncDataEvents(services: services, nodesContactExists: { id in
      try await store.fetchContact(id: id) != nil
    })
  }

  func wireSyncDataEvents(
    services: ServiceContainer,
    nodesContactExists: @escaping @MainActor (UUID) async throws -> Bool
  ) {
    syncDataEventsTask?.cancel()
    let events = services.syncCoordinator.dataEvents()
    let serviceID = ObjectIdentifier(services)
    syncDataEventsTask = Task { [weak self] in
      for await event in events {
        guard let self else { return }
        switch event {
        case .contactsChanged:
          contactsVersion += 1
          await invalidateMissingNodesContact(from: serviceID, contactExists: nodesContactExists)
        case .conversationsChanged:
          refreshConversations()
        case .directMessageReceived, .channelMessageReceived, .roomMessageReceived, .reactionReceived:
          break
        }
      }
    }
  }

  /// Updates connectedDevice from the settings stream of this container.
  /// Events are ignored once `services` no longer points at that container.
  func wireSettingsEventStream(services: ServiceContainer) async {
    settingsEventsTask?.cancel()
    let events = await services.settingsService.events()
    let producer = services
    settingsEventsTask = Task { [weak self] in
      for await event in events {
        guard let self, self.connectionManager.services === producer else { return }
        switch event {
        case let .nameUpdated(name):
          await self.connectionManager.applyNodeName(name)
        case let .coordinatesUpdated(latitude, longitude):
          await self.connectionManager.applyCoordinates(latitude: latitude, longitude: longitude)
        case let .deviceObserved(info):
          await self.connectionManager.applyDeviceObservation(info, from: producer)
        case let .radioUpdated(change):
          await self.connectionManager.applyRadio(change)
        case let .txPowerUpdated(power):
          await self.connectionManager.applyTxPower(power)
        case let .otherParamsUpdated(intent):
          await self.connectionManager.applyOtherParams(intent)
        case let .autoAddConfigUpdated(config):
          await MainActor.run {
            self.connectionManager.updateAutoAddConfig(config)
            // Clear storage full flag when overwrite oldest is enabled
            if config.bitmask & AutoAddConfig.overwriteOldestBit != 0 {
              self.connectionUI.isNodeStorageFull = false
            }
          }
        case let .clientRepeatUpdated(enabled):
          await MainActor.run {
            self.connectionManager.updateClientRepeat(enabled)
          }
        case let .pathHashModeUpdated(mode):
          await MainActor.run {
            self.connectionManager.updatePathHashMode(mode)
          }
        case let .allowedRepeatFreqUpdated(ranges):
          await MainActor.run {
            self.connectionManager.allowedRepeatFreqRanges = ranges
          }
        case let .defaultFloodScopeUpdated(name):
          await MainActor.run {
            self.connectionManager.updateDefaultFloodScopeName(name)
          }
        }
      }
    }
  }

  /// Wire device update and contact change callbacks.
  /// Updates connectedDevice when local device settings (like OCV) are changed via DeviceService,
  /// and handles contact updates/deletions for real-time Discover page updates.
  func wireDeviceUpdateCallbacks(services: ServiceContainer) async {
    await services.deviceService.setDeviceUpdateCallback { [weak self] deviceDTO in
      await MainActor.run {
        self?.connectionManager.updateDevice(with: deviceDTO)
      }
    }

    // Contact updates bump contactsVersion for real-time Discover page
    // updates; contact-deleted cleanup removes notifications and refreshes
    // the badge when the device auto-deletes a contact via 0x8F.
    // Re-subscribes per connection because ServiceContainer is rebuilt.
    advertisementEventsTask?.cancel()
    let advertisementEvents = services.advertisementService.events()
    // ConnectionManager nils `services` before `tearDown` yields `.contactDeletedCleanup`.
    // Capture `notificationService` so `removeDeliveredNotifications` still runs.
    let notificationService = services.notificationService
    advertisementEventsTask = Task { [weak self] in
      for await event in advertisementEvents {
        guard let self else { return }
        switch event {
        case .contactUpdated:
          contactsVersion += 1
          PersistentLogger(subsystem: "com.mc1", category: "discover-trace")
            .debug("B4 contactUpdated bump contactsVersion=\(contactsVersion)")
        case .conversationsChanged:
          refreshConversations()
        case let .contactDeletedCleanup(contactIDs):
          if let selected = navigation.selectedContact, contactIDs.contains(selected.id) {
            navigation.clearSelectedContact(matching: selected.id)
          }
          await notificationService.removeDeliveredNotifications(
            forContactIDs: Set(contactIDs)
          )
          if !contactIDs.isEmpty {
            await notificationService.updateBadgeCount()
          }
        case .newContactDiscovered, .nodeStorageFullChanged,
             .pathDiscoveryResponse, .pathDiscoveryWriteFailed, .traceResponse,
             .traceSnrObserved, .orphanDirectMessagesAdopted:
          // The paired .conversationsChanged already refreshes the list; the
          // banner and badge are handled by the SyncCoordinator consumer.
          break
        }
      }
    }
  }

  /// Wire the message event streams. Delegates to `MessageEventDispatcher`,
  /// which subscribes to the service event streams and fans them out to
  /// `messageEventStream` and `sessionStateChangeCount`.
  func wireMessageEvents(services: ServiceContainer) {
    messageEventDispatcher.wire(services: services)
  }

  /// Wire Live Activity callbacks for RX freshness, battery, and connection lifecycle.
  func wireLiveActivityCallbacks(services: ServiceContainer) async {
    // Every received RF packet refreshes Live Activity freshness and may
    // trigger an overdue battery read. Re-subscribes per connection
    // because ServiceContainer is rebuilt.
    rxLogEventsTask?.cancel()
    let rxLogEntries = services.rxLogService.entryStream()
    rxLogEventsTask = Task { [weak self] in
      for await _ in rxLogEntries {
        guard let self else { return }
        await liveActivityManager.handlePacketReceived()
        if liveActivityManager.hasActiveActivity {
          await batteryMonitor.fetchBatteryIfOverdue(
            services: self.services, device: connectedDevice
          )
        }
      }
    }

    batteryMonitor.onBatteryChanged = { [weak self] battery in
      Task { @MainActor [weak self] in
        await self?.liveActivityManager.handleBatteryChanged(battery: battery)
      }
    }

    let device = connectedDevice
    let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
    let unreadCount = await totalUnreadCount(from: services)

    if let device {
      await liveActivityManager.handleConnectionReady(
        device: device,
        ocvArray: ocvArray,
        unreadCount: unreadCount
      )
    }
  }

  /// `Activity.request` only succeeds in the foreground, so a `startActivity`
  /// triggered from background (e.g. BLE auto-reconnect after the disconnect
  /// grace timer fired) throws and leaves `currentActivity` nil. iOS may also
  /// end an activity from background — 8-hour active cap, 12-hour total, or
  /// memory pressure — in which case the `.dismissed` branch in
  /// `validateActivityState` clears the reference without restarting. Both
  /// leave the radio connected with no LA on screen.
  func restartLiveActivityIfMissing() async {
    guard connectionState.isConnected,
          liveActivityManager.isEnabled,
          !liveActivityManager.hasActiveActivity,
          let device = connectedDevice,
          let services else { return }

    let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
    let unreadCount = await totalUnreadCount(from: services)
    await liveActivityManager.handleConnectionReady(
      device: device,
      ocvArray: ocvArray,
      unreadCount: unreadCount
    )
  }

  func totalUnreadCount(from services: ServiceContainer) async -> Int {
    guard let radioID = currentRadioID else { return 0 }
    return await conversationUnreadOperations.read(services.dataStore, radioID)
  }
}
