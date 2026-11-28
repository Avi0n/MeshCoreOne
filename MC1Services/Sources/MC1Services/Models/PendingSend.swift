import Foundation
import SwiftData

/// Durable send work partitioned by Radio ID and ordered by a per-radio sequence.
/// ACK metadata retains delivery tracking after command issuance ends.
@Model
public final class PendingSend {
  #Index<PendingSend>([\.radioID, \.sequence], [\.messageID])

  @Attribute(.unique)
  public var id: UUID

  public var radioID: UUID
  public var messageID: UUID

  public var kindRawValue: Int

  public var contactID: UUID?
  public var channelIndex: UInt8?
  public var isResend: Bool
  public var isSingleAttempt: Bool = false

  public var messageText: String
  public var messageTimestamp: UInt32
  public var localNodeName: String?

  public var sequence: Int
  public var enqueuedAt: Date

  /// Persisted before issuing a command so recovery knows whether to reuse the wire timestamp.
  /// `nil` marks legacy rows with ambiguous send history; warm-up purges them.
  /// `0` permits a fresh timestamp because no attempt has started. A positive count
  /// means transmission may have started, so replay preserves the timestamp.
  public var attemptCount: Int?

  /// Next `CMD_SEND_TXT_MSG` attempt byte for this row's current wire timestamp.
  /// Bytes already handed to the radio stay below this value. `0` is a new timestamp.
  public var nextWireAttempt: UInt8 = 0

  /// Non-nil ACK metadata changes this row from queued work to delivery tracking without retransmission.
  public var ackCodes: [Data]?
  public var ackSentAt: Date?
  public var ackDeadline: Date?

  /// Backoff rounds this send may still run after its normal retry budget is spent. `0` never backs off.
  public var maxBackoffRounds: Int = 0
  /// Backoff rounds started so far.
  public var backoffRound: Int = 0
  /// While set and in the future, the row is queued work that waits for this time before sending.
  public var notBefore: Date?

  public init(
    id: UUID,
    radioID: UUID,
    messageID: UUID,
    kindRawValue: Int,
    contactID: UUID?,
    channelIndex: UInt8?,
    isResend: Bool,
    messageText: String,
    messageTimestamp: UInt32,
    localNodeName: String?,
    sequence: Int,
    enqueuedAt: Date,
    attemptCount: Int? = nil,
    nextWireAttempt: UInt8 = 0,
    ackCodes: [Data]? = nil,
    ackSentAt: Date? = nil,
    ackDeadline: Date? = nil,
    isSingleAttempt: Bool = false,
    maxBackoffRounds: Int = 0,
    backoffRound: Int = 0,
    notBefore: Date? = nil
  ) {
    self.maxBackoffRounds = maxBackoffRounds
    self.backoffRound = backoffRound
    self.notBefore = notBefore
    self.id = id
    self.radioID = radioID
    self.messageID = messageID
    self.kindRawValue = kindRawValue
    self.contactID = contactID
    self.channelIndex = channelIndex
    self.isResend = isResend
    self.isSingleAttempt = isSingleAttempt
    self.messageText = messageText
    self.messageTimestamp = messageTimestamp
    self.localNodeName = localNodeName
    self.sequence = sequence
    self.enqueuedAt = enqueuedAt
    self.attemptCount = attemptCount
    self.nextWireAttempt = nextWireAttempt
    self.ackCodes = ackCodes
    self.ackSentAt = ackSentAt
    self.ackDeadline = ackDeadline
  }

  var kind: PendingSendKind {
    PendingSendKind(rawValue: kindRawValue) ?? .dm
  }
}

/// Raw values persist in `kindRawValue` and must survive case renames.
enum PendingSendKind: Int {
  case dm = 0
  case channel = 1
}

