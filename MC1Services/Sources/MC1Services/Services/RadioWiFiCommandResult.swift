import Foundation

struct RadioWiFiCommandResult: Sendable {
  let contextID: UUID
  let reply: String?
  let revision: UInt64?
  let outcome: RadioWiFiState.MutationOutcome
  let error: (any Error)?
}
