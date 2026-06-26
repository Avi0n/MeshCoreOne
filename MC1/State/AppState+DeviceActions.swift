import CoreLocation
import Foundation
import MC1Services

// MARK: - Device Actions

extension AppState {

    /// Start device scan/pairing
    func startDeviceScan() {
        // Hide disconnected pill when starting new connection
        connectionUI.hideDisconnectedPill()
        // Clear any previous pairing failure state
        connectionUI.failedPairingDeviceID = nil
        connectionUI.isBusy = true

        Task {
            defer { connectionUI.isBusy = false }

            do {
                // pairNewDevice() triggers onConnectionReady callback on success
                try await connectionManager.pairNewDevice()
                await wireServicesIfConnected()

                // If still in onboarding, navigate to region step; otherwise mark complete
                if !onboarding.hasCompletedOnboarding {
                    onboarding.onboardingPath.append(.region)
                }
            } catch DevicePairingError.cancelled {
                // User cancelled - no error
            } catch DevicePairingError.alreadyInProgress {
                // Picker is already showing - ignore
            } catch let pairingError as PairingError {
                connectionUI.presentPairingFailure(pairingError)
            } catch {
                connectionUI.presentConnectionFailure(message: error.userFacingMessage)
            }
        }
    }

    /// Remove a device that failed pairing (wrong PIN) and automatically retry
    func removeFailedPairingAndRetry() {
        guard let deviceID = connectionUI.failedPairingDeviceID else { return }

        Task {
            await connectionManager.removeFailedPairing(deviceID: deviceID)
            connectionUI.failedPairingDeviceID = nil
            // Set flag - View observing scenePhase will trigger startDeviceScan when active
            connectionUI.shouldShowPickerOnForeground = true
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

    /// Called by View when scenePhase becomes active and shouldShowPickerOnForeground is true
    func handleBecameActive() {
        if connectionUI.shouldShowPickerOnForeground {
            connectionUI.shouldShowPickerOnForeground = false
            startDeviceScan()
        }

        activeRecoveryFallbackTask?.cancel()
        activeRecoveryFallbackTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            guard self.connectionState == .disconnected,
                  self.connectionManager.lastConnectedDeviceID != nil else { return }

            self.logger.info("[BLE] Active fallback: disconnected after activation, running foreground reconciliation")
            await self.handleReturnToForeground()
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
