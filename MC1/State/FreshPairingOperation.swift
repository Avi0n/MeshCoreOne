import Foundation

struct FreshPairingOperation {
  let actionID: UUID
  let source: Source
  var requestID: UUID?
  var phase: Phase

  init(actionID: UUID, source: Source, requestID: UUID?) {
    self.actionID = actionID
    self.source = source
    self.requestID = requestID
    phase = .running(.initial)
  }

  var isConfirmingSystemPairingSetup: Bool {
    source == .systemPairingSetup && phase == .running(.initial)
  }

  var isForegroundRetry: Bool {
    phase == .running(.foregroundRetry)
  }
}
