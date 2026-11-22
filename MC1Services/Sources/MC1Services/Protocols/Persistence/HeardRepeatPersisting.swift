import Foundation
import MeshCore

/// Store operations for correlating RX observations with a known message.
public protocol HeardRepeatPersisting: Actor {
  /// Find a sent channel message by exact channel, sender timestamp, and text on the sending radio
  func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String) async throws -> MessageDTO?

  /// Same radio scope as `MessagePersisting.fetchMessage(deduplicationKey:radioID:)`.
  /// Incoming extras join on the content-based key, not `Message.timestamp`.
  func fetchMessage(deduplicationKey: String, radioID: UUID) async throws -> MessageDTO?

  /// Every message on one radio and channel. Datagram path matching filters this set in memory.
  func fetchChannelMessages(radioID: UUID, channelIndex: UInt8) async throws -> [MessageDTO]

  /// Save a message repeat entry
  func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws

  /// Fetch all repeats for a message
  func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO]

  /// Check if a repeat exists for the given RX log entry
  func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool

  /// Increment heard repeats count and return new count
  func incrementMessageHeardRepeats(id: UUID) async throws -> Int

  /// Current canonical, dedup, and insert in one turn.
  /// A nil path adopts the observation; empty path bytes are a 0-hop, and a distinct path is an extra arrival.
  func admitIncomingPath(
    messageID: UUID,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    receivedAt: Date,
    rxLogEntryID: UUID?
  ) async throws -> IncomingPathAdmission

  /// Records every sent echo, including identical paths, while its timestamp matches.
  /// Returns nil for a duplicate RX entry or an echo from a prior send.
  func admitSentEcho(
    messageID: UUID,
    expectedTimestamp: UInt32,
    receivedAt: Date,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    rxLogEntryID: UUID?
  ) async throws -> Int?

  /// Route and region copied from the earliest matching datagram RX row when its path is adopted.
  func applyDatagramCanonicalContext(
    messageID: UUID,
    routeType: RouteType?,
    regionScope: String?,
    regionScopeMatches: [String]
  ) async throws

  /// Region written onto a datagram message from the canonical RX row, including a later rewrite.
  func updateMessageRegion(
    id: UUID,
    regionScope: String?,
    regionScopeMatches: [String]
  ) async throws

  /// Writes path columns only when they are still nil. Fetch by id; `#Predicate` cannot match `Data`.
  /// Leaves snr and heardRepeats unchanged.
  func adoptIncomingPathIfUnknown(
    id: UUID,
    pathNodes: Data,
    pathLength: UInt8
  ) async throws -> Bool

  /// Increment send count and return new count
  func incrementMessageSendCount(id: UUID) async throws -> Int
}

extension HeardRepeatPersisting {
  func fetchChannelMessages(radioID: UUID, channelIndex: UInt8) async throws -> [MessageDTO] {
    []
  }

  func applyDatagramCanonicalContext(
    messageID: UUID,
    routeType: RouteType?,
    regionScope: String?,
    regionScopeMatches: [String]
  ) async throws {}

  func updateMessageRegion(
    id: UUID,
    regionScope: String?,
    regionScopeMatches: [String]
  ) async throws {}
}
