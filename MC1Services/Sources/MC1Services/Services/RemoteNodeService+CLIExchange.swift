import Foundation
import MeshCore

extension RemoteNodeService {
  private static let minimumCLIPollTimeout: TimeInterval = 0.1

  func performCLIExchange(
    publicKey: Data,
    destinationPrefix: Data,
    command: String,
    acceptsAnyResponse: Bool,
    timeout: Duration,
    ownerID: UUID,
    textType: MeshCore.TextType
  ) async throws -> String {
    try validateCLISlot(for: destinationPrefix, ownerID: ownerID)
    let command = String(decoding: command.utf8.drop(while: { $0 == Self.cliIgnoredSpace }), as: UTF8.self)
    let wirePrefix = makeCLIWirePrefix()
    let requestID = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        guard !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        let worker = Task { [self] in
          await runCLIExchange(
            publicKey: publicKey, prefix: destinationPrefix, command: command,
            wirePrefix: wirePrefix, timeout: timeout, requestID: requestID, textType: textType
          )
        }
        cliSlots[destinationPrefix]?.beginExchange(RemoteCLISlot.Request(
          id: requestID,
          command: command,
          wirePrefix: wirePrefix,
          acceptsAnyResponse: acceptsAnyResponse,
          continuation: continuation,
          worker: worker
        ))
      }
    } onCancel: { [weak self] in
      Task { [weak self] in
        await self?.cancelPendingCLIRequest(for: destinationPrefix, requestID: requestID)
      }
    }
  }

  private func runCLIExchange(
    publicKey: Data, prefix: Data, command: String, wirePrefix: String,
    timeout: Duration, requestID: UUID, textType: MeshCore.TextType
  ) async {
    let sentInfo: MessageSentInfo
    do {
      try Task.checkCancellation()
      sentInfo = try await session.sendCommand(
        to: publicKey,
        command: wirePrefix + command,
        textType: textType
      )
    } catch is CancellationError {
      cancelPendingCLIRequest(for: prefix, requestID: requestID)
      return
    } catch {
      let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
      cliSlots[prefix]?.claimCompletion(requestID: requestID)?.resume(
        throwing: RemoteNodeError.sessionError(meshError)
      )
      return
    }

    guard cliSlots[prefix]?.isCurrentRequest(requestID: requestID) == true else { return }
    let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)
    let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
    while ContinuousClock.now < deadline {
      guard cliSlots[prefix]?.isCurrentRequest(requestID: requestID) == true else { return }

      let remaining = deadline - .now
      let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
      do {
        _ = try await session.getMessage(timeout: max(Self.minimumCLIPollTimeout, timeInterval(for: pollDuration)))
      } catch is CancellationError {
        cancelPendingCLIRequest(for: prefix, requestID: requestID)
        return
      } catch {
        // A single poll can miss the reply. The deadline is the timeout.
      }
    }

    cliSlots[prefix]?.claimCompletion(requestID: requestID)?.resume(throwing: RemoteNodeError.timeout)
  }

  private func cancelPendingCLIRequest(for prefix: Data, requestID: UUID) {
    cliSlots[prefix]?.claimCompletion(requestID: requestID)?.resume(throwing: CancellationError())
  }

  // MARK: - CLI Slot

  func validateCLISlot(for prefix: Data, ownerID: UUID) throws {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard cliSlots[prefix]?.ownerID == ownerID else { throw CancellationError() }
  }

  func acquireCLISlot(for prefix: Data, waiterID: UUID) async throws {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard cliSlots[prefix] != nil else {
      cliSlots[prefix] = RemoteCLISlot(ownerID: waiterID)
      return
    }

    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        cliSlots[prefix]?.enqueue(RemoteCLISlot.Waiter(id: waiterID, continuation: continuation))
      }
    } onCancel: { [weak self] in
      Task { [weak self] in
        await self?.cancelCLISlotWaiter(for: prefix, waiterID: waiterID)
      }
    }
  }

  func releaseCLISlot(for prefix: Data, ownerID: UUID) {
    guard cliSlots[prefix]?.ownerID == ownerID else { return }
    if let next = cliSlots[prefix]?.advance() {
      next.continuation.resume()
    } else {
      cliSlots[prefix] = nil
    }
  }

  private func cancelCLISlotWaiter(for prefix: Data, waiterID: UUID) {
    cliSlots[prefix]?.removeWaiter(id: waiterID)?.continuation.resume(throwing: CancellationError())
  }

  /// Match echoed wire prefixes; older firmware replies use single-flight shape validation.
  func handleCLIResponse(_ message: ContactMessage) {
    let prefix = Data(message.senderPublicKeyPrefix.prefix(Self.cliPublicKeyPrefixLength))

    guard let pending = cliSlots[prefix]?.pendingRequest else {
      logger.debug("Unmatched CLI response (no pending request), length \(message.text.count)")
      return
    }

    let responseText: String
    if let echoed = CLIResponse.splitEchoedPrefix(message.text) {
      guard echoed.prefix == pending.wirePrefix else {
        logger.warning(
          "Dropping stale CLI response with prefix \(echoed.prefix) while awaiting \(pending.wirePrefix)"
        )
        return
      }
      responseText = echoed.body
    } else {
      guard pending.acceptsAnyResponse
        || CLIResponse.isPlausibleResponse(message.text, forQuery: pending.command) else {
        logger.warning(
          "Dropping CLI response that doesn't match pending command, length \(message.text.count)"
        )
        return
      }
      responseText = message.text
    }

    cliSlots[prefix]?.claimCompletion(requestID: pending.id)?.resume(returning: responseText)
  }
}
