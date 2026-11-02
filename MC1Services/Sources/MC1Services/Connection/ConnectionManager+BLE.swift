@preconcurrency import CoreBluetooth
import MeshCore

extension ConnectionManager {
  // MARK: - BLE Diagnostics

  /// Returns a best-effort snapshot of the BLE state machine for debug exports.
  public func currentBLEDiagnosticsSummary() async -> String {
    let diagnostics = await stateMachine.linkDiagnostics
    let bleState = diagnostics.centralState
    let blePhase = diagnostics.phase
    let blePeripheralState = diagnostics.peripheralState ?? "none"
    let isConnected = await stateMachine.isConnected
    let isAutoReconnecting = await stateMachine.isAutoReconnecting
    let connectedDeviceShort = await stateMachine.connectedDeviceID?.uuidString.prefix(8) ?? "none"
    let sessionPresent = session != nil
    let servicesPresent = services != nil
    let eventMonitoringActive = services?.isEventMonitoringActive ?? false
    let autoFetchActive: Bool = if let services {
      await services.messagePollingService.isAutoFetching
    } else {
      false
    }
    let activeReconnectDeviceShort = reconnectionCoordinator.reconnectingDeviceID?.uuidString.prefix(8) ?? "none"
    return
      "BLE: state=\(bleState), " +
      "phase=\(blePhase), " +
      "peripheralState=\(blePeripheralState), " +
      "isConnected=\(isConnected), " +
      "isAutoReconnecting=\(isAutoReconnecting), " +
      "connectedDevice=\(connectedDeviceShort), " +
      "sessionPresent=\(sessionPresent), " +
      "servicesPresent=\(servicesPresent), " +
      "eventMonitoringActive=\(eventMonitoringActive), " +
      "autoFetchActive=\(autoFetchActive), " +
      "activeReconnectDevice=\(activeReconnectDeviceShort)"
  }

  // MARK: - BLE Device Checks

  /// Checks if a device is connected to the system by another app.
  /// Returns false during auto-reconnect or when the device is already connected by us.
  /// - Parameter deviceID: The UUID of the device to check
  /// - Returns: `true` if device appears connected to another app
  public func isDeviceConnectedToOtherApp(_ deviceID: UUID) async -> Bool {
    let isAutoReconnecting = await stateMachine.isAutoReconnecting
    let smIsConnected = await stateMachine.isConnected
    let smConnectedDeviceID = await stateMachine.connectedDeviceID
    let systemConnected = await stateMachine.isDeviceConnectedToSystem(deviceID)
    let allSystemConnected = await stateMachine.systemConnectedPeripheralIDs()

    logger.info(
      "[OtherAppCheck] device=\(deviceID.uuidString.prefix(8)), " +
        "connectionState=\(String(describing: connectionState)), " +
        "isAutoReconnecting=\(isAutoReconnecting), " +
        "smIsConnected=\(smIsConnected), " +
        "smConnectedDevice=\(smConnectedDeviceID?.uuidString.prefix(8) ?? "nil"), " +
        "isDeviceConnectedToSystem=\(systemConnected), " +
        "allSystemConnectedCount=\(allSystemConnected.count), " +
        "allSystemConnected=\(allSystemConnected.map { String($0.uuidString.prefix(8)) })"
    )

    // Don't check during auto-reconnect - that's our own connection
    guard !isAutoReconnecting else { return false }

    // Don't report our own connection as "another app" (state restoration may have completed)
    if smIsConnected, smConnectedDeviceID == deviceID {
      return false
    }

    return systemConnected
  }

  /// True when a system-connected NUS peripheral is this app's radio: last
  /// connected, in-flight ASK pair, or still in the system pairing registry.
  func isOwnedSystemConnectedLink(_ deviceID: UUID) -> Bool {
    if deviceID == lastConnectedDeviceID { return true }
    if isPairingInProgress { return true }
    if pairing.hasSystemPairingRegistry {
      return pairing.isDeviceConnectable(deviceID)
    }
    return false
  }

  // MARK: - BLE Scanning

  /// Starts scanning for nearby BLE devices and returns an AsyncStream of `DiscoveredDevice`.
  /// Scanning is orthogonal to the connection lifecycle — works while connected.
  /// Cancel the consuming task to stop scanning automatically.
  public func startBLEScanning() -> AsyncStream<DiscoveredDevice> {
    let (stream, continuation) = AsyncStream.makeStream(of: DiscoveredDevice.self)
    bleScanTask?.cancel()
    bleScanRequestID &+= 1
    let requestID = bleScanRequestID

    bleScanTask = Task { @MainActor [weak self] in
      guard let self, !Task.isCancelled, requestID == bleScanRequestID else { return }

      await stateMachine.setDeviceDiscoveredHandler { @Sendable deviceID, name, rssi in
        _ = continuation.yield(DiscoveredDevice(id: deviceID, name: name, rssi: rssi))
      }

      guard !Task.isCancelled, requestID == bleScanRequestID else { return }
      await stateMachine.startScanning()
    }

    continuation.onTermination = { [weak self] _ in
      Task { @MainActor in
        guard let self, self.bleScanRequestID == requestID else { return }
        self.bleScanRequestID &+= 1
        self.bleScanTask?.cancel()
        self.bleScanTask = nil
        await self.stateMachine.setDeviceDiscoveredHandler { _, _, _ in }
        await self.stateMachine.stopScanning()
      }
    }

    return stream
  }

