import Foundation
import os

extension ChatSendQueueService {
  /// Values the channel drain closes over. The queue does not retain the service.
  nonisolated struct ChannelDrainDependencies: Sendable {
    let radioID: UUID
    let dataStore: any MessagePersisting
    let messageService: MessageService
    let channelService: ChannelService
    let reactionService: ReactionService
    let triggers: BLETransportOpenedSignal
    let logger: PersistentLogger
    let osLogger: Logger
    let failureCounter: FailureCounter
    let config: ChatSendQueueConfig
    let sendsRetired: OSAllocatedUnfairLock<Bool>
  }

  /// Outcome of the bump and the radio send. Both run under the channel gate.
  /// `Error` returns to the awaiting task after the gate releases.
  private nonisolated enum QueuedChannelTransmission: @unchecked Sendable {
    case dropped
    case preflightFailed(Error)
    case sent(UInt32)
    case radioFailed(Error, postBumpCount: Int)
  }

  /// Drains one queued channel send. A database error during the bump parks outside the channel gate.
  nonisolated static func sendQueuedChannel(
    envelope: ChannelMessageEnvelope,
    dependencies: ChannelDrainDependencies
  ) async throws {
    do {
      guard try await validateQueuedChannel(
        envelope: envelope,
        radioID: dependencies.radioID,
        dataStore: dependencies.dataStore,
        channelService: dependencies.channelService,
        triggers: dependencies.triggers,
        logger: dependencies.logger,
        timeout: dependencies.config.transportWaitTimeout
      ) else { return }
      try cancelSendIfRetired(dependencies.sendsRetired)

      switch try await transmitQueuedChannel(envelope: envelope, dependencies: dependencies) {
      case .dropped:
        return
      case let .preflightFailed(error):
        _ = try? await dependencies.dataStore.updateMessageStatusUnlessDelivered(
          id: envelope.messageID,
          status: .pending
        )
        dependencies.logger.warning("channel drain preflight failed: \(String(describing: error))")
        try await parkAndCancel(
          triggers: dependencies.triggers,
          logger: dependencies.logger,
          messageID: envelope.messageID,
          kind: "channel",
          timeout: dependencies.config.transportWaitTimeout
        )
      case let .sent(indexTimestamp):
        if let nodeName = envelope.localNodeName, !MCOImageCodec.isImageText(envelope.messageText) {
          _ = await dependencies.reactionService.indexMessage(
            id: envelope.messageID,
            channelIndex: envelope.channelIndex,
            senderName: nodeName,
            text: envelope.messageText,
            timestamp: indexTimestamp
          )
        }
        try? await dependencies.dataStore.deletePendingSendsForMessage(messageID: envelope.messageID)
        dependencies.osLogger.debug("channel drain success messageID=\(envelope.messageID)")
        await dependencies.failureCounter.reset(for: envelope.messageID)
        await dependencies.triggers.clear()
      case let .radioFailed(error, postBumpCount):
        try await handleQueuedChannelRadioFailure(
          error,
          postBumpCount: postBumpCount,
          envelope: envelope,
          dependencies: dependencies
        )
      }
    } catch let cancellation as CancellationError {
      throw cancellation
    } catch {
      await dependencies.messageService.notifyMessageFailed(messageID: envelope.messageID)
      throw error
    }
  }

  /// The bump and the radio send share the channel gate. A slot rewrite
  /// deletes this pending row before it can acquire the gate and transmit.
  private nonisolated static func transmitQueuedChannel(
    envelope: ChannelMessageEnvelope,
    dependencies: ChannelDrainDependencies
  ) async throws -> QueuedChannelTransmission {
    try await dependencies.channelService.withChannelOperation { () -> QueuedChannelTransmission in
      let prepared: (postBumpCount: Int, preserveTimestamp: Bool)
      do {
        guard let result = try await preflightAndBump(
          dataStore: dependencies.dataStore,
          messageID: envelope.messageID,
          kind: "channel",
          logger: dependencies.logger,
          osLogger: dependencies.osLogger
        ) else { return .dropped }
        prepared = (result.postBumpCount, result.preserveTimestamp)
      } catch {
        return .preflightFailed(error)
      }

      do {
        if envelope.isResend {
          let timestamp = try await dependencies.messageService.resendChannelMessage(
            messageID: envelope.messageID,
            preserveTimestamp: prepared.preserveTimestamp
          )
          return .sent(timestamp)
        }
        try await dependencies.messageService.sendPendingChannelMessage(messageID: envelope.messageID)
        return .sent(envelope.messageTimestamp)
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        return .radioFailed(error, postBumpCount: prepared.postBumpCount)
      }
    }
  }

