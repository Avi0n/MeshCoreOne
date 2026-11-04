import Foundation
import MC1Services

// MARK: - Service Wiring Helpers

extension AppState {
  /// Consume the sync coordinator's data event stream for SwiftUI observation
  /// (actors don't participate in SwiftUI's observation system).
  /// Message events are ignored here; `MessageEventDispatcher` owns them.
  /// Re-subscribes per connection because `ServiceContainer` is rebuilt.
  func wireSyncDataEvents(snapshot: ConnectionSnapshot) {
    let store = connectionManager.persistenceStore
    wireSyncDataEvents(snapshot: snapshot, nodesContactExists: { id in
      try await store.fetchContact(id: id) != nil
    })
  }

  func wireSyncDataEvents(
    snapshot: ConnectionSnapshot,
    nodesContactExists: @escaping @MainActor (UUID) async throws -> Bool
  ) {
    let context = ConnectionCallbackContext(snapshot)
    let services = snapshot.services
    guard context.isCurrent(connectionManager) else { return }
    syncDataEventsTask?.cancel()
    let events = services.syncCoordinator.dataEvents()
    let serviceID = ObjectIdentifier(services)
    syncDataEventsTask = Task { [weak self] in
      for await event in events {
        guard let self, !Task.isCancelled, context.isCurrent(connectionManager) else { return }
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

  /// Consume settings service event stream.
  /// Updates connectedDevice when settings are changed via SettingsService.
  func wireSettingsEventStream(snapshot: ConnectionSnapshot) async {
    let context = ConnectionCallbackContext(snapshot)
    let services = snapshot.services
    guard context.isCurrent(connectionManager) else { return }
    let events = await services.settingsService.events()
    guard !Task.isCancelled, context.isCurrent(connectionManager) else { return }
    settingsEventsTask?.cancel()
    settingsEventsTask = Task { [weak self] in
      for await event in events {
        guard let self, !Task.isCancelled, context.isCurrent(connectionManager) else { return }
        switch event {
        case let .nameUpdated(name):
          await self.connectionManager.applyNodeName(name)
        case let .coordinatesUpdated(latitude, longitude):
          await self.connectionManager.applyCoordinates(latitude: latitude, longitude: longitude)
        case let .deviceObserved(info):
          await self.connectionManager.applyDeviceObservation(info, from: services)
        case let .radioUpdated(change):
          await self.connectionManager.applyRadio(change)
        case let .txPowerUpdated(power):
          await self.connectionManager.applyTxPower(power)
        case let .otherParamsUpdated(intent):
          await self.connectionManager.applyOtherParams(intent)
        case let .autoAddConfigUpdated(config):
          self.connectionManager.updateAutoAddConfig(config)
          // Clear storage full flag when overwrite oldest is enabled.
          if config.bitmask & AutoAddConfig.overwriteOldestBit != 0 {
            self.connectionUI.isNodeStorageFull = false
          }
        case let .clientRepeatUpdated(enabled):
          self.connectionManager.updateClientRepeat(enabled)
        case let .pathHashModeUpdated(mode):
          self.connectionManager.updatePathHashMode(mode)
        case let .allowedRepeatFreqUpdated(ranges):
          self.connectionManager.allowedRepeatFreqRanges = ranges
        case let .defaultFloodScopeUpdated(name):
          self.connectionManager.updateDefaultFloodScopeName(name)
        case .radioWiFiChanged:
          radioWiFiSettingsRevision &+= 1
        }
      }
    }
  }

  /// Wire device update and contact change callbacks.
  /// Updates connectedDevice when local device settings (like OCV) are changed via DeviceService,
  /// and handles contact updates/deletions for real-time Discover page updates.
  func wireDeviceUpdateCallbacks(snapshot: ConnectionSnapshot) async {
    let context = ConnectionCallbackContext(snapshot)
    let services = snapshot.services
    guard context.isCurrent(connectionManager) else { return }
    await services.deviceService.setDeviceUpdateCallback { [weak self] deviceDTO in
      await MainActor.run {
        guard let self, context.isCurrent(self.connectionManager) else { return }
        self.connectionManager.updateDevice(with: deviceDTO)
      }
    }

    guard !Task.isCancelled, context.isCurrent(connectionManager) else { return }

    // Re-subscribe per connection to refresh Discover and apply committed deletions
    // to the current selection and badge after captured notification cleanup.
    advertisementEventsTask?.cancel()
    let advertisementEvents = services.advertisementService.events()
    let notificationService = services.notificationService
    advertisementEventsTask = Task { [weak self] in
      for await event in advertisementEvents {
        guard let self, !Task.isCancelled, context.isCurrent(connectionManager) else { return }
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
          if !contactIDs.isEmpty {
            await notificationService.updateBadgeCount { [weak self] in
              guard let self else { return false }
              return context.isCurrent(connectionManager)
            }
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
  func wireMessageEvents(snapshot: ConnectionSnapshot) {
    guard connectionManager.isCurrent(snapshot) else { return }
    messageEventDispatcher.wire(snapshot: snapshot)
  }

  /// Wire Live Activity callbacks for RX freshness, battery, and connection lifecycle.
  func wireLiveActivityCallbacks(snapshot: ConnectionSnapshot) async {
    let context = ConnectionCallbackContext(snapshot)
    let services = snapshot.services
    guard context.isCurrent(connectionManager) else { return }
    // Every received RF packet refreshes Live Activity freshness and may
    // trigger an overdue battery read. Re-subscribes per connection
    // because ServiceContainer is rebuilt.
    rxLogEventsTask?.cancel()
    let rxLogEntries = services.rxLogService.entryStream()
    rxLogEventsTask = Task { [weak self] in
      for await _ in rxLogEntries {
        guard let self, !Task.isCancelled, context.isCurrent(connectionManager) else { return }
        await liveActivityManager.handlePacketReceived()
        guard !Task.isCancelled, context.isCurrent(connectionManager) else { return }
        if liveActivityManager.hasActiveActivity {
          await batteryMonitor.fetchBatteryIfOverdue(
            services: services, device: snapshot.device
          )
        }
      }
    }

    batteryMonitor.onBatteryChanged = { [weak self] battery in
      Task { @MainActor [weak self] in
        guard let self, context.isCurrent(connectionManager) else { return }
        await liveActivityManager.handleBatteryChanged(battery: battery)
      }
    }

    let device = snapshot.device
    let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
    let unreadCount = await totalUnreadCount(from: services)

    guard !Task.isCancelled, context.isCurrent(connectionManager) else { return }
    await liveActivityManager.handleConnectionReady(
      device: device,
      ocvArray: ocvArray,
      unreadCount: unreadCount,
      isCurrent: { [weak self] in
        guard let self else { return false }
        return context.isCurrent(connectionManager)
      }
    )
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
          let snapshot = connectionManager.currentConnection,
          connectionManager.isCurrent(snapshot) else { return }
    let device = snapshot.device
    let services = snapshot.services
    let context = ConnectionCallbackContext(snapshot)

    let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
    let unreadCount = await totalUnreadCount(from: services)
    guard connectionManager.isCurrent(snapshot) else { return }
    await liveActivityManager.handleConnectionReady(
      device: device,
      ocvArray: ocvArray,
      unreadCount: unreadCount,
      isCurrent: { [weak self] in
        guard let self else { return false }
        return context.isCurrent(connectionManager)
      }
    )
  }

  func totalUnreadCount(from services: ServiceContainer) async -> Int {
    guard let radioID = currentRadioID else { return 0 }
    return await conversationUnreadOperations.read(services.dataStore, radioID)
  }
}
