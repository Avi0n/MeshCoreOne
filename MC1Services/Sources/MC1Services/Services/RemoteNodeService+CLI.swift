import Foundation
import MeshCore

extension RemoteNodeService {
  private static let cliIgnoredSpace: UInt8 = 0x20

  // MARK: - CLI Commands

  /// Send a CLI command to a remote node and wait for its response (admin only).
  /// Replies to structured `get` queries must parse to their expected shape;
  /// anything else waiting in the mesh is dropped instead of misattributed.
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
    try await performCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout,
      acceptsAnyResponse: false
    )
  }

  /// Send a raw CLI command to a remote node (admin only). The next reply from
  /// the node is delivered verbatim without shape validation. Used by CLI
  /// terminals and commands whose reply format is free-form.
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
    let response = try await performCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout,
      acceptsAnyResponse: true
    )

    // Clear stored password after admin password change
    await handlePasswordChangeIfNeeded(command: command, sessionID: sessionID)

    return response
  }

  /// Shared send path: acquires the node's CLI slot so exactly one command is
  /// in flight per node, then performs the exchange.
  private func performCLICommand(
    sessionID: UUID,
    command: String,
    timeout: Duration,
    acceptsAnyResponse: Bool
  ) async throws -> String {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard remoteSession.isAdmin else {
      throw RemoteNodeError.permissionDenied
    }

    let command = RemoteCLICommandRewriter.rewrite(command)

    // Log CLI command (with password redaction)
    await auditLogger.logCLICommand(
      publicKey: remoteSession.publicKey,
      command: command,
      target: .repeater
    )

    let destinationPrefix = Data(remoteSession.publicKey.prefix(6))
    let requestID = UUID()

    try await acquireCLISlot(for: destinationPrefix, waiterID: requestID)
    defer { releaseCLISlot(for: destinationPrefix) }
    try Task.checkCancellation()

    // A no-reply command is sent once. Path reset and resend would repeat it.
    if RemoteCLINoReplyCommand(command) != nil {
      return try await performCLIExchange(
        publicKey: remoteSession.publicKey,
        destinationPrefix: destinationPrefix,
        command: command,
        acceptsAnyResponse: acceptsAnyResponse,
        timeout: timeout,
        requestID: requestID,
        textType: .cliData
      )
    }

    return try await performWithDirectPathFloodRecovery(
      radioID: remoteSession.radioID,
      publicKey: remoteSession.publicKey,
      operationName: "remoteCLI"
    ) {
      try await self.performCLIExchange(
        publicKey: remoteSession.publicKey,
        destinationPrefix: destinationPrefix,
        command: command,
        acceptsAnyResponse: acceptsAnyResponse,
        timeout: timeout,
        requestID: requestID,
        textType: .cliData
      )
    }
  }

  /// Sends a Radio CLI command to a chat contact. The contact runs it only after granting this radio Remote CLI.
  /// A missing reply is a timeout, not a reason to reset the contact's direct path.
  public func sendRadioCLICommand(
    to publicKey: Data,
    command: String,
    timeout: Duration
  ) async throws -> String {
    let destinationPrefix = Data(publicKey.prefix(6))
    let requestID = UUID()
    await auditLogger.logCLICommand(publicKey: publicKey, command: command, target: .contact)

    try await acquireCLISlot(for: destinationPrefix, waiterID: requestID)
    defer { releaseCLISlot(for: destinationPrefix) }
    try Task.checkCancellation()

    return try await performCLIExchange(
      publicKey: publicKey,
      destinationPrefix: destinationPrefix,
      command: command,
      acceptsAnyResponse: false,
      timeout: timeout,
      requestID: requestID,
      textType: .cliCommand
    )
  }

  /// Register the pending request, send the command, and poll the device for
  /// the reply until it arrives or the effective timeout elapses.
  private func performCLIExchange(
    publicKey: Data,
    destinationPrefix: Data,
    command: String,
    acceptsAnyResponse: Bool,
    timeout: Duration,
    requestID: UUID,
    textType: MeshCore.TextType
  ) async throws -> String {
    let command = String(decoding: command.utf8.drop(while: { $0 == Self.cliIgnoredSpace }), as: UTF8.self)
    let wirePrefix = makeCLIWirePrefix()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        guard !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        pendingCLIRequests[destinationPrefix] = PendingCLIRequest(
          id: requestID,
          command: command,
          wirePrefix: wirePrefix,
          acceptsAnyResponse: acceptsAnyResponse,
          continuation: continuation
        )

        pendingCLIRequests[destinationPrefix]?.workerTask = Task { [self] in
          let sentInfo: MessageSentInfo
          do {
            try Task.checkCancellation()
            sentInfo = try await session.sendCommand(
              to: publicKey,
              command: wirePrefix + command,
              textType: textType
            )
          } catch is CancellationError {
            if let failed = takePendingCLIRequest(for: destinationPrefix, requestID: requestID) {
              failed.continuation.resume(throwing: CancellationError())
            }
            return
          } catch {
            if let failed = takePendingCLIRequest(for: destinationPrefix, requestID: requestID) {
              let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
              failed.continuation.resume(throwing: RemoteNodeError.sessionError(meshError))
            }
            return
          }

          let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)
          let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
          while ContinuousClock.now < deadline {
            guard pendingCLIRequests[destinationPrefix]?.id == requestID else {
              return // Request was resumed by handleCLIResponse or cancelled
            }

            let remaining = deadline - .now
            let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
            do {
              _ = try await session.getMessage(timeout: max(0.1, timeInterval(for: pollDuration)))
            } catch is CancellationError {
              if let stopped = takePendingCLIRequest(for: destinationPrefix, requestID: requestID) {
                stopped.continuation.resume(throwing: CancellationError())
              }
              return
            } catch {
              // A single poll can miss the reply. The deadline is the timeout.
            }
          }

          if let timedOut = takePendingCLIRequest(for: destinationPrefix, requestID: requestID) {
            timedOut.continuation.resume(throwing: RemoteNodeError.timeout)
          }
        }
      }
    } onCancel: { [weak self] in
      Task { [weak self] in
        await self?.cancelPendingCLIRequest(for: destinationPrefix, requestID: requestID)
      }
    }
  }

  /// Remove and return the pending request if it is still the given one.
  func takePendingCLIRequest(for prefix: Data, requestID: UUID) -> PendingCLIRequest? {
    guard let pending = pendingCLIRequests[prefix], pending.id == requestID else { return nil }
    pendingCLIRequests[prefix] = nil
    // Taken first, so the worker's cancellation path does not resume this continuation.
    pending.workerTask?.cancel()
    return pending
  }

  // MARK: - CLI Slot

  /// Wait for the node's CLI slot (FIFO). Throws `CancellationError` if the
  /// calling task is cancelled while waiting.
  private func acquireCLISlot(for prefix: Data, waiterID: UUID) async throws {
    try Task.checkCancellation()
    guard cliSlotBusy.contains(prefix) else {
      cliSlotBusy.insert(prefix)
      return
    }

    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        cliSlotWaiters[prefix, default: []].append(CLISlotWaiter(id: waiterID, continuation: continuation))
      }
    } onCancel: { [weak self] in
      Task { [weak self] in
        await self?.cancelCLISlotWaiter(for: prefix, waiterID: waiterID)
      }
    }
  }

  /// Hand the slot to the next waiter, or free it when none are queued.
  private func releaseCLISlot(for prefix: Data) {
    if var waiters = cliSlotWaiters[prefix], !waiters.isEmpty {
      let next = waiters.removeFirst()
      cliSlotWaiters[prefix] = waiters.isEmpty ? nil : waiters
      next.continuation.resume()
    } else {
      cliSlotBusy.remove(prefix)
    }
  }

  /// Cancel a queued slot waiter when its calling task is cancelled.
  private func cancelCLISlotWaiter(for prefix: Data, waiterID: UUID) {
    guard var waiters = cliSlotWaiters[prefix],
          let index = waiters.firstIndex(where: { $0.id == waiterID }) else {
      return
    }

    let cancelled = waiters.remove(at: index)
    cliSlotWaiters[prefix] = waiters.isEmpty ? nil : waiters
    cancelled.continuation.resume(throwing: CancellationError())
  }

  /// Clear stored password if command is an admin password change.
  private func handlePasswordChangeIfNeeded(command: String, sessionID: UUID) async {
    let lower = command.lowercased().trimmingCharacters(in: .whitespaces)

    // Only admin password changes, not guest
    guard lower.hasPrefix("password "), !lower.contains("guest.password") else {
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
}
