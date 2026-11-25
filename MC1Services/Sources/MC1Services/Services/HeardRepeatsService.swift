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

  private static let sentKeySeedLimit = 200
  private static let maxTrackedSentKeys = 500
  private static let incomingKeySeedLimit = 500
  private static let maxTrackedIncomingKeys = 1000

  /// Nil means unseeded (fail-open to DB lookup).
  private var knownSentKeys: Set<SentChannelMessageKey>?
  private var knownSentOrder: [SentChannelMessageKey] = []
  private var knownIncomingDedupKeys: Set<String>?
  private var knownIncomingOrder: [String] = []

  #if DEBUG
    /// Runs before an incoming-path decision reads the current rows.
    var admissionHoldForTesting: (@Sendable () async -> Void)?

    func setAdmissionHoldForTesting(_ hold: (@Sendable () async -> Void)?) {
      admissionHoldForTesting = hold
    }
  #endif

  /// First byte of this radio's public key: the source hash on its own TEXT_MSG packets.
  private var selfKeyHash: UInt8?

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
  public func configure(radioID: UUID, selfPublicKey: Data? = nil) async {
    self.radioID = radioID
    selfKeyHash = selfPublicKey?.first
    knownSentKeys = nil
    knownSentOrder.removeAll(keepingCapacity: true)
    knownIncomingDedupKeys = nil
    knownIncomingOrder.removeAll(keepingCapacity: true)

    if let seededSent = try? await dataStore.fetchRecentSentChannelMessageKeys(
      radioID: radioID,
      limit: Self.sentKeySeedLimit
    ) {
      var set: Set<SentChannelMessageKey> = []
      var order: [SentChannelMessageKey] = []
      for key in seededSent.reversed() {
        Self.insertBounded(key, into: &set, order: &order, limit: Self.maxTrackedSentKeys)
      }
      knownSentKeys = set
      knownSentOrder = order
    }

    if let seededIncoming = try? await dataStore.fetchRecentIncomingChannelDeduplicationKeys(
      radioID: radioID,
      limit: Self.incomingKeySeedLimit
    ) {
      var set: Set<String> = []
      var order: [String] = []
      for key in seededIncoming.reversed() {
        Self.insertBounded(key, into: &set, order: &order, limit: Self.maxTrackedIncomingKeys)
      }
      knownIncomingDedupKeys = set
      knownIncomingOrder = order
    }

    logger.info("Configured with radioID: \(radioID)")
  }

  public func noteSentChannelMessage(channelIndex: UInt8, timestamp: UInt32) {
    guard var set = knownSentKeys else { return }
    Self.insertBounded(
      SentChannelMessageKey(channelIndex: channelIndex, timestamp: timestamp),
      into: &set,
      order: &knownSentOrder,
      limit: Self.maxTrackedSentKeys
    )
    knownSentKeys = set
  }

  public func noteIncomingChannelMessage(deduplicationKey: String?) {
    guard let deduplicationKey, var set = knownIncomingDedupKeys else { return }
    Self.insertBounded(
      deduplicationKey,
      into: &set,
      order: &knownIncomingOrder,
      limit: Self.maxTrackedIncomingKeys
    )
    knownIncomingDedupKeys = set
  }

  private static func insertBounded<T: Hashable>(
    _ item: T,
    into set: inout Set<T>,
    order: inout [T],
    limit: Int
  ) {
    guard set.insert(item).inserted else { return }
    order.append(item)
    if order.count > limit {
      let evicted = order.removeFirst()
      set.remove(evicted)
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
    if entry.payloadType == .textMessage {
      return await processDMEcho(entry)
    }

    // Only process successfully decrypted channel messages
    guard entry.payloadType == .groupText, entry.decryptStatus == .success, let decodedText = entry.decodedText, let channelIndex = entry.channelIndex, let senderTimestamp = entry.senderTimestamp, let radioID else { return nil }

    // Body after the first colon is the stored outgoing text. Sender name is a
    // join key only for incoming extras (`DeduplicationKey`), not sent echoes.
    guard let (senderName, messageText) = ChannelMessageFormat.parse(decodedText) else {
      logger.info("Failed to parse channel message text: \(decodedText.prefix(50))")
      return nil
    }

    do {
      let sentKey = SentChannelMessageKey(channelIndex: channelIndex, timestamp: senderTimestamp)
      if knownSentKeys == nil || knownSentKeys?.contains(sentKey) == true,
         let message = try await dataStore.findSentChannelMessage(
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
      guard knownIncomingDedupKeys == nil || knownIncomingDedupKeys?.contains(key) == true,
            let message = try await dataStore.fetchMessage(
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

  /// Attributes a heard TEXT_MSG packet to one of our own in-flight DMs.
  ///
  /// DM payloads are encrypted for the recipient, so unlike channel echoes they cannot
  /// be matched on text. A packet whose source hash is ours and whose destination hash
  /// matches the contact of a DM still awaiting delivery is a repeater forwarding that DM.
  /// This is a heuristic: a one-byte hash can collide, but only an active send can match.
  private func processDMEcho(_ entry: RxLogEntryDTO) async -> Int? {
    guard let radioID, let selfKeyHash else { return nil }
    guard entry.packetPayload.count >= 2 else { return nil }
    let recipientHash = entry.packetPayload[entry.packetPayload.startIndex]
    let senderHash = entry.packetPayload[entry.packetPayload.startIndex + 1]
    guard senderHash == selfKeyHash else { return nil }
    // `admitSentEcho` skips an RX log entry it already recorded.

    do {
      guard let message = try await dataStore.findActiveOutgoingDM(
        radioID: radioID,
        recipientHash: recipientHash
      ) else { return nil }
      return try await recordSentEcho(message: message, entry: entry)
    } catch {
      logger.error("Failed to process DM echo: \(error.localizedDescription)")
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
  /// An adopted path also takes the route and region of `contextSource`, when given.
  @discardableResult
  func recordDistinctPathIfNeeded(
    message: MessageDTO,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    receivedAt: Date,
    rxLogEntryID: UUID?,
    adoptingContextOf contextSource: RxLogEntryDTO? = nil
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
      if case .adoptedCanonical = admission, let contextSource {
        try await dataStore.applyDatagramCanonicalContext(
          messageID: message.id,
          routeType: contextSource.routeType,
          regionScope: contextSource.regionScope,
          regionScopeMatches: contextSource.regionScopeMatches
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

  /// Attaches stored RX rows with this message's data field, oldest first.
  /// A nil service radio skips an outgoing row.
  func harvestDatagramPaths(
    for message: MessageDTO,
    decodedCandidates: [RxLogEntryDTO]
  ) async {
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
      _ = await recordDatagramObservation(entry, on: message)
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
      let messages = try await dataStore.fetchChannelMessages(
        radioID: entry.radioID,
        channelIndex: channelIndex,
        timestampWindow: MCOImageTransport.datagramMatchWindow(around: earliest.receivedAt)
      )
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
    noteIncomingChannelMessage(deduplicationKey: message.deduplicationKey)
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
    do {
      let messages = try await dataStore.fetchChannelMessages(
        radioID: radioID,
        channelIndex: channelIndex,
        timestampWindow: MCOImageTransport.datagramMatchWindow(around: entry.receivedAt)
      )
      var lastCount: Int?
      for message in messages where message.datagramDataField == blob {
        lastCount = await recordDatagramObservation(entry, on: message) ?? lastCount
      }
      return lastCount
    } catch {
      logger.error("Failed to process datagram repeat: \(error.localizedDescription)")
      return nil
    }
  }

  /// An outgoing row counts the echo. An incoming row records the path, and the path it adopts
  /// brings the RX row's route and region, which a datagram push does not carry.
  private func recordDatagramObservation(_ entry: RxLogEntryDTO, on message: MessageDTO) async -> Int? {
    guard !message.isOutgoing else {
      do {
        return try await recordSentEcho(message: message, entry: entry)
      } catch {
        logger.error("Failed to record datagram echo: \(error.localizedDescription)")
        return nil
      }
    }
    return await recordDistinctPathIfNeeded(
      message: message,
      pathNodes: entry.pathNodes,
      pathLength: entry.pathLength,
      snr: entry.snr,
      rssi: entry.rssi,
      receivedAt: entry.receivedAt,
      rxLogEntryID: entry.id,
      adoptingContextOf: entry
    )
  }
}
