import CoreLocation
import Foundation
import MC1Services

// MARK: - Device Actions

extension AppState {
  /// ASK Remove Accessory leaves the scene unable to present `showPicker`.
  /// Retry once after a short delay in case `.active` already fired.
  private static let freshPairingForegroundRetryDelay: Duration = .milliseconds(400)

  /// Start device scan/pairing
  func startDeviceScan() {
    // Hide disconnected pill when starting new connection
    connectionUI.hideDisconnectedPill()
    // Clear any previous pairing failure state
    connectionUI.failedPairingDeviceID = nil
    connectionUI.isBusy = true
    connectionManager.isPairingFlowActive = true

    Task {
      defer {
        connectionUI.isBusy = false
        if connectionUI.pendingSystemPairingSetup == nil,
           connectionUI.queuedSystemPairingSetup == nil,
           !connectionUI.shouldCompleteFreshPairingOnForeground {
          connectionManager.isPairingFlowActive = false
        }
      }
      await withPairingFlowErrorHandling {
        let pending = try await connectionManager.systemAccessoriesMissingDeviceRecord()
        if !pending.isEmpty {
          if connectionUI.pendingSystemPairingSetup == nil {
            connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
              accessories: pending.map { SystemPairedAccessory(id: $0.id, name: $0.name) }
            )
          }
          return
        }
        try await completeFreshPairing()
      }
    }
  }

  func handleDeviceSelectionSheetDismissed() {
    if connectionUI.queuedDeviceScanAfterSelectionDismiss {
      connectionUI.queuedDeviceScanAfterSelectionDismiss = false
      connectionUI.queuedSystemPairingSetup = nil
      connectionManager.isPairingFlowActive = true
      Task {
        await connectionManager.stopBLEScanning()
        startDeviceScan()
      }
      return
    }
    guard let queued = connectionUI.queuedSystemPairingSetup else { return }
    connectionUI.queuedSystemPairingSetup = nil
    guard connectionUI.pendingSystemPairingSetup == nil else { return }
    connectionManager.isPairingFlowActive = true
    connectionUI.pendingSystemPairingSetup = queued
  }

  func cancelSystemPairingSetup() {
    connectionUI.pendingSystemPairingSetup = nil
    connectionUI.queuedSystemPairingSetup = nil
    connectionUI.shouldCompleteFreshPairingOnForeground = false
    isFreshPairingForegroundRetry = false
    connectionManager.isPairingFlowActive = false
  }

  func handleSystemPairingSetupSheetDismissed() {
    guard !isConfirmingSystemPairingSetup else { return }
    guard connectionUI.pendingSystemPairingSetup == nil else { return }
    cancelSystemPairingSetup()
  }

  /// Forget the pending ASK accessories, then open the picker. Clears pending first so
  /// iOS Remove Accessory is the only dialog; siblings do not re-prompt.
  func confirmSystemPairingSetup() {
    guard let prompt = connectionUI.pendingSystemPairingSetup else { return }
    isConfirmingSystemPairingSetup = true
    connectionManager.isPairingFlowActive = true
    connectionUI.shouldShowPickerOnForeground = false
    connectionUI.pendingSystemPairingSetup = nil
    connectionUI.isBusy = true
    Task {
      defer {
        connectionUI.isBusy = false
        isConfirmingSystemPairingSetup = false
        if !connectionUI.shouldCompleteFreshPairingOnForeground {
          connectionManager.isPairingFlowActive = false
        }
      }
      await withPairingFlowErrorHandling {
        try await connectionManager.removeSystemAccessoriesMissingDeviceRecord(prompt.accessories.map(\.id))
        try await completeFreshPairing()
      }
    }
  }

  private func completeFreshPairing() async throws {
    if connectionState != .disconnected {
      await disconnect(reason: .switchingDevice)
    }
    try await connectionManager.pairNewDevice()
    await wireServicesIfConnected()
    if !onboarding.hasCompletedOnboarding {
      onboarding.onboardingPath.append(.region)
    }
  }

  private func withPairingFlowErrorHandling(_ work: () async throws -> Void) async {
    do {
      try await work()
    } catch DevicePairingError.cancelled {
      // Picker dismissed, or iOS Remove Accessory declined. Do not re-prompt.
      connectionUI.shouldCompleteFreshPairingOnForeground = false
      isFreshPairingForegroundRetry = false
    } catch DevicePairingError.alreadyInProgress {
    } catch DevicePairingError.pickerUnavailable {
      handlePickerUnavailableAfterForget()
    } catch let pairingError as PairingError {
      connectionUI.presentFreshPairingFailure(pairingError)
    } catch {
      connectionUI.presentConnectionFailure(message: error.userFacingMessage)
    }
  }

  /// ASK rejected the picker because Remove Accessory still owns the scene.
  /// Retry `pairNewDevice` on the next `.active` (and once after a short delay
  /// if that transition already happened). A second rejection is a real failure.
  private func handlePickerUnavailableAfterForget() {
    if isFreshPairingForegroundRetry {
      isFreshPairingForegroundRetry = false
      connectionUI.shouldCompleteFreshPairingOnForeground = false
      connectionManager.isPairingFlowActive = false
      connectionUI.presentConnectionFailure(
        message: L10n.Localizable.Error.AccessorySetup.pickerRestricted
      )
      return
    }
    scheduleFreshPairingOnForeground()
  }

  private func scheduleFreshPairingOnForeground() {
    connectionUI.shouldCompleteFreshPairingOnForeground = true
    connectionManager.isPairingFlowActive = true
    Task {
      try? await Task.sleep(for: Self.freshPairingForegroundRetryDelay)
      await resumeFreshPairingIfNeeded()
    }
  }

  private func resumeFreshPairingIfNeeded() async {
    guard connectionUI.shouldCompleteFreshPairingOnForeground else { return }
    connectionUI.shouldCompleteFreshPairingOnForeground = false
    isFreshPairingForegroundRetry = true
    connectionUI.isBusy = true
    connectionManager.isPairingFlowActive = true
    defer {
      connectionUI.isBusy = false
      isFreshPairingForegroundRetry = false
      if !connectionUI.shouldCompleteFreshPairingOnForeground {
        connectionManager.isPairingFlowActive = false
      }
    }
    await withPairingFlowErrorHandling {
      try await completeFreshPairing()
    }
  }

  /// Remove a device that failed pairing (wrong PIN) and automatically retry
  func removeFailedPairingAndRetry() {
    guard let deviceID = connectionUI.failedPairingDeviceID else { return }

    Task {
      await connectionManager.removeFailedPairing(deviceID: deviceID)
      connectionUI.failedPairingDeviceID = nil
      if connectionManager.hasSystemPairingRegistry {
        // Remove Accessory can bounce the scene; wait for `.active`.
        connectionUI.shouldShowPickerOnForeground = true
      } else {
        // No registry confirmation, so `.active` will not re-fire.
        startDeviceScan()
      }
    }
  }

  /// Retry connecting to the device that just failed without removing the bond.
  /// Used for transient pairing failures where the bond is still good — radio out of range,
  /// brief BLE flap, etc. Auth-failure paths route through `removeFailedPairingAndRetry`
  /// because the bond itself needs to be torn down before retrying.
  func retryFailedPairingConnect() async {
    guard let deviceID = connectionUI.failedPairingDeviceID else { return }
    connectionUI.isBusy = true
    defer { connectionUI.isBusy = false }

    do {
      try await connectionManager.connect(to: deviceID, forceReconnect: true)
      connectionUI.failedPairingDeviceID = nil
      await wireServicesIfConnected()
    } catch BLEError.deviceConnectedToOtherApp {
      connectionUI.failedPairingDeviceID = nil
      connectionUI.presentPairingFailure(.deviceConnectedToOtherApp(deviceID: deviceID))
    } catch {
      connectionUI.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: error))
    }
  }

  /// Called by View when scenePhase becomes active.
  func handleBecameActive() {
    // Clear the auth-failure latch so a still-invalid bond re-surfaces fresh
    // from the foreground reconnect instead of staying silenced from background.
    connectionManager.clearSurfacedAuthenticationFailure()

    if connectionUI.shouldCompleteFreshPairingOnForeground {
      Task { await resumeFreshPairingIfNeeded() }
      return
    }

    if connectionUI.shouldShowPickerOnForeground,
       connectionUI.pendingSystemPairingSetup == nil,
       connectionUI.queuedSystemPairingSetup == nil {
      connectionUI.shouldShowPickerOnForeground = false
      startDeviceScan()
    }

    activeRecoveryFallbackTask?.cancel()
    activeRecoveryFallbackTask = Task { @MainActor [weak self] in
      guard let self else { return }
      try? await Task.sleep(for: .seconds(1))
      guard !Task.isCancelled else { return }
      guard !connectionManager.shouldDeferOpportunisticReconnect,
            connectionUI.pendingSystemPairingSetup == nil,
            connectionUI.queuedSystemPairingSetup == nil else { return }
      guard connectionState == .disconnected,
            connectionManager.lastConnectedDeviceID != nil else { return }

      logger.info("[BLE] Active fallback: disconnected after activation, running foreground reconciliation")
      await handleReturnToForeground()
    }
  }

  /// Disconnect from device
  /// - Parameter reason: The reason for disconnecting (for debugging)
  func disconnect(reason: DisconnectReason = .userInitiated) async {
    await connectionManager.disconnect(reason: reason)
    await liveActivityManager.endActivity()
    // Explicit disconnect does not fire onConnectionLost, so run the same
    // per-session teardown the loss path performs in wireServicesIfConnected.
    tearDownAppStateSessionState()
  }

  /// Connect to a device via WiFi/TCP
  func connectViaWiFi(host: String, port: UInt16, forceFullSync: Bool = false) async throws {
    // Hide disconnected pill when starting new connection
    connectionUI.hideDisconnectedPill()
    try await connectionManager.connectViaWiFi(host: host, port: port, forceFullSync: forceFullSync)
    await wireServicesIfConnected()
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
