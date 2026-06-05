import Foundation
import MeshCore

// MARK: - BLEReconnectionDelegate

extension ConnectionManager: BLEReconnectionDelegate {

    func setConnectionState(_ state: ConnectionState) {
        let previousState = connectionState
        connectionState = state
        if state == .disconnected, previousState != .disconnected {
            let transportName = switch currentTransportType {
            case .bluetooth: "bluetooth"
            case .wifi: "wifi"
            case nil: "none"
            }
            persistDisconnectDiagnostic(
                "source=reconnectionCoordinator.setConnectionState, " +
                "previousState=\(String(describing: previousState)), " +
                "transport=\(transportName), " +
                "intent=\(connectionIntent)"
            )
        }
    }

    func setConnectedDevice(_ device: DeviceDTO?) {
        connectedDevice = device
    }

    func teardownSessionForReconnect() async {
        // Capture and clear synchronously so a concurrent rebuildSession can
        // assume nil-and-rebuild without racing the terminal writes that used
        // to land at the end of this method. Subsequent awaits operate on the
        // captured local — they no longer touch self.services / self.session.
        let oldServices = services
        services = nil
        session = nil

        if let oldServices {
            sessionsAwaitingReauth = await oldServices.remoteNodeService.handleBLEDisconnection()
            await oldServices.tearDown()
        }
        cancelResyncLoop()

        // Reset sync state on the captured services to prevent stuck "Syncing" pill
        if let oldServices {
            await oldServices.syncCoordinator.onDisconnected(services: oldServices)
        }
    }

    // Background execution note: iOS provides ~10s of background execution time.
    // Session rebuild (transport + session.start) should complete within this window.
    // Full sync is deferred until performInitialSync returns to foreground via onConnectionEstablished.
    func rebuildSession(deviceID: UUID) async throws {
        logger.info("[BLE] Rebuilding session for auto-reconnect: \(deviceID.uuidString.prefix(8))")
        let expectedGeneration = reconnectionCoordinator.reconnectGeneration
        sessionRebuildDeviceID = deviceID
        defer {
            if sessionRebuildDeviceID == deviceID {
                sessionRebuildDeviceID = nil
            }
        }

        // Stop any existing session to prevent multiple receive loops racing for transport data
        await session?.stop()
        session = nil

        let newSession = MeshCoreSession(transport: transport)
        self.session = newSession

        do {
            try await newSession.start(reconnectingAttempt: 1)
        } catch {
            logger.warning("[BLE] rebuildSession: session.start() failed: \(error.localizedDescription)")
            throw error
        }

        // Check after await — user may have disconnected or a new reconnect cycle may have started
        guard connectionIntent.wantsConnection else {
            logger.info("User disconnected during session setup")
            await newSession.stop()
            connectionState = .disconnected
            return
        }
        guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
            logger.info("[BLE] rebuildSession superseded by new reconnect cycle during session setup")
            await newSession.stop()
            return
        }

        guard let selfInfo = await newSession.currentSelfInfo else {
            logger.warning("[BLE] rebuildSession: selfInfo is nil after start()")
            throw ConnectionError.initializationFailed("No self info")
        }
        let capabilities: DeviceCapabilities
        do {
            capabilities = try await newSession.queryDevice()
        } catch {
            logger.warning("[BLE] rebuildSession: queryDevice() failed: \(error.localizedDescription)")
            throw error
        }

        // Configure BLE write pacing based on device platform
        await configureBLEPacing(for: capabilities)