  /// Manually stops BLE scanning.
  public func stopBLEScanning() async {
    bleScanRequestID &+= 1
    bleScanTask?.cancel()
    bleScanTask = nil
    await stateMachine.setDeviceDiscoveredHandler { _, _, _ in }
    await stateMachine.stopScanning()
  }

  // MARK: - Reconnection Watchdog

  private enum WatchdogTiming {
    static let initialDelay: Duration = .seconds(30)
    static let maximumDelay: Duration = .seconds(120)
  }

  /// Starts a watchdog that periodically retries connection when the user wants to be
  /// connected but the device is stuck in disconnected state (e.g., after auto-reconnect failure).
  /// Uses exponential backoff: 30s → 60s → 120s (capped).
  func startReconnectionWatchdog() {
    guard !powerOffBlocksReconnect else {
      logger.info("[BLE] Watchdog suppressed during power-off")
      return
    }
    stopReconnectionWatchdog()

    reconnectionWatchdogGeneration += 1
    let generation = reconnectionWatchdogGeneration
    let requestID = desiredRequest?.id
    reconnectionWatchdogTask = Task {
      // Nil the property on every natural exit so "arm if nil" cannot see a
      // finished Task as live. Generation fence avoids nilling a replacement.
      defer {
        if reconnectionWatchdogGeneration == generation {
          reconnectionWatchdogTask = nil
        }
      }

      #if DEBUG
        var delay: Duration = testWatchdogInitialDelay ?? WatchdogTiming.initialDelay
      #else
        var delay = WatchdogTiming.initialDelay
      #endif
      let maxDelay = WatchdogTiming.maximumDelay

      while !Task.isCancelled {
        try? await reconnectionClock.sleep(for: delay)
        guard !Task.isCancelled else { return }

        guard connectionIntent.wantsConnection,
              connectionState == .disconnected,
              !powerOffBlocksReconnect else {
          logger.info("[BLE] Watchdog exiting: intent or state changed")
          return
        }

        if await stateMachine.isBluetoothPoweredOff {
          logger.info("[BLE] Watchdog skipping: Bluetooth powered off")
          delay = min(delay * 2, maxDelay)
          continue
        }

        if await stateMachine.isAutoReconnecting {
          logger.info("[BLE] Watchdog skipping: iOS auto-reconnect in progress")
          delay = min(delay * 2, maxDelay)
          continue
        }

        guard !Task.isCancelled, reconnectionWatchdogGeneration == generation,
              desiredRequest?.id == requestID else { return }
        logger.info("[BLE] Watchdog attempting reconnection (delay was \(delay))")
        // Admission cancels this watchdog, so health needs an independent caller.
        // Join that caller before scheduling another tick.
        let health = Task { @MainActor in
          guard self.reconnectionWatchdogGeneration == generation,
                self.desiredRequest?.id == requestID else { return }
          await self.checkBLEConnectionHealth()
        }
        await health.value
        guard !Task.isCancelled, reconnectionWatchdogGeneration == generation,
              desiredRequest?.id == requestID else { return }
        delay = min(delay * 2, maxDelay)
      }
    }
  }

  /// Stops the reconnection watchdog
  func stopReconnectionWatchdog() {
    reconnectionWatchdogGeneration += 1
    reconnectionWatchdogTask?.cancel()
    reconnectionWatchdogTask = nil
  }

  /// Publishes Bluetooth availability for pickers and diagnostics.
  /// `BLEStateMachine` owns power-loss cancellation and its disconnected lifecycle event.
  func handleBluetoothStateChange(_ state: CBManagerState) {
    let stateName = switch state {
    case .unknown: "unknown"
    case .resetting: "resetting"
    case .unsupported: "unsupported"
    case .unauthorized: "unauthorized"
    case .poweredOff: "poweredOff"
    case .poweredOn: "poweredOn"
    @unknown default: "unknown(\(state.rawValue))"
    }
    bluetoothAvailability = Self.bluetoothAvailability(for: state)
    logger.info("[BLE] Bluetooth state changed: \(stateName), connectionState: \(String(describing: connectionState)), connectionIntent: \(connectionIntent)")
  }

  /// Maps a raw `CBManagerState` to the user-actionable availability the pickers display. Only the
  /// two states a person can resolve get a distinct case; transient and unsupported states stay
  /// `.ready` so the picker keeps scanning rather than offering a remedy that does nothing.
  private static func bluetoothAvailability(for state: CBManagerState) -> BluetoothAvailability {
    switch state {
    case .poweredOff: .poweredOff
    case .unauthorized: .unauthorized
    default: .ready
    }
  }
}
