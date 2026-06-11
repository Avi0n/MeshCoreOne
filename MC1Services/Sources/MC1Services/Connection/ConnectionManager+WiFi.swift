import Foundation
import MeshCore

extension ConnectionManager {

    // MARK: - WiFi Heartbeat

    /// Starts periodic heartbeat to detect dead WiFi connections.
    /// ESP32's TCP stack doesn't respond to TCP keepalives, so we use application-level probes.
    private func startWiFiHeartbeat() {
        stopWiFiHeartbeat()

        wifiHeartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: Self.wifiHeartbeatInterval)
                } catch {
                    break
                }

                guard let self,
                      self.currentTransportType == .wifi,
                      self.connectionState.isOperational,
                      let session = self.session else { break }

                if self.shouldPauseWiFiHeartbeatProbe {
                    self.logger.info("WiFi heartbeat skipped while sync activity is active")
                    continue
                }

                // Probe connection with lightweight command
                do {
                    _ = try await session.getTime()
                } catch {
                    if self.shouldPauseWiFiHeartbeatProbe {
                        self.logger.info("WiFi heartbeat timeout during sync activity treated as inconclusive")
                        continue
                    }
                    self.logger.warning("WiFi heartbeat failed: \(error.localizedDescription)")
                    await self.handleWiFiDisconnection(error: error)
                    break
                }
            }
        }
    }

    var shouldPauseWiFiHeartbeatProbe: Bool {
        connectionState == .syncing || resyncTask != nil || channelRetryTask != nil
    }

    /// Stops the WiFi heartbeat loop
    func stopWiFiHeartbeat() {
        wifiHeartbeatTask?.cancel()
        wifiHeartbeatTask = nil
    }

    // MARK: - WiFi Disconnection Handling

    /// Handles unexpected WiFi connection loss
    private func handleWiFiDisconnection(error: Error?) async {
        // User-initiated disconnect - don't reconnect
        guard connectionIntent.wantsConnection else { return }

        // Only handle WiFi disconnections
        guard currentTransportType == .wifi else { return }

        // Prevent re-entrant calls: multiple disconnection callbacks can fire
        // simultaneously from the transport handler and heartbeat. The flag
        // covers the window between entry and startWiFiReconnection() where
        // await suspension points could allow interleaving on @MainActor.
        guard !isHandlingWiFiDisconnection, wifiReconnectTask == nil else {
            logger.info("WiFi disconnection already being handled, ignoring duplicate")
            return
        }
        isHandlingWiFiDisconnection = true
        defer { isHandlingWiFiDisconnection = false }

        logger.warning("WiFi connection lost: \(error?.localizedDescription ?? "unknown")")

        // Stop heartbeat before teardown
        stopWiFiHeartbeat()

        cancelResyncLoop()
        cancelChannelRetry()

        // Capture and clear synchronously, mirroring teardownSessionForReconnect:
        // a reconnect can install a new container during the awaits below, and
        // re-reading self.services would tear that new container down.
        let oldServices = services
        services = nil
        session = nil

        if let oldServices {
            // Mark room sessions disconnected before tearing down services
            _ = await oldServices.remoteNodeService.handleBLEDisconnection()
            // Reset sync state before destroying services to prevent stuck "Syncing" pill
            await oldServices.syncCoordinator.onDisconnected(services: oldServices)
            await oldServices.tearDown()
        }

        // Show connecting state (pulsing indicator)
        logger.info("[WiFi] State → .connecting (WiFi disconnection, starting reconnection)")
        connectionState = .connecting

        // Start reconnection attempts
        startWiFiReconnection()
    }

    // MARK: - WiFi Reconnection

    /// Starts the WiFi reconnection retry loop
    private func startWiFiReconnection() {
        // If a reconnect task is already running, don't start another
        if wifiReconnectTask != nil {
            logger.info("WiFi reconnection already in progress, skipping")
            return
        }

        // Rate limiting: prevent rapid reconnection attempts
        if let lastStart = lastWiFiReconnectStartTime,
           Date().timeIntervalSince(lastStart) < Self.wifiReconnectCooldown {
            logger.warning("Suppressing WiFi reconnection: too soon after last attempt")
            Task { await cleanupConnection() }
            return
        }
        lastWiFiReconnectStartTime = Date()

        wifiReconnectAttempt = 0
        wifiReconnectTask?.cancel()

        wifiReconnectTask = Task {
            // Only self-nil when not cancelled: startWiFiReconnection cancels the
            // old task before assigning a new one, and an unconditional defer in
            // the cancelled old task would destroy the replacement's handle (the
            // same pattern resyncTask and channelRetryTask document).
            defer {
                if !Task.isCancelled {
                    wifiReconnectTask = nil
                    wifiReconnectAttempt = 0
                }
            }

            let startTime = ContinuousClock.now

            while !Task.isCancelled && connectionIntent.wantsConnection {
                // Check if we've exceeded 30 second window
                let elapsed = ContinuousClock.now - startTime
                if elapsed > Self.wifiMaxReconnectDuration {
                    logger.info("WiFi reconnection timeout after 30s")
                    await cleanupConnection()
                    return
                }

                wifiReconnectAttempt += 1
                logger.info("WiFi reconnect attempt \(self.wifiReconnectAttempt)")

                do {
                    try await reconnectWiFi()
                    logger.info("WiFi reconnection succeeded")
                    return
                } catch {
                    logger.warning("WiFi reconnect failed: \(error.localizedDescription)")
                }

                // Exponential backoff: 0.5s, 1s, 2s, 4s (capped)
                let delay = min(0.5 * pow(2.0, Double(wifiReconnectAttempt - 1)), 4.0)
                try? await Task.sleep(for: .seconds(delay))
            }
        }
    }

    /// Attempts to reconnect to the WiFi device using stored connection info
    private func reconnectWiFi() async throws {
        guard let wifiTransport,
              let (host, port) = await wifiTransport.connectionInfo else {
            throw ConnectionError.connectionFailed("No WiFi connection info")
        }

        // Stop any existing session to prevent receive loops racing for transport data
        await session?.stop()
        session = nil

        // Disconnect old transport cleanly
        await wifiTransport.disconnect()

        // Create fresh transport with same connection info
        let newTransport = WiFiTransport()
        await newTransport.setConnectionInfo(host: host, port: port)
        self.wifiTransport = newTransport

        // Connect
        try await newTransport.connect()
        connectionState = .connected

        // Re-establish session
        let newSession = MeshCoreSession(transport: newTransport)
        self.session = newSession
        try await newSession.start()

        guard let selfInfo = await newSession.currentSelfInfo else {
            throw ConnectionError.initializationFailed("No self info")
        }

        let deviceID = DeviceIdentity.deriveUUID(from: selfInfo.publicKey)
        try await completeWiFiReconnection(
            session: newSession,
            transport: newTransport,
            deviceID: deviceID
        )
    }

    /// Completes WiFi reconnection by re-establishing services
    private func completeWiFiReconnection(
        session: MeshCoreSession,
        transport: WiFiTransport,
        deviceID: UUID
    ) async throws {
        let capabilities = try await session.queryDevice()
        detectAndStorePlatform(model: capabilities.model, transportType: .wifi)
        guard let selfInfo = await session.currentSelfInfo else {
            throw ConnectionError.initializationFailed("No self info")
        }

        let (newServices, radioID) = try await buildServicesAndSaveDevice(
            deviceID: deviceID,
            session: session,
            selfInfo: selfInfo,
            capabilities: capabilities
        )

        // Wire disconnection handler on new transport
        await transport.setDisconnectionHandler { [weak self] error in
            Task { @MainActor in
                await self?.handleWiFiDisconnection(error: error)
            }
        }

        await onConnectionReady?()
        // onConnectionReady can suspend; a reentrant main-actor WiFi disconnect may
        // clear connectedDevice or replace services during that await. Recheck so an
        // aborted reconnect bails here instead of syncing a torn-down session.
        guard connectionIntent.wantsConnection,
              self.services === newServices,
              connectedDevice != nil
        else {
            logger.info("[WiFi] reconnect aborted after onConnectionReady: connection state changed")
            await session.stop()
            await newServices.tearDown()
            services = nil
            connectedDevice = nil
            allowedRepeatFreqRanges = []
            return
        }
        let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, transportType: .wifi, context: "WiFi reconnect")

        guard await promoteToReady(syncSucceeded: syncSucceeded, expectedServices: newServices, transportType: .wifi) else { return }

        stopReconnectionWatchdog()
        if connectionState == .ready {
            startWiFiHeartbeat()
        }
    }

    // MARK: - WiFi Connection Health

    /// Checks if the WiFi connection is still alive (call on app foreground)
    public func checkWiFiConnectionHealth() async {
        // If a reconnect task is already running, let it finish
        if wifiReconnectTask != nil {
            logger.info("WiFi reconnection already in progress on foreground")
            return
        }

        // Case 1: We think we're connected but the transport died while backgrounded
        if currentTransportType == .wifi,
           connectionState.isOperational,
           let wifiTransport {
            let isConnected = await wifiTransport.isConnected
            if !isConnected {
                logger.info("WiFi connection died while backgrounded")
                await handleWiFiDisconnection(error: nil)
                return
            }
        }

        // Case 2: Connection was lost and cleanup already ran while backgrounded,
        // but user still wants to be connected — attempt fresh reconnection
        if connectionState == .disconnected,
           connectionIntent.wantsConnection,
           let lastDeviceID = lastConnectedDeviceID {
            let dataStore = PersistenceStore(modelContainer: modelContainer)
            if let device = try? await dataStore.fetchDevice(id: lastDeviceID),
               let wifiMethod = device.connectionMethods.first(where: { $0.isWiFi }) {
                if case .wifi(let host, let port, _) = wifiMethod {
                    logger.info("WiFi foreground reconnect to \(host):\(port)")
                    do {
                        try await connectViaWiFi(host: host, port: port)
                    } catch {
                        logger.warning("WiFi foreground reconnect failed: \(error.localizedDescription)")
                    }
                }
            }
        }
    }

    // MARK: - WiFi Connection

    /// Connects to a device via WiFi/TCP.
    ///
    /// - Parameters:
    ///   - host: The hostname or IP address of the device
    ///   - port: The TCP port to connect to
    ///   - forceFullSync: When true, performs a complete sync ignoring cached timestamps
    /// - Throws: Connection or session errors
    public func connectViaWiFi(host: String, port: UInt16, forceFullSync: Bool = false) async throws {
        logger.info("Connecting via WiFi to \(host):\(port)")

        // Disconnect existing connection if any
        if connectionState != .disconnected {
            await disconnect(reason: .wifiReconnectPrep)
        }

        connectionIntent = .wantsConnection()
        persistIntent()
        connectionState = .connecting

        do {
            // Create and configure WiFi transport
            let newWiFiTransport = WiFiTransport()
            await newWiFiTransport.setConnectionInfo(host: host, port: port)
            wifiTransport = newWiFiTransport

            // Connect the transport
            try await newWiFiTransport.connect()

            connectionState = .connected

            // Create session (same as BLE)
            let newSession = MeshCoreSession(transport: newWiFiTransport)
            self.session = newSession

            let (meshCoreSelfInfo, deviceCapabilities) = try await initializeSession(newSession)
            detectAndStorePlatform(model: deviceCapabilities.model, transportType: .wifi)

            // Derive device ID from public key (WiFi devices don't have Bluetooth UUIDs)
            let deviceID = DeviceIdentity.deriveUUID(from: meshCoreSelfInfo.publicKey)

            let wifiMethod = ConnectionMethod.wifi(host: host, port: port, displayName: nil)
            let (newServices, radioID) = try await buildServicesAndSaveDevice(
                deviceID: deviceID,
                session: newSession,
                selfInfo: meshCoreSelfInfo,
                capabilities: deviceCapabilities,
                connectionMethods: [wifiMethod]
            )

            // Persist connection for potential future use
            persistConnection(deviceID: deviceID, radioID: radioID, deviceName: meshCoreSelfInfo.name)

            await onConnectionReady?()
            let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, transportType: .wifi, forceFullSync: forceFullSync)

            // Wire disconnection handler before promotion — needed even if promotion fails
            await newWiFiTransport.setDisconnectionHandler { [weak self] error in
                Task { @MainActor in
                    await self?.handleWiFiDisconnection(error: error)
                }
            }

            guard await promoteToReady(syncSucceeded: syncSucceeded, expectedServices: newServices, transportType: .wifi) else { return }

            stopReconnectionWatchdog()
            if connectionState == .ready {
                startWiFiHeartbeat()
            }
            logger.info("WiFi connection complete - device ready")

        } catch {
            // Cleanup on failure
            if let wifiTransport {
                await wifiTransport.disconnect()
                self.wifiTransport = nil
            }
            currentTransportType = nil
            await cleanupConnection()
            throw error
        }
    }
}
