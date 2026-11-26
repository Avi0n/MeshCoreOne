import Foundation
import MeshCore

extension ContactService {
  /// Repairs a direct-routed exchange over flood and restores the pre-fallback path
  /// on failure or cancellation if the radio contact is still flood-routed.
  public func attemptFloodFallback<T: Sendable>(
    radioID: UUID,
    publicKey: Data,
    rethrowIfUnavailable callerError: Error,
    operation: @Sendable () async throws -> T
  ) async throws -> T {
    let key = FailedRepairKey(radioID: radioID, publicKey: publicKey)
    guard RemoteFloodFallback.isEnabled(defaults: defaults),
          !hasActiveFailedRepair(for: key) else {
      throw callerError
    }

    let liveContact: MeshContact?
    do {
      liveContact = try await session.getContact(publicKey: publicKey)
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      throw callerError
    }
    guard let liveContact,
          liveContact.outPathLength != PacketBuilder.floodPathSentinel else {
      throw callerError
    }

    let savedPath = liveContact.outPath
    let savedLength = liveContact.outPathLength

    // The reset sits inside the restore scope so cancellation after its radio write still restores.
    do {
      try await resetPath(radioID: radioID, publicKey: publicKey)
      return try await operation()
    } catch {
      _ = await restoreIfStillFlood(
        radioID: radioID,
        publicKey: publicKey,
        savedPath: savedPath,
        savedLength: savedLength,
        recordFailureWindow: true,
        deferOnLinkDrop: true
      )
      throw error
    }
  }

  /// Replays a deferred restore on reconnect before `.ready`; returns `false` only if another link drop leaves it pending.
  func applyDeferredRouteRestore(_ restore: PendingRouteRestore) async -> Bool {
    await restoreIfStillFlood(
      radioID: restore.radioID,
      publicKey: restore.publicKey,
      savedPath: restore.outPath,
      savedLength: restore.outPathLength,
      recordFailureWindow: false,
      deferOnLinkDrop: false
    )
  }

  private func restoreIfStillFlood(
    radioID: UUID,
    publicKey: Data,
    savedPath: Data,
    savedLength: UInt8,
    recordFailureWindow: Bool,
    deferOnLinkDrop: Bool
  ) async -> Bool {
    // Run inside an unstructured Task so caller cancellation cannot interrupt restore.
    let restoreTask = Task<Bool, Never> {
      do {
        guard let current = try await self.session.getContact(publicKey: publicKey) else {
          return true
        }
        if current.outPathLength != PacketBuilder.floodPathSentinel {
          _ = try? await self.dataStore.updateContactRoute(
            radioID: radioID,
            publicKey: publicKey,
            outPathLength: current.outPathLength,
            outPath: current.outPath
          )
          return true
        }
        try await self.setPath(
          radioID: radioID,
          publicKey: publicKey,
          path: savedPath,
          pathLength: savedLength
        )
        if recordFailureWindow {
          self.recordFailedRepair(for: FailedRepairKey(radioID: radioID, publicKey: publicKey))
        }
        return true
      } catch ContactServiceError.contactNotFound {
        return true
      } catch let serviceError as ContactServiceError where Self.isLinkDropServiceError(serviceError) {
        if deferOnLinkDrop {
          await self.deferRouteRestore(
            PendingRouteRestore(
              radioID: radioID,
              publicKey: publicKey,
              outPath: savedPath,
              outPathLength: savedLength
            ),
            reason: serviceError
          )
        }
        return false
      } catch let meshError as MeshCoreError where Self.isLinkDropMeshError(meshError) {
        if deferOnLinkDrop {
          await self.deferRouteRestore(
            PendingRouteRestore(
              radioID: radioID,
              publicKey: publicKey,
              outPath: savedPath,
              outPathLength: savedLength
            ),
            reason: meshError
          )
        }
        return false
      } catch is CancellationError {
        if deferOnLinkDrop {
          await self.deferRouteRestore(
            PendingRouteRestore(
              radioID: radioID,
              publicKey: publicKey,
              outPath: savedPath,
              outPathLength: savedLength
            ),
            reason: CancellationError()
          )
        }
        return false
      } catch {
        self.logger.error("Route restore failed: \(error.localizedDescription)")
        return false
      }
    }
    return await restoreTask.value
  }

  private func deferRouteRestore(_ restore: PendingRouteRestore, reason: Error) async {
    let keyHex = restore.publicKey.prefix(3).uppercaseHexString()
    logger.info("Deferring route restore for \(keyHex) after link drop: \(reason.localizedDescription)")
    await onDeferredRouteRestore?(restore)
  }

  private func hasActiveFailedRepair(for key: FailedRepairKey) -> Bool {
    guard let remaining = failedRepairDeadlines[key] else { return false }
    if remaining() > .zero {
      return true
    }
    failedRepairDeadlines[key] = nil
    return false
  }

  private func recordFailedRepair(for key: FailedRepairKey) {
    failedRepairDeadlines[key] = clock.countdown(from: RemoteOperationTimeoutPolicy.failedRepairWindow)
  }

  private static func isLinkDropServiceError(_ error: ContactServiceError) -> Bool {
    switch error {
    case .notConnected, .syncInterrupted:
      true
    case let .sessionError(meshError):
      isLinkDropMeshError(meshError)
    default:
      false
    }
  }

  private static func isLinkDropMeshError(_ error: MeshCoreError) -> Bool {
    switch error {
    case .notConnected, .connectionLost, .bluetoothPoweredOff, .sessionNotStarted, .bluetoothUnavailable:
      true
    default:
      false
    }
  }
}

private extension Clock where Duration == Swift.Duration {
  func countdown(from duration: Duration) -> @Sendable () -> Duration {
    let start = now
    return { duration - start.duration(to: self.now) }
  }
}
