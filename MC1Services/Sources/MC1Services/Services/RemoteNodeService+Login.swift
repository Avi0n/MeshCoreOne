import Foundation
import MeshCore

extension RemoteNodeService {

    // MARK: - Login

    /// Login to a remote node.
    /// Works for both room servers and repeaters.
    /// - Parameters:
    ///   - sessionID: The remote session ID.
    ///   - password: Optional password (uses stored password if nil).
    ///   - pathLength: Path length hint for timeout calculation.
    ///   - onTimeoutKnown: Optional callback invoked with timeout in seconds once firmware responds.
    public func login(
        sessionID: UUID,
        password: String? = nil,
        pathLength: UInt8 = 0,
        onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
    ) async throws -> LoginResult {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Get password from parameter or keychain
        let pwd: String
        if let password {
            pwd = password
        } else if let stored = try await keychainService.retrievePassword(forNodeKey: remoteSession.publicKey) {
            pwd = stored
        } else {
            throw RemoteNodeError.passwordNotFound
        }

        let prefix = Data(remoteSession.publicKey.prefix(6))

        // Cancel any existing pending login for this prefix
        if let existing = pendingLogins.removeValue(forKey: prefix) {
            let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
            logger.warning("Overwriting pending login for prefix \(prefixHex)")
            pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
            existing.resume(throwing: RemoteNodeError.cancelled)
        }

        // Log login request
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logLoginRequest(target: targetType, publicKey: remoteSession.publicKey, pathLength: pathLength)

        // Register continuation BEFORE sending to avoid race condition with loginSuccess event
        let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
        logger.info("login: registering pending login for prefix \(prefixHex)")
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pendingLogins[prefix] = continuation

                let timeoutTask = Task { [self] in
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await sendLoginHealingIfNeeded(
                            publicKey: remoteSession.publicKey,
                            radioID: remoteSession.radioID,
                            password: pwd
                        )
                    } catch {
                        // Cancellation means this task no longer owns the continuation: whoever
                        // cancelled it (an overlapping login for this prefix, or cancelPendingLogin
                        // on outer-task cancellation) has already resumed and removed it.
                        guard !Task.isCancelled else { return }
                        pendingLoginTimeoutTasks.removeValue(forKey: prefix)
                        if let pending = pendingLogins.removeValue(forKey: prefix) {
                            let rnError = (error as? RemoteNodeError)
                                ?? .sessionError(error as? MeshCoreError ?? .connectionLost(underlying: error))
                            pending.resume(throwing: rnError)
                        }
                        return
                    }

                    let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: pathLength)
                    logger.info("login: send succeeded, starting \(timeout) timeout for prefix \(prefixHex)")

