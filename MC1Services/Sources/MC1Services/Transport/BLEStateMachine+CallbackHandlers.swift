@preconcurrency import CoreBluetooth
import Foundation

// MARK: - Internal Callback Handlers

extension BLEStateMachine {
  func handleCentralManagerDidUpdateState(_ state: CBManagerState) {
    let stateString = switch state {
    case .unknown: "unknown"
    case .resetting: "resetting"
    case .unsupported: "unsupported"
    case .unauthorized: "unauthorized"
    case .poweredOff: "poweredOff"
    case .poweredOn: "poweredOn"
    @unknown default: "unknown(\(state.rawValue))"
    }
    if lastCentralState != state {
      lastCentralState = state
      logger.info(
        "[BLE] Central manager state changed: \(stateString), currentPhase: \(phase.name), instance: \(instanceID), \(processContext)"
      )
    }
    onBluetoothStateChange?(state)

    switch state {
    case .poweredOn:
      // Cancel any poweredOff grace period — Bluetooth is now available
      bluetoothPowerOffGraceTask?.cancel()
      bluetoothPowerOffGraceTask = nil

      // Resume waiting continuation if any
      if case let .waitingForBluetooth(continuation) = phase {
        transition(to: .idle)
        continuation.resume()
      }

      // Handle state restoration from phase
      if case let .restoringState(peripheral) = phase {
        handleRestoredPeripheral(peripheral)
      }

      // Fulfill pending scan request
      if pendingScanRequest {
        startScanning()
      }

      // Notify handler for power-on events
      onBluetoothPoweredOn?()

    case .poweredOff:
      let wasScanning = isCurrentlyScanning
      isCurrentlyScanning = false
      if wasScanning {
        pendingScanRequest = true
      }

      if case .waitingForBluetooth = phase {
        // A freshly created CBCentralManager may briefly report poweredOff
        // before settling on poweredOn. Start a grace period instead of
        // failing immediately, so the initialization can complete.
        if bluetoothPowerOffGraceTask == nil {
          logger.info("[BLE] poweredOff during waitingForBluetooth — starting grace period")
          bluetoothPowerOffGraceTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self.handleBluetoothPowerOffGraceExpired()
          }
        }
      } else {
        // Not waiting — cancel any active operation immediately
        let deviceID = phase.deviceID
        cancelCurrentOperation(with: BLEError.bluetoothPoweredOff)
        if let deviceID {
          onDisconnection?(deviceID, nil)
        }
      }

    case .unauthorized:
      handleBluetoothBecomingUnavailable(error: .bluetoothUnauthorized)

    case .unsupported:
      handleBluetoothBecomingUnavailable(error: .bluetoothUnavailable)

    default:
      break
    }
  }

  /// Called when the poweredOff grace period expires without poweredOn arriving.
  private func handleBluetoothPowerOffGraceExpired() {
    bluetoothPowerOffGraceTask = nil
    guard case .waitingForBluetooth = phase else { return }
    logger.info("[BLE] poweredOff grace period expired — Bluetooth is off")
    let deviceID = phase.deviceID
    cancelCurrentOperation(with: BLEError.bluetoothPoweredOff)
    if let deviceID {
      onDisconnection?(deviceID, nil)
    }
  }

  /// Handles Bluetooth becoming permanently unavailable (unauthorized or unsupported).
  private func handleBluetoothBecomingUnavailable(error: BLEError) {
    isCurrentlyScanning = false
    pendingScanRequest = false
    if case let .waitingForBluetooth(continuation) = phase {
      transition(to: .idle)
      continuation.resume(throwing: error)
    }
    if case let .restoringState(peripheral) = phase {
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, nil)
    }
    // Auto-reconnect waits indefinitely on the OS pending connect, which can
    // never complete once Bluetooth is unavailable; tear down explicitly.
    if case let .autoReconnecting(peripheral, _, _) = phase {
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, error)
    }
  }

  func handleRestoredPeripheral(_ peripheral: CBPeripheral, source: RestoredPeripheralSource = .stateRestoration) {
    let pState = peripheralStateString(peripheral.state)
    logger.info("[BLE] Processing restored peripheral: \(peripheral.identifier.uuidString.prefix(8)), state: \(pState), source: \(source)")

    peripheral.delegate = delegateHandler

    // Advance connection generation for restoration-driven reconnect
    advanceConnectionGeneration()

    // Start timeout for auto-reconnect discovery
    armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)

    transition(to: .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil))

    // State-restoration paths claim the reconnect cycle here so the coordinator's
    // strict completion guard accepts the eventual onReconnection. The adoption
    // path already claimed via startAdoptingLastSystemConnectedPeripheralIfAvailable
    // and passes .adoption to skip and avoid double-claiming.
    if source == .stateRestoration {
      onAutoReconnecting?(peripheral.identifier, "state-restoration")
    }

    if peripheral.state == .connected {
      // Already connected, just need to rediscover services
      peripheral.discoverServices([nordicUARTServiceUUID])
    } else if peripheral.state == .connecting {
      // Connection in progress, wait for didConnect
    } else {
      // Not connected, try to reconnect
      let options: [String: Any] = [
        CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
        CBConnectPeripheralOptionEnableAutoReconnect: true
      ]
      centralManager.connect(peripheral, options: options)
    }
  }

  func handleWillRestoreState(_ peripheral: CBPeripheral) {
    let pState = peripheralStateString(peripheral.state)
    logger.info("[BLE] State restoration callback: \(peripheral.identifier.uuidString.prefix(8)), state: \(pState)")

    // If Bluetooth is already powered on, proceed directly to restoration.
    // This handles the edge case where .poweredOn Task runs before this Task.
    if centralManager.state == .poweredOn {
      handleRestoredPeripheral(peripheral)
    } else {
      transition(to: .restoringState(peripheral: peripheral))
    }
  }

  func handleDidConnect(_ peripheral: CBPeripheral) {
    let pState = peripheralStateString(peripheral.state)
    let elapsed = Date().timeIntervalSince(phaseStartTime)
    logger.info("[BLE] Did connect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), phase: \(phase.name), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

    // Handle auto-reconnect
    if case let .autoReconnecting(expected, _, _) = phase,
       peripheral.identifier == expected.identifier {
      logger.info("[BLE] Auto-reconnect: peripheral connected, discovering services")
      peripheral.delegate = delegateHandler
      peripheral.discoverServices([nordicUARTServiceUUID])

      // Cancel any existing timeout (e.g., from handleRestoredPeripheral) and restart
      armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)
      return
    }

    // Normal connection flow
    guard case let .connecting(expected, continuation, timeoutTask) = phase,
          expected.identifier == peripheral.identifier else {
      logger.warning("Unexpected didConnect for \(peripheral.identifier)")
      cancelUnexpectedPeripheral(peripheral)
      return
    }

    timeoutTask.cancel()

    // Arm discovery timeout before starting discovery so the callback
    // window between discoverServices() and timeout creation is closed.
    armServiceDiscoveryTimeout(for: peripheral)

    transition(to: .discoveringServices(
      peripheral: peripheral,
      continuation: continuation
    ))

    peripheral.delegate = delegateHandler
    peripheral.discoverServices([nordicUARTServiceUUID])
  }

  func handleDidFailToConnect(_ peripheral: CBPeripheral, error: Error?) {
    let pState = peripheralStateString(peripheral.state)
    let elapsed = Date().timeIntervalSince(phaseStartTime)
    var errorInfo = "none"
    if let error = error as NSError? {
      errorInfo = "domain=\(error.domain), code=\(error.code), desc=\(error.localizedDescription)"
    }
    logger.warning(
      "[BLE] Did fail to connect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), phase: \(phase.name), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s, error: \(errorInfo)"
    )

    // Handle failure during auto-reconnect (iOS auto-reconnect gave up)
    if case let .autoReconnecting(expected, _, _) = phase,
       expected.identifier == peripheral.identifier {
      logger.warning("Auto-reconnect failed for \(peripheral.identifier) - transitioning to idle")
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, error)
      return
    }

    guard case let .connecting(expected, continuation, timeoutTask) = phase,
          expected.identifier == peripheral.identifier else {
      logger.info("Ignoring didFailToConnect - not our peripheral or unexpected phase")
      return
    }

    timeoutTask.cancel()
    transition(to: .idle)
    continuation.resume(throwing: Self.makeConnectionError(error))
  }

  /// Maps a CoreBluetooth error to a typed BLEError. Auth/encryption codes
  /// from CBATTError or CBError get the typed `.authenticationFailed` case so
  /// detection survives iOS localizing the error description in any locale.
  static func makeConnectionError(_ error: Error?, fallback: String = "Unknown error") -> BLEError {
    if let nsError = error as NSError? {
      if nsError.domain == CBATTErrorDomain {
        switch nsError.code {
        case CBATTError.insufficientAuthentication.rawValue,
             CBATTError.insufficientAuthorization.rawValue,
             CBATTError.insufficientEncryption.rawValue,
             CBATTError.insufficientEncryptionKeySize.rawValue:
          return .authenticationFailed
        default:
          break
        }
      }
      if nsError.domain == CBErrorDomain,
         nsError.code == CBError.encryptionTimedOut.rawValue {
        return .authenticationFailed
      }
    }
    return .connectionFailed(error?.localizedDescription ?? fallback)
  }

  func handleDidDisconnect(_ peripheral: CBPeripheral, timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
    let pState = peripheralStateString(peripheral.state)
    let elapsed = Date().timeIntervalSince(phaseStartTime)
    var errorInfo = "none"
    if let error = error as NSError? {
      errorInfo = "domain=\(error.domain), code=\(error.code), desc=\(error.localizedDescription)"
    }
    let elapsedStr = elapsed.formatted(.number.precision(.fractionLength(2)))
    logger.info(
      "[BLE] Did disconnect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), isReconnecting: \(isReconnecting), phase: \(phase.name), elapsed: \(elapsedStr)s, error: \(errorInfo)"
    )

    // C7: Ignore stale disconnects for peripherals that don't match the active session.
    // A delayed callback from an old peripheral must not cancel the current session.
    if let activePeripheral = phase.peripheral,
       activePeripheral.identifier != peripheral.identifier {
      logger.warning("[BLE] Ignoring stale didDisconnect for \(peripheral.identifier.uuidString.prefix(8)), active: \(activePeripheral.identifier.uuidString.prefix(8))")
      return
    }

    // Primary stale-callback fence: reject disconnect callbacks from a previous generation.
    // After app resume, iOS may deliver queued disconnects from before the suspend.
    // We use CFAbsoluteTime (not a generation counter captured at callback delivery time)
    // because CoreBluetooth's didDisconnectPeripheral timestamp reflects the disconnect
    // event time per Apple's header ("now or a few seconds ago"), not delivery time.
    // A generation captured at delivery time would be unsafe if advanceConnectionGeneration()
    // runs between the event and callback delivery. CFAbsoluteTimeGetCurrent() is not
    // guaranteed monotonic (NTP adjustments can cause backward jumps), so the 1.0s
    // tolerance accommodates typical clock corrections. The peripheral identity check
    // above provides the primary defense; this timestamp fence is a secondary guard for
    // same-peripheral stale callbacks across generation boundaries.
    let generationStart = connectionGenerationStartTime
    if Self.isDisconnectCallbackFromPreviousGeneration(
      timestamp: timestamp,
      generationStart: generationStart
    ) {
      let callbackAge = CFAbsoluteTimeGetCurrent() - timestamp
      logger.warning(
        "[BLE] Ignoring stale disconnect callback: " +
          "age=\(callbackAge.formatted(.number.precision(.fractionLength(1))))s, " +
          "generation=\(connectionGeneration), phase=\(phase.name)"
      )
      return
    }

    // Secondary diagnostic: flag very old callbacks, but do not drop callbacks
    // that belong to the current connection generation.
    let callbackAge = CFAbsoluteTimeGetCurrent() - timestamp
    if callbackAge > 120 {
      logger.warning(
        "[BLE] Processing aged disconnect callback: " +
          "age=\(callbackAge.formatted(.number.precision(.fractionLength(1))))s, " +
          "generation=\(connectionGeneration), phase=\(phase.name)"
      )
    }

    let deviceID = peripheral.identifier

    if isReconnecting {
      handleAutoReconnectDisconnect(peripheral: peripheral, error: error)
    } else {
      handleFullDisconnect(deviceID: deviceID, error: error)
    }
  }

  /// Handles a disconnect where iOS is auto-reconnecting the peripheral.
  /// Setup-phase continuations route through makeConnectionError so a CBATT
  /// auth/encryption code arriving before the bond is established still maps
  /// to the typed BLEError.authenticationFailed.
  private func handleAutoReconnectDisconnect(peripheral: CBPeripheral, error: Error?) {
    let deviceID = peripheral.identifier
    var errorInfo = "none"
    if let nsError = error as NSError? {
      errorInfo = "domain=\(nsError.domain), code=\(nsError.code), desc=\(nsError.localizedDescription)"
    }
    logger.info("[BLE] iOS auto-reconnect started: \(deviceID.uuidString.prefix(8)), will attempt automatic reconnection")

    // Clean up pending operations before transitioning.
    // This ensures any pending setup continuations and write waiters are properly
    // resumed/failed, preventing orphaned continuations and waiter starvation.
    cancelPendingWriteOperations()

    // Clean up current state but preserve peripheral for reconnection.
    // transition() handles dataContinuation cleanup when leaving .connected.
    // Note: We handle phase continuations manually below since cancelCurrentOperation
    // would transition to .idle, but we need to go to .autoReconnecting.
    let setupError = Self.makeConnectionError(error, fallback: "Disconnected during setup")
    switch phase {
    case let .connecting(_, continuation, timeoutTask):
      timeoutTask.cancel()
      continuation.resume(throwing: setupError)
    case let .discoveringServices(_, continuation):
      continuation.resume(throwing: setupError)
    case let .discoveringCharacteristics(_, _, continuation):
      continuation.resume(throwing: setupError)
    case let .subscribingToNotifications(_, _, _, continuation):
      continuation.resume(throwing: setupError)
    default:
      break
    }

    // Advance generation for the auto-reconnect cycle
    advanceConnectionGeneration()

    transition(to: .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil))

    // C5: Arm the auto-reconnect discovery timeout (same as restoration path)
    armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)

    // Notify handler so UI can show "connecting" state
    onAutoReconnecting?(deviceID, errorInfo)
  }

  /// Handles a full (non-reconnecting) disconnection.
  private func handleFullDisconnect(deviceID: UUID, error: Error?) {
    switch phase {
    case .disconnecting:
      // Expected disconnection, transition handled by disconnect()
      break

    case .connected, .autoReconnecting:
      // Unexpected disconnection
      cancelCurrentOperation(with: BLEError.notConnected)
      onDisconnection?(deviceID, error)

    default:
      // Disconnection during connection attempt
      cancelCurrentOperation(with: Self.makeConnectionError(error, fallback: "Disconnected during setup"))
    }
  }

  func handleDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?) {
    let serviceCount = peripheral.services?.count ?? 0
    let hasNordicUART = peripheral.services?.contains { $0.uuid == nordicUARTServiceUUID } ?? false
    logger.info("[BLE] Did discover services: \(peripheral.identifier.uuidString.prefix(8)), count: \(serviceCount), hasNordicUART: \(hasNordicUART), error: \(error?.localizedDescription ?? "none")")

    // Handle auto-reconnect
    if case let .autoReconnecting(expected, _, _) = phase,
       peripheral.identifier == expected.identifier {
      if let error {
        logger.warning("Auto-reconnect service discovery failed: \(error.localizedDescription)")
        transition(to: .idle)
        onDisconnection?(expected.identifier, error)
        return
      }

      guard let service = peripheral.services?.first(where: { $0.uuid == nordicUARTServiceUUID }) else {
        logger.warning("Auto-reconnect: service not found")
        transition(to: .idle)
        onDisconnection?(expected.identifier, nil)
        return
      }

      peripheral.discoverCharacteristics([txCharacteristicUUID, rxCharacteristicUUID], for: service)
      return
    }

    // Normal flow
    guard case let .discoveringServices(expected, continuation) = phase,
          expected.identifier == peripheral.identifier else {
      logger.warning("Unexpected didDiscoverServices")
      return
    }

    if let error {
      transition(to: .idle)
      continuation.resume(throwing: Self.makeConnectionError(error))
      return
    }

    guard let service = peripheral.services?.first(where: { $0.uuid == nordicUARTServiceUUID }) else {
      transition(to: .idle)
      continuation.resume(throwing: BLEError.characteristicNotFound)
      return
    }

    transition(to: .discoveringCharacteristics(
      peripheral: peripheral,
      service: service,
      continuation: continuation
    ))

    peripheral.discoverCharacteristics([txCharacteristicUUID, rxCharacteristicUUID], for: service)
  }

  func handleDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?) {
    let characteristics = service.characteristics ?? []
    let hasTX = characteristics.contains { $0.uuid == txCharacteristicUUID }
    let hasRX = characteristics.contains { $0.uuid == rxCharacteristicUUID }
    logger.info("[BLE] Did discover characteristics: \(peripheral.identifier.uuidString.prefix(8)), count: \(characteristics.count), hasTX: \(hasTX), hasRX: \(hasRX), error: \(error?.localizedDescription ?? "none")")

    // Handle auto-reconnect
    if case let .autoReconnecting(expected, _, _) = phase,
       peripheral.identifier == expected.identifier {
      if let error {
        logger.warning("Auto-reconnect characteristic discovery failed: \(error.localizedDescription)")
        transition(to: .idle)
        onDisconnection?(expected.identifier, error)
        return
      }

      guard let characteristics = service.characteristics,
            let tx = characteristics.first(where: { $0.uuid == txCharacteristicUUID }),
            let rx = characteristics.first(where: { $0.uuid == rxCharacteristicUUID }) else {
        logger.warning("Auto-reconnect: characteristics not found")
        transition(to: .idle)
        onDisconnection?(expected.identifier, nil)
        return
      }

      captureWriteWithoutResponseCapability(from: tx)

      // Store tx/rx in phase for use when notification subscription completes
      transition(to: .autoReconnecting(peripheral: peripheral, tx: tx, rx: rx))

      // Subscribe to notifications to complete reconnection
      peripheral.setNotifyValue(true, for: rx)
      return
    }

    // Normal flow
    guard case let .discoveringCharacteristics(expected, expectedService, continuation) = phase,
          expected.identifier == peripheral.identifier,
          expectedService.uuid == service.uuid else {
      logger.warning("Unexpected didDiscoverCharacteristics")
      return
    }

    if let error {
      transition(to: .idle)
      continuation.resume(throwing: Self.makeConnectionError(error))
      return
    }

    guard let characteristics = service.characteristics,
          let tx = characteristics.first(where: { $0.uuid == txCharacteristicUUID }),
          let rx = characteristics.first(where: { $0.uuid == rxCharacteristicUUID }) else {
      transition(to: .idle)
      continuation.resume(throwing: BLEError.characteristicNotFound)
      return
    }

    captureWriteWithoutResponseCapability(from: tx)

    transition(to: .subscribingToNotifications(
      peripheral: peripheral,
      tx: tx,
      rx: rx,
      continuation: continuation
    ))

    peripheral.setNotifyValue(true, for: rx)
  }

  func handleDidUpdateNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
    logger.info("[BLE] Did update notification state: \(peripheral.identifier.uuidString.prefix(8)), isNotifying: \(characteristic.isNotifying), charUUID: \(characteristic.uuid.uuidString.prefix(8)), error: \(error?.localizedDescription ?? "none")")

    guard case let .subscribingToNotifications(expected, tx, rx, continuation) = phase,
          expected.identifier == peripheral.identifier,
          characteristic.uuid == rxCharacteristicUUID else {
      // Could be auto-reconnect scenario - handle separately
      handleReconnectionNotificationState(peripheral, characteristic: characteristic, error: error)
      return
    }

    if let error {
      centralManager.cancelPeripheralConnection(expected)
      transition(to: .idle)
      continuation.resume(throwing: Self.makeConnectionError(error))
      return
    }

    // C9: Verify notification subscription actually succeeded
    guard characteristic.isNotifying else {
      logger.warning("[BLE] Notification subscription completed without isNotifying=true")
      transition(to: .idle)
      continuation.resume(throwing: BLEError.connectionFailed("Notification subscription failed"))
      return
    }

    // Cancel the service discovery timeout since we completed successfully
    serviceDiscoveryTimeoutTask?.cancel()
    serviceDiscoveryTimeoutTask = nil

    // Transition to discoveryComplete before resuming the continuation.
    // This prevents double-resume if cancelCurrentOperation, disconnect(),
    // or a timeout handler runs before connect() transitions to .connected.
    transition(to: .discoveryComplete(peripheral: expected, tx: tx, rx: rx))
    continuation.resume()
  }

  private func handleReconnectionNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
    // Handle auto-reconnect notification subscription completion
    guard case let .autoReconnecting(expected, tx, rx) = phase,
          peripheral.identifier == expected.identifier else {
      return
    }

    // C9: Verify characteristic UUID matches RX and notification is active
    guard characteristic.uuid == rxCharacteristicUUID else {
      logger.debug("[BLE] Auto-reconnect: ignoring notification state for non-RX characteristic \(characteristic.uuid.uuidString.prefix(8))")
      return
    }

    if let error {
      logger.warning("Auto-reconnect notification subscription failed: \(error.localizedDescription)")
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, error)
      return
    }

    guard characteristic.isNotifying else {
      logger.warning("[BLE] Auto-reconnect: notification subscription completed without isNotifying=true")
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, nil)
      return
    }

    guard let tx, let rx else {
      logger.error("Auto-reconnect: tx/rx characteristics missing from phase")
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, nil)
      return
    }

    // Cancel the auto-reconnect discovery timeout since we completed successfully
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil

    let elapsed = Date().timeIntervalSince(phaseStartTime)
    logger.info("[BLE] Auto-reconnect notification subscription complete, elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

    // Create data stream and transition to connected
    let (stream, continuation) = AsyncStream.makeStream(
      of: Data.self,
      bufferingPolicy: .bufferingOldest(512)
    )

    // Pass continuation to delegate handler for direct yielding (preserves ordering)
    delegateHandler.setDataContinuation(continuation)

    transition(to: .connected(
      peripheral: peripheral,
      tx: tx,
      rx: rx,
      dataContinuation: continuation
    ))
    startRSSIKeepalive(for: peripheral)

    logger.info("[BLE] iOS auto-reconnect complete: \(peripheral.identifier.uuidString.prefix(8))")
    onReconnection?(peripheral.identifier, stream)
  }

  func handleDidWriteValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?, writeSequence: UInt64) {
    guard let continuation = pendingWriteContinuation else {
      logger.debug("[BLE] didWriteValue with no pending continuation, ignoring")
      return
    }

    // C8: Reject stale write callbacks from a previous (timed-out) write
    if writeSequence != pendingWriteSequence {
      logger.warning("[BLE] Stale didWriteValue: seq=\(writeSequence), expected=\(pendingWriteSequence), ignoring")
      return
    }

    // Cancel the timeout task since write completed
    writeTimeoutTask?.cancel()
    writeTimeoutTask = nil

    // Reset queue tracking on successful completion
    consecutiveQueuedWrites = 0

    pendingWriteContinuation = nil

    if let error {
      logger.warning("[BLE] Write error: seq=\(writeSequence), error=\(error.localizedDescription)")
      continuation.resume(throwing: BLEError.writeError(error.localizedDescription))
    } else {
      logger.debug("[BLE] Write complete: seq=\(writeSequence)")
      continuation.resume()
    }

    earliestNextWrite = ContinuousClock.now.advanced(by: .seconds(writePacingDelay))
    resumeNextWriteWaiter()
  }

  func handleDidReadRSSI(RSSI: NSNumber, error: Error?) {
    if let error {
      consecutiveRSSIFailures += 1
      if consecutiveRSSIFailures == 3 || consecutiveRSSIFailures % 10 == 0 {
        logger.warning(
          "[BLE] RSSI read failed (\(consecutiveRSSIFailures) consecutive): \(error.localizedDescription)"
        )
      }
    } else {
      if consecutiveRSSIFailures > 0 {
        logger.info("[BLE] RSSI read recovered after \(consecutiveRSSIFailures) failures, RSSI: \(RSSI)")
      }
      consecutiveRSSIFailures = 0
    }
  }
}
