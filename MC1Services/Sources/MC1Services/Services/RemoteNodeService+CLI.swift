import Foundation
import MeshCore

extension RemoteNodeService {

    // MARK: - CLI Commands

    /// Send a CLI command to a remote node and wait for response (admin only).
    /// - Parameters:
    ///   - sessionID: The remote node session ID.
    ///   - command: The CLI command to send.
    ///   - timeout: Hard maximum time to wait for response (default 10 seconds).
    /// - Returns: The CLI response text from the remote node.
    public func sendCLICommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isAdmin else {
            throw RemoteNodeError.permissionDenied
        }

        // Log CLI command (with password redaction)
        await auditLogger.logCLICommand(publicKey: remoteSession.publicKey, command: command)

        let destinationPrefix = Data(remoteSession.publicKey.prefix(6))
        let requestTimestamp = Date()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let request = PendingCLIRequest(
                    command: command,
                    continuation: continuation,
                    timestamp: requestTimestamp
                )

                if pendingCLIRequests[destinationPrefix] == nil {
                    pendingCLIRequests[destinationPrefix] = []
                }
                pendingCLIRequests[destinationPrefix]!.append(request)

                Task { [self] in
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await session.sendCommand(to: remoteSession.publicKey, command: command)
                    } catch {
                        if var requests = pendingCLIRequests[destinationPrefix],
                           let index = requests.firstIndex(where: { $0.timestamp == requestTimestamp }) {
                            let failed = requests.remove(at: index)
                            pendingCLIRequests[destinationPrefix] = requests.isEmpty ? nil : requests
                            let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
                            failed.continuation.resume(throwing: RemoteNodeError.sessionError(meshError))
                        }
                        return
                    }

                    let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)
                    let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
                    while ContinuousClock.now < deadline {
                        if let requests = pendingCLIRequests[destinationPrefix],
                           !requests.contains(where: { $0.timestamp == requestTimestamp }) {
                            return
                        } else if pendingCLIRequests[destinationPrefix] == nil {
                            return
                        }

                        let remaining = deadline - .now
                        let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
                        _ = try? await session.getMessage(timeout: max(0.1, timeInterval(for: pollDuration)))
                    }

                    if var requests = pendingCLIRequests[destinationPrefix],
                       let index = requests.firstIndex(where: { $0.timestamp == requestTimestamp }) {
                        let timedOut = requests.remove(at: index)
                        pendingCLIRequests[destinationPrefix] = requests.isEmpty ? nil : requests
                        timedOut.continuation.resume(throwing: RemoteNodeError.timeout)
                    }
                }
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingCLIRequest(for: destinationPrefix, timestamp: requestTimestamp)
            }
        }
    }

    /// Send a raw CLI command to a remote node using FIFO response matching (admin only).
    /// Unlike `sendCLICommand`, this method accepts any response from the target node
    /// without content-based matching. Used by CLI tool for passthrough commands.
    /// - Parameters:
    ///   - sessionID: The remote node session ID.
    ///   - command: The CLI command to send.
    ///   - timeout: Hard maximum time to wait for response (default 10 seconds).
    /// - Returns: The raw response text from the remote node.
    public func sendRawCLICommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isAdmin else {
            throw RemoteNodeError.permissionDenied
        }

        // Log CLI command (with password redaction)
        await auditLogger.logCLICommand(publicKey: remoteSession.publicKey, command: command)

        let destinationPrefix = Data(remoteSession.publicKey.prefix(6))

        // Only one raw CLI request per sender at a time (FIFO matching)
        guard pendingRawCLIRequests[destinationPrefix] == nil else {
            throw RemoteNodeError.sessionError(.connectionLost(underlying: nil))
        }

        // Register continuation BEFORE sending to avoid race condition
        // Use withTaskCancellationHandler to clean up pending request if caller cancels
        let response = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pendingRawCLIRequests[destinationPrefix] = continuation

                Task { [self] in
                    // Send CLI command
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await session.sendCommand(to: remoteSession.publicKey, command: command)
                    } catch {
                        // Send failed - remove pending request and resume with error
                        if let pending = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                            let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
                            pending.resume(throwing: RemoteNodeError.sessionError(meshError))
                        }
                        return
                    }

                    let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)

                    // Poll for response
                    let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
                    while ContinuousClock.now < deadline {
                        // Check if our request was already satisfied
                        guard pendingRawCLIRequests[destinationPrefix] != nil else {
                            return  // Request was matched and removed by handleCLIResponse
                        }

                        // Check for task cancellation
                        if Task.isCancelled {
                            if let cancelled = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                                cancelled.resume(throwing: CancellationError())
                            }
                            return
                        }

                        // Poll device for pending messages
                        let remaining = deadline - .now
                        let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
                        _ = try? await session.getMessage(timeout: max(0.1, timeInterval(for: pollDuration)))
                    }

                    // Timeout - remove pending request and resume with error
                    if let timedOut = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                        timedOut.resume(throwing: RemoteNodeError.timeout)
                    }
                }
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingRawCLIRequest(for: destinationPrefix)
            }
        }

        // Clear stored password after admin password change
        await handlePasswordChangeIfNeeded(command: command, sessionID: sessionID)

        return response
    }

    /// Clear stored password if command is an admin password change.
    private func handlePasswordChangeIfNeeded(command: String, sessionID: UUID) async {
        let lower = command.lowercased().trimmingCharacters(in: .whitespaces)

        // Only admin password changes, not guest
        guard lower.hasPrefix("password ") && !lower.contains("guest.password") else {
            return
        }

        guard let session = try? await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            return
        }

        do {
            try await keychainService.deletePassword(forNodeKey: session.publicKey)
            logger.info("Cleared stored password after password change for session \(sessionID)")
        } catch {
            logger.warning("Failed to clear stored password for session \(sessionID): \(error)")
            // Next login fails naturally - user re-enters password, overwrites stale credential
        }
    }

    /// Cancel a pending raw CLI request when the calling task is cancelled.
    private func cancelPendingRawCLIRequest(for prefix: Data) {
        if let cancelled = pendingRawCLIRequests.removeValue(forKey: prefix) {
            cancelled.resume(throwing: CancellationError())
        }
    }
}
