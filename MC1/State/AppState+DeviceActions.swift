import CoreLocation
import Foundation
import MC1Services

// MARK: - Device Actions

extension AppState {
  func currentConnectionFailure(_ error: any Error) -> (any Error)? {
    if error is CancellationError { return nil }
    if let failure = error as? ConnectionRequestFailure {
      guard connectionManager.isCurrent(failure), !(failure.underlying is CancellationError) else { return nil }
      return failure.underlying
    }
    return error
  }

  /// Retry connecting to the device that just failed without removing the bond.
  /// Used for transient pairing failures where the bond is still good — radio out of range,
  /// brief BLE flap, etc. Auth-failure paths route through `removeFailedPairingAndRetry`
  /// because the bond itself needs to be torn down before retrying.
  func retryFailedPairingConnect() async {
    guard let deviceID = connectionUI.failedPairingDeviceID else { return }
    let episodeID = connectionUI.pairingFailureEpisodeID
    let actionID = connectionUI.beginConnectionAction()
    defer {
      connectionUI.finishConnectionAction(actionID)
    }
    do {
      let result = try await connectionManager.connect(to: deviceID, forceReconnect: true)
      guard case let .operational(snapshot) = result, connectionManager.isCurrent(snapshot) else { return }
      await wireServices(for: snapshot)
      guard connectionManager.isCurrent(snapshot), connectionUI.pairingFailureEpisodeID == episodeID else { return }
      connectionUI.failedPairingDeviceID = nil
    } catch {
      guard connectionUI.pairingFailureEpisodeID == episodeID,
            let failure = currentConnectionFailure(error) else { return }
      if case BLEError.deviceConnectedToOtherApp = failure {
        connectionUI.failedPairingDeviceID = nil
        connectionUI.presentPairingFailure(.deviceConnectedToOtherApp(deviceID: deviceID))
      } else {
        connectionUI.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: failure))
      }
    }
  }

  /// Called by View when scenePhase becomes active.
  func handleBecameActive() {
    logToolbarResumeProbe(source: "becameActive")
    if storeState.service.loadState == .loaded {
      // Re-walk currentEntitlements: a refund may never arrive on Transaction.updates.
      Task { await storeState.service.refreshEntitlements() }
    }
    // Clear the auth-failure latch so a still-invalid bond re-surfaces fresh
    // from the foreground reconnect instead of staying silenced from background.
    connectionManager.clearSurfacedAuthenticationFailure()

    if shouldCompleteFreshPairingOnForeground {
      resumeFreshPairingIfNeeded()
      return
    }

    activeRecoveryFallbackTask?.cancel()
    activeRecoveryFallbackTask = Task { @MainActor [weak self] in
      guard let self else { return }
      try? await Task.sleep(for: .seconds(1))
      guard !Task.isCancelled,
            !connectionManager.shouldDeferOpportunisticReconnect,
            connectionUI.pendingSystemPairingSetup == nil,
            connectionUI.queuedSystemPairingSetup == nil,
            connectionState == .disconnected,
            connectionManager.lastConnectedDeviceID != nil else { return }

      logger.info("[BLE] Active fallback: disconnected after activation, running foreground reconciliation")
      await handleReturnToForeground()
    }
  }

  /// Disconnect from device
  /// - Parameter reason: The reason for disconnecting (for debugging)
  func disconnect(reason: DisconnectReason = .userInitiated) async {
    connectionLinkSwitch = nil
    let receipt = await connectionManager.disconnect(reason: reason)
    guard connectionManager.isCurrent(receipt) else { return }
    tearDownAppStateSessionState()
    await liveActivityManager.endActivity()
  }

  func disconnect(requestID: UUID, reason: DisconnectReason = .statusMenuDisconnectTap) async {
    guard let receipt = await connectionManager.disconnect(requestID: requestID, reason: reason),
          connectionManager.isCurrent(receipt) else { return }
    connectionLinkSwitch = nil
    tearDownAppStateSessionState()
    await liveActivityManager.endActivity()
  }

  /// Asks the paired radio to cut power. The manager disconnects that link only
  /// when the attempt still owns it. A throw leaves a newer connection alone.
  func powerOffRadio() async throws {
    guard services?.settingsService != nil else {
      throw SettingsServiceError.notConnected
    }
    let token = try await connectionManager.powerOffCurrentLink()
    guard connectionManager.powerOffOwnsCleanup(token) else {
      throw PowerOffSuperseded()
    }
    connectionLinkSwitch = nil
    #if DEBUG
      if let powerOffEndActivityOverride {
        await powerOffEndActivityOverride()
      } else {
        await liveActivityManager.endActivity()
      }
    #else
      await liveActivityManager.endActivity()
    #endif
    guard connectionManager.powerOffOwnsCleanup(token) else {
      throw PowerOffSuperseded()
    }
    tearDownAppStateSessionState()
  }

  /// Connect to a device via WiFi/TCP.
  @discardableResult
  func connectViaWiFi(host: String, port: UInt16, forceFullSync: Bool = false) async throws -> ConnectionResult {
    connectionUI.hideDisconnectedPill()
    let result = try await connectionManager.connectViaWiFi(host: host, port: port, forceFullSync: forceFullSync)
    if case let .operational(snapshot) = result {
      await wireServices(for: snapshot)
    }
    return result
  }

  @discardableResult
  func connectViaBluetooth(peripheralID: UUID, forceFullSync: Bool = false) async throws -> ConnectionResult {
    connectionUI.hideDisconnectedPill()
    let result = try await connectionManager.connectViaBluetooth(peripheralID: peripheralID, forceFullSync: forceFullSync)
    if case let .operational(snapshot) = result {
      await wireServices(for: snapshot)
    }
    return result
  }

  /// Starts one link switch. A declined start does not connect and does not change the token.
  func beginLinkSwitch(to target: TransportType, snapshotWiFiHost: String?) async {
    let token = UUID()
    guard let started = ConnectionLinkSwitch.start(
      existing: connectionLinkSwitch,
      target: target,
      liveTransport: connectionManager.currentTransportType,
      radioID: connectedDevice?.radioID,
      deviceID: connectedDevice?.id,
      bluetoothPeripheralID: connectedDevice?.bluetoothPeripheralID,
      wifi: capturedWiFiDial(snapshotHost: snapshotWiFiHost),
      token: token
    ) else { return }

    connectionLinkSwitch = started
    do {
      let result = try await dial(started, to: target)
      guard connectionManager.isCurrent(result) else { return }
      applyConnectionLinkSwitchCompletion(token: token, threw: false)
    } catch {
      guard let failure = currentConnectionFailure(error) else { return }
      connectionUI.presentSavedDeviceConnectFailure(deviceID: failureAlertDeviceID(started, target: target), error: failure)
      applyConnectionLinkSwitchCompletion(token: token, threw: true)
    }
  }

  /// Drops a finished record once a radio is connected, and drops a record for a different radio.
  /// An in-progress attempt for the connected radio stays.
  func reconcileConnectionLinkSwitch() {
    guard let device = connectedDevice, let record = connectionLinkSwitch else { return }
    if record.radioID != device.radioID || record.target == nil {
      connectionLinkSwitch = nil
    }
  }

  private func applyConnectionLinkSwitchCompletion(token: UUID, threw: Bool) {
    guard let record = connectionLinkSwitch else { return }
    switch record.complete(
      token: token,
      threw: threw,
      liveTransport: connectionManager.currentTransportType,
      connectedRadioID: connectedDevice?.radioID
    ) {
    case .ignored:
      break
    case .cleared:
      connectionLinkSwitch = nil
    case let .held(updated):
      connectionLinkSwitch = updated
    }
  }

  private func failureAlertDeviceID(_ record: ConnectionLinkSwitch, target: TransportType) -> UUID {
    switch target {
    case .bluetooth:
      record.bluetoothPeripheralID ?? record.deviceID
    case .wifi:
      record.deviceID
    }
  }

  private func dial(_ record: ConnectionLinkSwitch, to target: TransportType) async throws -> ConnectionResult {
    switch target {
    case .bluetooth:
      guard let peripheralID = record.bluetoothPeripheralID else { return .cancelled }
      return try await connectViaBluetooth(peripheralID: peripheralID)
    case .wifi:
      guard let wifi = record.wifi, let host = wifi.displayHost else { return .cancelled }
      return try await connectViaWiFi(host: host, port: wifi.port)
    }
  }

  private func capturedWiFiDial(snapshotHost: String?) -> ConnectionLinkWiFiDial? {
    ConnectionLinkWiFiDial.capture(
      liveTransport: connectionManager.currentTransportType,
      savedWiFi: connectedDevice?.wifiConnectionMethod,
      snapshotHost: snapshotHost
    )
  }

  // MARK: - Advertising

  /// Broadcast a self-advertisement from the connected radio, refreshing GPS
  /// location first when the device's policy and the user's per-device
  /// preference allow it. `allowLocationPrompt` is false from the App Intent: a
  /// background Siri context cannot present a location-permission dialog, so the
  /// refresh there uses only already-authorized location instead of stalling on
  /// a prompt that can never resolve.
  func sendSelfAdvert(flood: Bool, allowLocationPrompt: Bool = true) async throws {
    if let source = advertGPSSource(device: connectedDevice, store: DevicePreferenceStore()) {
      await updateLocationFromGPS(source: source, allowLocationPrompt: allowLocationPrompt)
    }
    guard let advertisementService = services?.advertisementService else {
      throw AdvertisementError.notConnected
    }
    try await advertisementService.sendSelfAdvertisement(flood: flood)
  }

  /// The GPS source to refresh before an advert, or nil when the device's advert
  /// policy or the user's per-device preference disables auto-update. Pure over
  /// its inputs so the privacy gate is testable with an injected store.
  func advertGPSSource(device: DeviceDTO?, store: DevicePreferenceStore) -> GPSSource? {
    guard let device,
          device.sharesLocationPublicly,
          store.isAutoUpdateLocationEnabled(deviceID: device.id) else {
      return nil
    }
    return store.gpsSource(deviceID: device.id)
  }

  private func updateLocationFromGPS(source: GPSSource, allowLocationPrompt: Bool) async {
    let settingsService = services?.settingsService
    do {
      switch source {
      case .phone:
        let location: CLLocation
        if allowLocationPrompt || locationService.isAuthorized {
          do {
            location = try await locationService.requestCurrentLocation()
          } catch {
            guard let cached = locationService.currentLocation else { throw error }
            location = cached
          }
        } else {
          guard let cached = locationService.currentLocation else { return }
          location = cached
        }
        _ = try await settingsService?.setLocationVerified(
          latitude: location.coordinate.latitude,
          longitude: location.coordinate.longitude
        )
      case .device:
        let gpsState = try await settingsService?.getDeviceGPSState()
        if gpsState?.isEnabled != true {
          _ = try await settingsService?.setDeviceGPSEnabledVerified(true)
        }
      }
    } catch {
      logger.warning("Failed to update location from GPS: \(error.localizedDescription)")
    }
  }
}
