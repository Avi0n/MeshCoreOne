import Foundation

extension MessageService {
  func adoptPlainTextLease(_ provided: PlainTextSendGate.Lease?) async throws -> PlainTextSendGate.Lease? {
    if let provided { return provided }
    return try await plainTextSendGate?.acquire()
  }

  func stopPlainTextLease(_ lease: PlainTextSendGate.Lease?) async {
    guard let lease else { return }
    await plainTextSendGate?.stopIssuing(lease)
  }

  func notePlainTextLease(_ lease: PlainTextSendGate.Lease?) async {
    guard let lease else { return }
    await plainTextSendGate?.noteAckObligation(lease)
  }

  func settlePlainTextLease(_ leaseID: UUID?) async {
    guard let leaseID else { return }
    await plainTextSendGate?.settleAck(PlainTextSendGate.Lease(id: leaseID))
  }

  func discardTrackedAck(messageID: UUID) async {
    let leaseID = pendingAcks.removeValue(forKey: messageID)?.sendLeaseID
    await settlePlainTextLease(leaseID)
  }
}