        // Check after await
        guard connectionIntent.wantsConnection else {
            logger.info("User disconnected during device query")
            await newSession.stop()
            connectionState = .disconnected
            return
        }
        guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
            logger.info("[BLE] rebuildSession superseded by new reconnect cycle during device query")
            await newSession.stop()
            return
        }

        let (newServices, radioID) = try await buildServicesAndSaveDevice(
            deviceID: deviceID,
            session: newSession,
            selfInfo: selfInfo,
            capabilities: capabilities
        )

        // Check after await — user may have disconnected or new reconnect cycle started
        guard connectionIntent.wantsConnection else {
            logger.info("User disconnected during service wiring")
            await newSession.stop()
            await newServices.tearDown()
            services = nil
            connectedDevice = nil
            allowedRepeatFreqRanges = []
            connectionState = .disconnected
            return
        }
        guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
            logger.info("[BLE] rebuildSession superseded by new reconnect cycle during service wiring")
            await newSession.stop()
            await newServices.tearDown()
            services = nil
            connectedDevice = nil
            allowedRepeatFreqRanges = []
            return
        }

        // Notify observers before sync starts so they can wire callbacks
        await onConnectionReady?()
        // onConnectionReady can suspend; a reentrant main-actor disconnect or
        // reconnect-UI timeout may clear connectedDevice or replace services during
        // that await. Recheck so an aborted reconnect bails here instead of syncing
        // a torn-down session.
        guard connectionIntent.wantsConnection,
              reconnectionCoordinator.reconnectGeneration == expectedGeneration,
              self.services === newServices,
              connectedDevice != nil
        else {
            logger.info("[BLE] rebuildSession aborted after onConnectionReady: reconnect state changed")
            await newSession.stop()
            await newServices.tearDown()
            services = nil
            connectedDevice = nil
            allowedRepeatFreqRanges = []
            return
        }
        let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, context: "[BLE] iOS auto-reconnect")

        // Caller-specific guard: generation check for superseded reconnects
        guard connectionIntent.wantsConnection,
              reconnectionCoordinator.reconnectGeneration == expectedGeneration,
              self.services === newServices
        else {
            await newSession.stop()
            return
        }

        if syncSucceeded {
            // Re-authenticate room sessions (sends BLE commands — skip on failure path).
            let sessionIDs = sessionsAwaitingReauth
            await newServices.remoteNodeService.handleBLEReconnection(sessionIDs: sessionIDs)

            guard connectionIntent.wantsConnection,
                  reconnectionCoordinator.reconnectGeneration == expectedGeneration,
                  self.services === newServices
            else {
                // IDs preserved for next reconnect cycle — new IDs may have
                // arrived during handleBLEReconnection if BLE dropped mid-reauth.
                await newSession.stop()
                return
            }

            // Only clear consumed IDs after confirming this cycle is still authoritative.
            // Any IDs appended during the await (via teardownSessionForReconnect) survive.
            sessionsAwaitingReauth.subtract(sessionIDs)
        }

        guard await promoteToReady(
            syncSucceeded: syncSucceeded,
            expectedServices: newServices,
            transportType: .bluetooth,
            additionalGuard: { [reconnectionCoordinator] in
                reconnectionCoordinator.reconnectGeneration == expectedGeneration
            }
        ) else {
            await newSession.stop()
            return
        }

        recordConnectionSuccess()
        stopReconnectionWatchdog()
        logger.info("[BLE] iOS auto-reconnect: session ready, device: \(deviceID.uuidString.prefix(8))")
    }

    func disconnectTransport() async {
        await transport.disconnect()
    }

    func notifyConnectionLost() async {
        await onConnectionLost?()
    }

    func isTransportAutoReconnecting() async -> Bool {
        await stateMachine.isAutoReconnecting
    }

    func handleReconnectionFailure() async {
        logger.error("[BLE] Auto-reconnect session rebuild failed")
        await session?.stop()
        await services?.tearDown()
        session = nil
        services = nil
        await transport.disconnect()
        connectionState = .disconnected
        connectedDevice = nil
        allowedRepeatFreqRanges = []

        // Same callback contract as handleConnectionLoss and the UI-timeout
        // path in BLEReconnectionCoordinator: route through notifyConnectionLost()
        // so AppState tears down its observers and the Live Activity transitions
        // to disconnected. Without this the LA stays in stale "connected" state.
        await notifyConnectionLost()

        // Start watchdog to periodically retry if user still wants connection
        if connectionIntent.wantsConnection {
            startReconnectionWatchdog()
        }
    }
}