                    if let onTimeoutKnown {
                        let timeoutSeconds = max(1, Int(timeInterval(for: timeout).rounded(.up)))
                        await onTimeoutKnown(timeoutSeconds)
                    }
                    try? await Task.sleep(for: timeout)
                    guard !Task.isCancelled else { return }
                    if let pending = pendingLogins.removeValue(forKey: prefix) {
                        logger.warning("Login timeout after \(timeout) for session \(sessionID), prefix \(prefixHex)")
                        pendingLoginTimeoutTasks.removeValue(forKey: prefix)
                        pending.resume(throwing: RemoteNodeError.timeout)
                    } else {
                        logger.info("login: timeout elapsed but continuation already consumed for prefix \(prefixHex)")
                    }
                }
                pendingLoginTimeoutTasks[prefix] = timeoutTask
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingLogin(for: prefix)
            }
        }
    }

    /// Sends the login; if the radio reports the contact is missing from its table, pushes the local
    /// copy (flood-routed) and retries once. A contact can be in the app database but absent on the
    /// radio after a backup restore or a radio swap, which the firmware answers with notFound (0x02).
    /// The parked login continuation is covered here by the session commands' own timeouts, not the
    /// login timeout, which only starts after this returns.
    func sendLoginHealingIfNeeded(
        publicKey: Data,
        radioID: UUID,
        password: String
    ) async throws -> MessageSentInfo {
        do {
            return try await session.sendLogin(to: publicKey, password: password)
        } catch let error as MeshCoreError {
            guard case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue else {
                throw error
            }
            try Task.checkCancellation()
            try await addLocalContactToRadio(publicKey: publicKey, radioID: radioID)
            // Bounded to a single retry: a second notFound after a successful add is a firmware
            // inconsistency, left to propagate rather than loop.
            return try await session.sendLogin(to: publicKey, password: password)
        }
    }

    /// Pushes the local contact to the radio's contact table with flood routing, then reconciles the
    /// local row so keep-alive routing agrees with the radio.
    private func addLocalContactToRadio(publicKey: Data, radioID: UUID) async throws {
        guard let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
            throw RemoteNodeError.contactNotFound
        }
        let frame = contact.floodedContactFrame(asOf: UInt32(Date().timeIntervalSince1970))
        do {
            try await session.addContact(frame.toMeshContact())
        } catch let error as MeshCoreError {
            guard case .deviceError(let code) = error, code == ProtocolError.tableFull.rawValue else {
                throw error
            }
            throw RemoteNodeError.radioContactsFull
        }
        // The radio is healed; failing to sync the local row is a bookkeeping issue that must not
        // abort the login retry. Keep-alive routing self-corrects on the next contact refresh.
        do {
            _ = try await dataStore.saveContact(radioID: radioID, from: frame)
        } catch {
            logger.warning("Re-added contact to radio but failed to sync local row: \(error)")
        }
        logger.info("Re-added missing contact to radio during login")
    }

    /// Handle login result push from device.
    func handleLoginResult(_ result: LoginResult, fromPublicKeyPrefix: Data) async {
        guard fromPublicKeyPrefix.count >= 6 else {
            logger.warning("Login result has invalid prefix length: \(fromPublicKeyPrefix.count)")
            return
        }

        let prefix = Data(fromPublicKeyPrefix.prefix(6))
        let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
        let pendingKeys = pendingLogins.keys.map { $0.map { String(format: "%02x", $0) }.joined() }
        logger.info("handleLoginResult: looking for prefix \(prefixHex), pending keys: \(pendingKeys)")
        guard let continuation = pendingLogins.removeValue(forKey: prefix) else {
            logger.warning("Login result with no pending request. Prefix: \(prefixHex)")
            return
        }
        pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
        logger.info("handleLoginResult: found continuation for prefix \(prefixHex)")

        if result.success {
            // Update session state
            do {
                guard let remoteSession = try await dataStore.fetchRemoteNodeSessionByPrefix(prefix) else {
                    logger.error("handleLoginResult: no session found for prefix \(prefixHex) - database may be corrupted")
                    continuation.resume(returning: result)
                    return
                }

                // Log successful login
                let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
                await auditLogger.logLoginSuccess(target: targetType, publicKey: prefix, isAdmin: result.isAdmin)

                let permission = result.permissionLevel

                logger.info("handleLoginResult: updating session \(remoteSession.id) isConnected=true, permission=\(permission.rawValue)")

                try await dataStore.updateRemoteNodeSessionConnection(
                    id: remoteSession.id,
                    isConnected: true,
                    permissionLevel: permission
                )

                // Verify the update succeeded
                if let verifySession = try await dataStore.fetchRemoteNodeSession(id: remoteSession.id) {
                    if verifySession.isConnected {
                        logger.info("handleLoginResult: verified session \(remoteSession.id) isConnected=true")
                    } else {
                        logger.error("handleLoginResult: session \(remoteSession.id) still shows isConnected=false after update!")
                    }
                }

                // Notify UI of session state change
                eventBroadcaster.yield(.sessionStateChanged(sessionID: remoteSession.id, isConnected: true))

                keepAliveIntervals[remoteSession.id] = Self.defaultKeepAliveInterval
            } catch {
                logger.error("handleLoginResult: failed to update session state: \(error)")
            }
            continuation.resume(returning: result)
        } else {
            // Log failed login
            // Try to determine target type from existing session
            if let remoteSession = try? await dataStore.fetchRemoteNodeSessionByPrefix(prefix) {
                let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
                await auditLogger.logLoginFailed(target: targetType, publicKey: prefix, reason: "authentication failed")
            } else {
                await auditLogger.logLoginFailed(target: .repeater, publicKey: prefix, reason: "authentication failed")
            }
            continuation.resume(throwing: RemoteNodeError.loginFailed("authentication failed"))
        }
    }

    // MARK: - Keep-Alive (Room Servers)

    /// Start periodic keep-alive for a room server session.
    /// Sends an immediate keep-alive on start (for connectivity check + sync_since update),
    /// then continues at the configured interval. Transient failures are retried up to
    /// `KeepAliveRetryPolicy.maxConsecutiveFailures` times before disconnecting.
    private func startKeepAlive(sessionID: UUID, publicKey: Data) {
        stopKeepAlive(sessionID: sessionID)

        let interval = keepAliveIntervals[sessionID] ?? Self.defaultKeepAliveInterval

        // The actor retains this task via keepAliveTasks, so the loop captures
        // self weakly and rebinds per tick; a strong capture would keep the
        // actor (and its session/dataStore) alive for the app lifetime.
        let task = Task { [weak self] in
            var consecutiveFailures = 0

            while !Task.isCancelled {
                guard let tick = await self?.performKeepAliveTick(
                    sessionID: sessionID,
                    publicKey: publicKey,
                    consecutiveFailures: consecutiveFailures
                ), tick.shouldContinue else { return }
                consecutiveFailures = tick.consecutiveFailures

                try? await Task.sleep(for: interval)
            }
        }

        keepAliveTasks[sessionID] = task
    }

    /// Runs one keep-alive attempt for the loop in `startKeepAlive`.
    /// Returns whether the loop should continue and the updated failure count.
    private func performKeepAliveTick(
        sessionID: UUID,
        publicKey: Data,
        consecutiveFailures: Int
    ) async -> (shouldContinue: Bool, consecutiveFailures: Int) {
        var failures = consecutiveFailures
        do {
            try await sendKeepAliveIfDirectRouted(sessionID: sessionID, publicKey: publicKey)
            KeepAliveRetryPolicy.recordSuccess(consecutiveFailures: &failures)
            return (shouldContinue: true, consecutiveFailures: failures)
        } catch {
            let action = KeepAliveRetryPolicy.evaluate(error: error, consecutiveFailures: &failures)
            switch action {
            case .stop:
                break
            case .skip:
                logger.info("Skipping keep-alive for flood-routed session \(sessionID)")
            case .retryNextInterval:
                let reason = KeepAliveRetryPolicy.failureReason(for: error)
                logger.warning("Keep-alive \(failures)/\(KeepAliveRetryPolicy.maxConsecutiveFailures) failed for \(sessionID): \(reason)")
            case .disconnect, .disconnectNow:
                let reason = KeepAliveRetryPolicy.failureReason(for: error)
                logger.warning("Keep-alive failed for \(sessionID): \(reason)")
                do {
                    try await dataStore.markSessionDisconnected(sessionID)
                } catch {
                    logger.error("Failed to persist disconnected state for session \(sessionID): \(error)")
                }
                eventBroadcaster.yield(.sessionStateChanged(sessionID: sessionID, isConnected: false))
            }
            return (shouldContinue: !action.shouldExitLoop, consecutiveFailures: failures)
        }
    }

    /// Stop keep-alive for a session
    func stopKeepAlive(sessionID: UUID) {
        keepAliveTasks[sessionID]?.cancel()
        keepAliveTasks.removeValue(forKey: sessionID)
    }

    /// Send keep-alive only if the session has a direct routing path.
    private func sendKeepAliveIfDirectRouted(sessionID: UUID, publicKey: Data) async throws {
        // Fetch session to get radioID
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Check contact's routing status
        guard let contact = try await dataStore.fetchContact(radioID: remoteSession.radioID, publicKey: publicKey) else {
            throw RemoteNodeError.contactNotFound
        }

        // Keep-alive only works with direct routing
        if contact.isFloodRouted {
            throw RemoteNodeError.floodRouted
        }

        // Log keep-alive
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logKeepAlive(target: targetType, publicKey: publicKey)

        // Send keep-alive with sync_since for force-resync hint
        do {
            _ = try await session.sendKeepAlive(to: publicKey, syncSince: remoteSession.lastSyncTimestamp)
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    /// Public method to send keep-alive (for manual refresh).
    public func sendKeepAlive(sessionID: UUID) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }
        try await sendKeepAliveIfDirectRouted(sessionID: sessionID, publicKey: remoteSession.publicKey)
    }

    /// Start keep-alive for a room session (called when room view appears).
    public func startSessionKeepAlive(sessionID: UUID, publicKey: Data) {
        startKeepAlive(sessionID: sessionID, publicKey: publicKey)
    }

    /// Stop keep-alive for a room session (called when room view disappears).
    public func stopSessionKeepAlive(sessionID: UUID) {
        stopKeepAlive(sessionID: sessionID)
    }

    // MARK: - History Sync

    /// Request message history from a room server.
    public func requestHistorySync(sessionID: UUID) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isRoom else {
            throw RemoteNodeError.invalidResponse
        }

        // Check for direct route
        guard let contact = try await dataStore.fetchContact(radioID: remoteSession.radioID, publicKey: remoteSession.publicKey) else {
            throw RemoteNodeError.contactNotFound
        }

        if contact.isFloodRouted {
            throw RemoteNodeError.floodRouted
        }

        // Request status (which triggers sync)
        do {
            let contactType: ContactType = remoteSession.isRoom ? .room : .repeater
            _ = try await session.requestStatus(from: remoteSession.publicKey, type: contactType)
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }

        logger.info("Requested history sync for room \(remoteSession.name)")
    }

    // MARK: - Logout

    /// Explicitly logout from a remote node.
    public func logout(sessionID: UUID) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Log logout
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logLogout(target: targetType, publicKey: remoteSession.publicKey)

        stopKeepAlive(sessionID: sessionID)

        do {
            try await session.sendLogout(to: remoteSession.publicKey)
        } catch {
            // Ignore errors - we're disconnecting anyway
            logger.info("Logout send failed (ignoring): \(error)")
        }

        try await dataStore.updateRemoteNodeSessionConnection(
            id: sessionID,
            isConnected: false,
            permissionLevel: .guest
        )

        // Notify UI of session state change
        eventBroadcaster.yield(.sessionStateChanged(sessionID: sessionID, isConnected: false))
    }
}