/// Excluded from `AppBackupEnvelope` because restoring pending work could replay a delivered send.
public struct PendingSendDTO: Sendable, Hashable, Identifiable {
  public let id: UUID
  public let radioID: UUID
  public let messageID: UUID
  let kind: PendingSendKind
  public let contactID: UUID?
  public let channelIndex: UInt8?
  public let isResend: Bool
  public let isSingleAttempt: Bool
  public let messageText: String
  public let messageTimestamp: UInt32
  public let localNodeName: String?
  public let sequence: Int
  public let enqueuedAt: Date
  /// Zero distinguishes new sends from legacy rows migrated to nil.
  public let attemptCount: Int?
  /// Bytes below this value were already handed to the radio for this timestamp.
  public let nextWireAttempt: UInt8
  public let ackCodes: [Data]?
  public let ackSentAt: Date?
  public let ackDeadline: Date?
  public let maxBackoffRounds: Int
  public let backoffRound: Int
  public let notBefore: Date?

  init(
    id: UUID,
    radioID: UUID,
    messageID: UUID,
    kind: PendingSendKind,
    contactID: UUID?,
    channelIndex: UInt8?,
    isResend: Bool,
    messageText: String,
    messageTimestamp: UInt32,
    localNodeName: String?,
    sequence: Int,
    enqueuedAt: Date,
    attemptCount: Int? = 0,
    nextWireAttempt: UInt8 = 0,
    ackCodes: [Data]? = nil,
    ackSentAt: Date? = nil,
    ackDeadline: Date? = nil,
    isSingleAttempt: Bool = false,
    maxBackoffRounds: Int = 0,
    backoffRound: Int = 0,
    notBefore: Date? = nil
  ) {
    self.maxBackoffRounds = maxBackoffRounds
    self.backoffRound = backoffRound
    self.notBefore = notBefore
    self.id = id
    self.radioID = radioID
    self.messageID = messageID
    self.kind = kind
    self.contactID = contactID
    self.channelIndex = channelIndex
    self.isResend = isResend
    self.isSingleAttempt = isSingleAttempt
    self.messageText = messageText
    self.messageTimestamp = messageTimestamp
    self.localNodeName = localNodeName
    self.sequence = sequence
    self.enqueuedAt = enqueuedAt
    self.attemptCount = attemptCount
    self.nextWireAttempt = nextWireAttempt
    self.ackCodes = ackCodes
    self.ackSentAt = ackSentAt
    self.ackDeadline = ackDeadline
  }
}

public extension PendingSend {
  convenience init(dto: PendingSendDTO) {
    self.init(
      id: dto.id,
      radioID: dto.radioID,
      messageID: dto.messageID,
      kindRawValue: dto.kind.rawValue,
      contactID: dto.contactID,
      channelIndex: dto.channelIndex,
      isResend: dto.isResend,
      messageText: dto.messageText,
      messageTimestamp: dto.messageTimestamp,
      localNodeName: dto.localNodeName,
      sequence: dto.sequence,
      enqueuedAt: dto.enqueuedAt,
      attemptCount: dto.attemptCount,
      nextWireAttempt: dto.nextWireAttempt,
      ackCodes: dto.ackCodes,
      ackSentAt: dto.ackSentAt,
      ackDeadline: dto.ackDeadline,
      isSingleAttempt: dto.isSingleAttempt,
      maxBackoffRounds: dto.maxBackoffRounds,
      backoffRound: dto.backoffRound,
      notBefore: dto.notBefore
    )
  }

  func toDTO() -> PendingSendDTO {
    PendingSendDTO(
      id: id,
      radioID: radioID,
      messageID: messageID,
      kind: kind,
      contactID: contactID,
      channelIndex: channelIndex,
      isResend: isResend,
      messageText: messageText,
      messageTimestamp: messageTimestamp,
      localNodeName: localNodeName,
      sequence: sequence,
      enqueuedAt: enqueuedAt,
      attemptCount: attemptCount,
      nextWireAttempt: nextWireAttempt,
      ackCodes: ackCodes,
      ackSentAt: ackSentAt,
      ackDeadline: ackDeadline,
      isSingleAttempt: isSingleAttempt,
      maxBackoffRounds: maxBackoffRounds,
      backoffRound: backoffRound,
      notBefore: notBefore
    )
  }
}
