import Foundation
import MeshCore
import OSLog

/// Service for correlating RX log entries to known messages
/// and tracking extra flood paths plus sent-echo repeats.
public actor HeardRepeatsService {
  private let dataStore: any HeardRepeatPersisting
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "HeardRepeatsService")

  /// Device ID for the current session
  private var radioID: UUID?

  /// Multicast broadcaster for heard-repeat events.
  private nonisolated let eventBroadcaster = EventBroadcaster<HeardRepeatEvent>()

  init(dataStore: any HeardRepeatPersisting) {
    self.dataStore = dataStore
  }

  /// Returns a fresh stream of heard-repeat events. Registration is
  /// synchronous, so events yielded after this call are never dropped.
  /// Consumers must re-subscribe per connection because the owning
  /// `ServiceContainer` is rebuilt on every connection.
  public nonisolated func events() -> AsyncStream<HeardRepeatEvent> {
    eventBroadcaster.subscribe()
  }

  /// Ends every `events()` subscriber's for-await loop. Called by
  /// `ServiceContainer.tearDown()` so consumer tasks release the service
  /// references they hold.
  nonisolated func finishEvents() {
    eventBroadcaster.finish()
  }

  /// Configure the service with the connected radio.
  /// Must be called once before processing any RX log entries.
  public func configure(radioID: UUID) {
    self.radioID = radioID
    logger.info("Configured with radioID: \(radioID)")
  }

  /// Checks if a repeat has already been recorded for this RX log entry.
  private func isDuplicateRepeat(_ entryID: UUID) async -> Bool {
    do {
      return try await dataStore.messageRepeatExists(rxLogEntryID: entryID)
    } catch {
      logger.error("Failed to check for existing repeat: \(error.localizedDescription)")
      return true // Assume duplicate on error to prevent potential duplicates
    }
  }

  /// Process an RX log entry to check if it's a repeat of a sent message.
  ///
  /// Called by RxLogService for each new entry. Only processes successfully
  /// decrypted channel messages, matching the echo to a sent message by exact
  /// channel, sender timestamp, and text.
  ///
  /// - Parameter entry: The RX log entry to process
  /// - Returns: The updated heardRepeats count if a match was found, nil otherwise
  @discardableResult
  public func processForRepeats(_ entry: RxLogEntryDTO) async -> Int? {
    // Only process successfully decrypted channel messages
    guard entry.payloadType == .groupText else { return nil }
    guard entry.decryptStatus == .success else { return nil }
    guard let decodedText = entry.decodedText else { return nil }
    guard let channelIndex = entry.channelIndex else { return nil }
    guard let senderTimestamp = entry.senderTimestamp else { return nil }
    guard let radioID else { return nil }

    // Body after the first colon is the stored outgoing text. Sender name is a
    // join key only for incoming extras (`DeduplicationKey`), not sent echoes.
    guard let (senderName, messageText) = ChannelMessageFormat.parse(decodedText) else {
      logger.info("Failed to parse channel message text: \(decodedText.prefix(50))")
      return nil
    }

    // Check for duplicate (already processed this RX entry)
    if await isDuplicateRepeat(entry.id) {
      logger.info("Repeat already recorded for RX entry: \(entry.id)")
      return nil
    }

    do {
      if let message = try await dataStore.findSentChannelMessage(
        radioID: radioID,
        channelIndex: channelIndex,
        timestamp: senderTimestamp,
        text: messageText
      ) {
        return try await recordSentEcho(
          message: message,
          entry: entry
        )
      }

      let key = DeduplicationKey.contentBased(
        contactID: nil,
        channelIndex: channelIndex,
        senderNodeName: senderName,
        timestamp: senderTimestamp,
        content: messageText
      )
      guard let message = try await dataStore.fetchMessage(
        deduplicationKey: key,
        radioID: radioID
      ) else {
        return nil
      }
      return await recordDistinctPathIfNeeded(
        message: message,
        pathNodes: entry.pathNodes,
        pathLength: entry.pathLength,
        snr: entry.snr,
        rssi: entry.rssi,
        receivedAt: entry.receivedAt,
        rxLogEntryID: entry.id
      )
    } catch {
      logger.error("Failed to process repeat: \(error.localizedDescription)")
      return nil
    }
  }

  /// Records every sent-channel echo, including identical hop lists.
  private func recordSentEcho(message: MessageDTO, entry: RxLogEntryDTO) async throws -> Int {
    let repeatDTO = MessageRepeatDTO(
      messageID: message.id,
      receivedAt: entry.receivedAt,
      pathNodes: entry.pathNodes,
      pathLength: entry.pathLength,
      snr: entry.snr,
      rssi: entry.rssi,
      rxLogEntryID: entry.id
    )

    try await dataStore.saveMessageRepeat(repeatDTO)
    let newCount = try await dataStore.incrementMessageHeardRepeats(id: message.id)
    logger.info("Recorded repeat #\(newCount) for message \(message.id)")
    eventBroadcaster.yield(HeardRepeatEvent(messageID: message.id, count: newCount))
    return newCount
  }

  /// Records a distinct extra incoming path. A nil canonical is unknown, not a
  /// 0-hop, so the first match is adopted onto the message instead of stored as an extra.
  @discardableResult
  func recordDistinctPathIfNeeded(
    message: MessageDTO,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    receivedAt: Date,
    rxLogEntryID: UUID?
  ) async -> Int? {
    guard let canonicalPath = message.pathNodes else {
      do {
        _ = try await dataStore.adoptIncomingPathIfUnknown(
          id: message.id,
          pathNodes: pathNodes,
          pathLength: pathLength
        )
      } catch {
        logger.error("Failed to adopt incoming path: \(error.localizedDescription)")
      }
      return nil
    }
    if pathNodes == canonicalPath {
      return nil
    }

    do {
      if let rxLogEntryID, try await dataStore.messageRepeatExists(rxLogEntryID: rxLogEntryID) {
        return nil
      }

      let existing = try await dataStore.fetchMessageRepeats(messageID: message.id)
      if existing.contains(where: { $0.pathNodes == pathNodes }) {
        return nil
      }

      let repeatDTO = MessageRepeatDTO(
        messageID: message.id,
        receivedAt: receivedAt,
        pathNodes: pathNodes,
        pathLength: pathLength,
        snr: snr,
        rssi: rssi,
        rxLogEntryID: rxLogEntryID
      )
      try await dataStore.saveMessageRepeat(repeatDTO)
      let newCount = try await dataStore.incrementMessageHeardRepeats(id: message.id)
      logger.info("Recorded extra path #\(newCount) for message \(message.id)")
      eventBroadcaster.yield(HeardRepeatEvent(messageID: message.id, count: newCount))
      return newCount
    } catch {
      logger.error("Failed to record extra path: \(error.localizedDescription)")
      return nil
    }
  }

  /// Adopts an unknown canonical from the earliest `ChannelRXCorrelation` match,
  /// then records later distinct paths as extras.
  func harvestIncomingPaths(
    for message: MessageDTO,
    decodedCandidates: [RxLogEntryDTO]
  ) async {
    guard !message.isOutgoing, message.channelIndex != nil else { return }
    let matching = ChannelRXCorrelation.matching(
      decodedCandidates,
      deduplicationKey: message.deduplicationKey
    )
    var current = message
    for entry in matching {
      let wasUnknown = current.pathNodes == nil
      _ = await recordDistinctPathIfNeeded(
        message: current,
        pathNodes: entry.pathNodes,
        pathLength: entry.pathLength,
        snr: entry.snr,
        rssi: entry.rssi,
        receivedAt: entry.receivedAt,
        rxLogEntryID: entry.id
      )
      if wasUnknown, let key = current.deduplicationKey,
         let refreshed = try? await dataStore.fetchMessage(
           deduplicationKey: key,
           radioID: current.radioID
         ) {
        current = refreshed
      }
    }
  }

  /// Refresh repeats for a specific message by querying the RX log.
  /// Used when opening the Repeat Details sheet to catch any missed repeats.
  ///
  /// - Parameter messageID: The message to refresh repeats for
  /// - Returns: Array of repeat DTOs sorted by receivedAt
  public func refreshRepeats(for messageID: UUID) async -> [MessageRepeatDTO] {
    // Return existing repeats from database
    logger.info("refreshRepeats called for messageID: \(messageID)")
    do {
      let results = try await dataStore.fetchMessageRepeats(messageID: messageID)
      logger.info("refreshRepeats returning \(results.count) repeats")
      return results
    } catch {
      logger.error("Failed to fetch repeats: \(error.localizedDescription)")
      return []
    }
  }
}
