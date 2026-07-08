import Foundation

/// Context for presenting the send-DM sheet from a channel message sender.
struct SendDMContext: Identifiable {
  let id = UUID()
  let senderName: String
  let radioID: UUID
  let unverifiedNickname: String?
}
