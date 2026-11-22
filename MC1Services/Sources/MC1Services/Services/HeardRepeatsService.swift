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

  #if DEBUG
    /// Runs before an incoming-path decision reads the current rows.
    var admissionHoldForTesting: (@Sendable () async -> Void)?

    func setAdmissionHoldForTesting(_ hold: (@Sendable () async -> Void)?) {
      admissionHoldForTesting = hold
    }
  #endif

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
    if entry.payloadType == .groupData {
      return await processDatagramRepeat(entry)
    }
    // Only process successfully decrypted channel messages
    guard entry.payloadType == .groupText, entry.decryptStatus == .success, let decodedText = entry.decodedText, let channelIndex = entry.channelIndex, let senderTimestamp = entry.senderTimestamp, let radioID else { return nil }

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
  private func recordSentEcho(message: MessageDTO, entry: RxLogEntryDTO) async throws -> Int? {
    guard let newCount = try await dataStore.admitSentEcho(
      messageID: message.id,
      expectedTimestamp: message.timestamp,
      receivedAt: entry.receivedAt,
      pathNodes: entry.pathNodes,
      pathLength: entry.pathLength,
      snr: entry.snr,
      rssi: entry.rssi,
      rxLogEntryID: entry.id
    ) else { return nil }
    logger.info("Recorded repeat #\(newCount) for message \(message.id)")
    eventBroadcaster.yield(HeardRepeatEvent(messageID: message.id, count: newCount))
    return newCount
  }

  /// Records a distinct extra incoming path. The store reads the current canonical:
  /// nil adopts the path, and a stale nil DTO still records a later distinct path.
  @discardableResult
  func recordDistinctPathIfNeeded(
    message: MessageDTO,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    receivedAt: Date,
    rxLogEntryID: UUID?,
    canonicalRoute: RouteType? = nil,
    canonicalRegion: String? = nil,
    canonicalRegionMatches: [String] = [],
    applyCanonicalContext: Bool = false
  ) async -> Int? {
    #if DEBUG
      if let hold = admissionHoldForTesting {
        await hold()
      }
    #endif
    do {
      let admission = try await dataStore.admitIncomingPath(
        messageID: message.id,
        pathNodes: pathNodes,
        pathLength: pathLength,
        snr: snr,
        rssi: rssi,
        receivedAt: receivedAt,
        rxLogEntryID: rxLogEntryID
      )
      if case .adoptedCanonical = admission, applyCanonicalContext {
        try await dataStore.applyDatagramCanonicalContext(
          messageID: message.id,
          routeType: canonicalRoute,
          regionScope: canonicalRegion,
          regionScopeMatches: canonicalRegionMatches
        )
      }
      guard case let .recordedExtra(count) = admission else { return nil }
      logger.info("Recorded extra path #\(count) for message \(message.id)")
      eventBroadcaster.yield(HeardRepeatEvent(messageID: message.id, count: count))
      return count
    } catch {
      logger.error("Failed to record extra path: \(error.localizedDescription)")
      return nil
    }
  }

  /// Outgoing rows count echoes. Incoming rows adopt the earliest path, then extras.
  /// A nil service radio skips an outgoing row.
  func harvestDatagramPaths(
    for message: MessageDTO,
    decodedCandidates: [RxLogEntryDTO]
  ) async {
    guard message.isDatagramPathMCOImage else { return }
    if message.isOutgoing, message.radioID != radioID { return }
    guard let blob = message.datagramDataField,
          let channelIndex = message.channelIndex else { return }
    let matching = ChannelRXCorrelation.matchingDatagrams(
      decodedCandidates,
      blob: blob,
      radioID: message.radioID,
      channelIndex: channelIndex
    )
    for entry in matching {
      if message.isOutgoing {
        _ = try? await recordSentEcho(message: message, entry: entry)
      } else {
        _ = await recordDistinctPathIfNeeded(
          message: message,
          pathNodes: entry.pathNodes,
          pathLength: entry.pathLength,
          snr: entry.snr,
          rssi: entry.rssi,
          receivedAt: entry.receivedAt,
          rxLogEntryID: entry.id,
          canonicalRoute: entry.routeType,
          canonicalRegion: entry.regionScope,
          canonicalRegionMatches: entry.regionScopeMatches,
          applyCanonicalContext: true
        )
      }
    }
  }

  /// Copies a later region from the earliest matching datagram RX row onto each path MCOimg.
  func copyDatagramRegionIfCanonical(
    entry: RxLogEntryDTO,
    regionScope: String?,
    regionScopeMatches: [String],
    channelEntries: [RxLogEntryDTO]
  ) async {
    guard entry.datagramDataType == MCOImageTransport.applicationDataType,
          let blob = entry.datagramData,
          let channelIndex = entry.channelIndex else { return }
    let sameField = channelEntries.filter {
      $0.channelIndex == channelIndex &&
        $0.datagramDataType == entry.datagramDataType &&
        $0.datagramData == blob
    }
    guard let earliest = sameField.min(by: { $0.receivedAt < $1.receivedAt }),
          earliest.id == entry.id else { return }
    do {
      let messages = try await dataStore.fetchChannelMessages(radioID: entry.radioID, channelIndex: channelIndex)
      for message in messages where message.datagramDataField == blob {
        try await dataStore.updateMessageRegion(
          id: message.id,
          regionScope: regionScope,
          regionScopeMatches: regionScopeMatches
        )
      }
    } catch {
      logger.error("Failed to copy datagram region: \(error.localizedDescription)")
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
    for entry in matching {
      _ = await recordDistinctPathIfNeeded(
        message: message,
        pathNodes: entry.pathNodes,
        pathLength: entry.pathLength,
        snr: entry.snr,
        rssi: entry.rssi,
        receivedAt: entry.receivedAt,
        rxLogEntryID: entry.id
      )
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

  private func processDatagramRepeat(_ entry: RxLogEntryDTO) async -> Int? {
    guard entry.datagramDataType == MCOImageTransport.applicationDataType,
          let blob = entry.datagramData,
          let channelIndex = entry.channelIndex,
          let radioID,
          entry.radioID == radioID else { return nil }
    if await isDuplicateRepeat(entry.id) { return nil }
    do {
      let messages = try await dataStore.fetchChannelMessages(radioID: radioID, channelIndex: channelIndex)
      let matches = messages.filter { $0.datagramDataField == blob }
      var lastCount: Int?
      for message in matches where message.isOutgoing {
        if let count = try await recordSentEcho(message: message, entry: entry) {
          lastCount = count
        }
      }
      for message in matches where !message.isOutgoing {
        if let count = await recordDistinctPathIfNeeded(
          message: message,
          pathNodes: entry.pathNodes,
          pathLength: entry.pathLength,
          snr: entry.snr,
          rssi: entry.rssi,
          receivedAt: entry.receivedAt,
          rxLogEntryID: entry.id,
          canonicalRoute: entry.routeType,
          canonicalRegion: entry.regionScope,
          canonicalRegionMatches: entry.regionScopeMatches,
          applyCanonicalContext: true
        ) {
          lastCount = count
        }
      }
      return lastCount
    } catch {
      logger.error("Failed to process datagram repeat: \(error.localizedDescription)")
      return nil
    }
  }
}
