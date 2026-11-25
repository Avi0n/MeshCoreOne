import Foundation
import MeshCore

public struct SentChannelMessageKey: Hashable, Sendable {
  public let channelIndex: UInt8
  public let timestamp: UInt32

  public init(channelIndex: UInt8, timestamp: UInt32) {
    self.channelIndex = channelIndex
    self.timestamp = timestamp
  }
}

/// Store operations for correlating RX observations with a known message.
public protocol HeardRepeatPersisting: Actor {
  /// Recent sent channel keys for echo filtering, or `nil` to fail open to store lookups.
  func fetchRecentSentChannelMessageKeys(radioID: UUID, limit: Int) async throws -> [SentChannelMessageKey]?

  /// Recent incoming channel deduplication keys for arrival filtering, or `nil` to fail open.
  func fetchRecentIncomingChannelDeduplicationKeys(radioID: UUID, limit: Int) async throws -> [String]?

  /// Find a sent channel message by exact channel, sender timestamp, and text on the sending radio
  func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String) async throws -> MessageDTO?

  /// Same radio scope as `MessagePersisting.fetchMessage(deduplicationKey:radioID:)`.
  /// Incoming extras join on the content-based key, not `Message.timestamp`.
  func fetchMessage(deduplicationKey: String, radioID: UUID) async throws -> MessageDTO?

  /// Every message on one radio and channel. Datagram path matching filters this set in memory.
  /// Channel rows whose `timestamp` falls in `timestampWindow`, for datagram identity matching.
  func fetchChannelMessages(radioID: UUID, channelIndex: UInt8, timestampWindow: ClosedRange<UInt32>) async throws -> [MessageDTO]

  /// Save a message repeat entry
  func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws

  /// Fetch all repeats for a message
  func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO]

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

  /// The newest outgoing DM still awaiting delivery (pending, sending, sent or
  /// retrying) to a contact whose public key starts with `recipientHash`.
  /// Used to attribute a heard TEXT_MSG echo to the DM that produced it.
  func findActiveOutgoingDM(radioID: UUID, recipientHash: UInt8) async throws -> MessageDTO?
}

extension HeardRepeatPersisting {
  /// Stores without DM echo support (test doubles) match nothing.
  func findActiveOutgoingDM(radioID: UUID, recipientHash: UInt8) async throws -> MessageDTO? { nil }
}

public extension HeardRepeatPersisting {
  func fetchChannelMessages(radioID _: UUID, channelIndex _: UInt8, timestampWindow _: ClosedRange<UInt32>) async throws -> [MessageDTO] {
    []
  }

  func applyDatagramCanonicalContext(
    messageID _: UUID,
    routeType _: RouteType?,
    regionScope _: String?,
    regionScopeMatches _: [String]
  ) async throws {}

  func updateMessageRegion(
    id _: UUID,
    regionScope _: String?,
    regionScopeMatches _: [String]
  ) async throws {}

  func fetchRecentSentChannelMessageKeys(radioID _: UUID, limit _: Int) async throws -> [SentChannelMessageKey]? {
    nil
  }

  func fetchRecentIncomingChannelDeduplicationKeys(radioID _: UUID, limit _: Int) async throws -> [String]? {
    nil
  }
}