  private nonisolated static func handleQueuedChannelRadioFailure(
    _ error: Error,
    postBumpCount: Int,
    envelope: ChannelMessageEnvelope,
    dependencies: ChannelDrainDependencies
  ) async throws {
    guard isTransientChannelMessageError(
      error, isImage: MCOImageCodec.isImageText(envelope.messageText)
    ) else {
      dependencies.logger.info(
        "channel drain terminal messageID=\(envelope.messageID) error=\(String(describing: error))"
      )
      throw error
    }
    if isChannelMessageNotFound(error) {
      // Disambiguate pool exhaustion (transient) from a stale
      // channel index (terminal) by refreshing the radio's
      // view of the channel. `ChannelService.fetchChannel(index:)`
      // reads from the device, so a nil result means the radio
      // agrees the channel is gone (terminal).
      //
      // Gate on disambiguateAfterAttempts so the common
      // pool-exhaustion burst (1-2 NOT_FOUNDs in a row) parks
      // without an extra BLE round-trip; only persistent
      // NOT_FOUND warrants the fetchChannel cost.
      if postBumpCount < dependencies.config.disambiguateAfterAttempts {
        dependencies.logger.info(
          "channel drain NOT_FOUND below disambiguate threshold messageID=\(envelope.messageID) postBumpCount=\(postBumpCount); parking envelope"
        )
        _ = try? await dependencies.dataStore.updateMessageStatusUnlessDelivered(
          id: envelope.messageID,
          status: .pending
        )
        try await parkAndCancel(
          triggers: dependencies.triggers,
          logger: dependencies.logger,
          messageID: envelope.messageID,
          kind: "channel",
          timeout: dependencies.config.transportWaitTimeout
        )
      }

      let stillExists: Bool
      do {
        stillExists = try await dependencies.channelService.fetchChannel(index: envelope.channelIndex) != nil
        await dependencies.failureCounter.reset(for: envelope.messageID)
      } catch {
        let failures = await dependencies.failureCounter.increment(for: envelope.messageID)
        if failures >= dependencies.config.maxConsecutiveFetchChannelFailures {
          dependencies.logger.warning(
            "Channel drain: fetchChannel persistently failing (\(failures) consecutive) for messageID=\(envelope.messageID); marking terminal"
          )
          await dependencies.failureCounter.reset(for: envelope.messageID)
          throw error
        }
        dependencies.logger.info(
          "channel drain NOT_FOUND followup fetchChannel failed: \(String(describing: error)); parking envelope (\(failures)/\(dependencies.config.maxConsecutiveFetchChannelFailures))"
        )
        _ = try? await dependencies.dataStore.updateMessageStatusUnlessDelivered(
          id: envelope.messageID,
          status: .pending
        )
        try await parkAndCancel(
          triggers: dependencies.triggers,
          logger: dependencies.logger,
          messageID: envelope.messageID,
          kind: "channel",
          timeout: dependencies.config.transportWaitTimeout
        )
      }
      if !stillExists {
        dependencies.logger.warning(
          "Channel drain: NOT_FOUND confirmed by fetchChannel for messageID=\(envelope.messageID); treating as terminal (channel deleted)"
        )
        await dependencies.failureCounter.reset(for: envelope.messageID)
        throw error
      }
      // Channel still exists per device. NOT_FOUND was pool
      // exhaustion. Park on the transport-open trigger. The
      // effective per-attempt cadence under sustained
      // exhaustion is ~37s (3.5s withPoolBackoff in-loop +
      // 3.5s fetchChannel disambiguation + 30s
      // transportWaitTimeout park). Transport-open only fires
      // on entering `.ready`, so under a healthy connection
      // the 30s park is what bounds the retry rate.
    }
    dependencies.logger.info(
      "channel drain transient messageID=\(envelope.messageID) error=\(String(describing: error))"
    )
    _ = try? await dependencies.dataStore.updateMessageStatusUnlessDelivered(
      id: envelope.messageID,
      status: .pending
    )
    try await parkAndCancel(
      triggers: dependencies.triggers,
      logger: dependencies.logger,
      messageID: envelope.messageID,
      kind: "channel",
      timeout: dependencies.config.transportWaitTimeout
    )
  }
}
