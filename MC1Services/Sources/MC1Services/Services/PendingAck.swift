import Foundation

/// Tracks a DM's ACK codes across retry attempts.
/// `RoomServerService` waits for Room post ACKs separately.
struct PendingAck {
  struct Owner: Equatable {
    enum WriteKind {
      case insert, convert
    }

    enum Phase: Equatable {
      case pending(WriteKind)
      case saving(WriteKind)
      case saved
    }

    let id: UUID
    let radioID: UUID
    let deadline: Date
    fileprivate(set) var phase: Phase
  }

  struct Evidence {
    let code: UInt32
    let tripTime: UInt32?
  }

  enum Resolution {
    case awaitingAck
    case matched(Evidence)
    case savingDelivery(Evidence)
    case savingFailure(Evidence?)
    case resolved
  }

  let messageID: UUID
  let contactID: UUID
  var ackCodes: Set<Data>
  var sentAt: Date
  /// Per-attempt ACK wait derived from the firmware's `est_timeout` hint.
  /// Expiry uses it as a floor so a slow round trip is not failed in flight.
  var timeout: TimeInterval
  private(set) var resolution: Resolution = .awaitingAck
  private(set) var owner: Owner?
  /// Admission lease for this ACK. A restored live ACK uses its persisted owner ID.
  var sendLeaseID: UUID?

  var isAwaitingAck: Bool {
    if case .awaitingAck = resolution { return true }
    return false
  }

  var isResolved: Bool {
    if case .resolved = resolution { return true }
    return false
  }

  private var matchedEvidence: Evidence? {
    switch resolution {
    case let .matched(evidence), let .savingDelivery(evidence): evidence
    case let .savingFailure(evidence): evidence
    case .awaitingAck, .resolved: nil
    }
  }

  var matchedAckCode: UInt32? {
    matchedEvidence?.code
  }

  var matchedTripTime: UInt32? {
    matchedEvidence?.tripTime
  }

  mutating func recordAcknowledgement(code: UInt32, tripTime: UInt32?) {
    let evidence = Evidence(code: code, tripTime: tripTime)
    switch resolution {
    case .awaitingAck:
      resolution = .matched(evidence)
    case .savingFailure(nil):
      resolution = .savingFailure(evidence)
    case .matched, .savingDelivery, .savingFailure, .resolved:
      break
    }
  }

  mutating func prepareOwner(radioID: UUID, pendingSendID: UUID?, giveUpWindow: TimeInterval) {
    guard owner == nil, !isResolved else { return }
    owner = Owner(
      id: pendingSendID ?? UUID(), radioID: radioID,
      deadline: expiresAt(giveUpWindow: giveUpWindow),
      phase: .pending(pendingSendID == nil ? .insert : .convert)
    )
  }

  mutating func resetOwner() {
    owner = nil
  }

  mutating func beginOwnerSave() -> Owner? {
    guard !isResolved, case let .pending(kind) = owner?.phase else { return nil }
    owner?.phase = .saving(kind)
    return owner
  }

  mutating func finishOwnerSave(succeeded: Bool) {
    guard case let .saving(kind) = owner?.phase else { return }
    owner?.phase = succeeded ? .saved : .pending(kind)
  }

  mutating func beginResolution() -> MessageStatus? {
    if let owner, owner.phase != .saved { return nil }
    switch resolution {
    case .awaitingAck:
      resolution = .savingFailure(nil)
      return .failed
    case let .matched(evidence):
      resolution = .savingDelivery(evidence)
      return .delivered
    case .savingDelivery, .savingFailure, .resolved:
      return nil
    }
  }

  mutating func resolutionSaveFailed() {
    switch resolution {
    case let .savingDelivery(evidence):
      resolution = .matched(evidence)
    case let .savingFailure(evidence):
      resolution = evidence.map(Resolution.matched) ?? .awaitingAck
    case .awaitingAck, .matched, .resolved:
      break
    }
  }

  mutating func resolutionSaveSucceeded() {
    resolution = .resolved
  }

  func expiresAt(giveUpWindow: TimeInterval) -> Date {
    owner?.deadline ?? sentAt.addingTimeInterval(max(giveUpWindow, timeout))
  }
}
