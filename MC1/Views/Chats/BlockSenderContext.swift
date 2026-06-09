import Foundation

/// Context for presenting the block-sender sheet in channel conversations.
struct BlockSenderContext: Identifiable {
    let id = UUID()
    let senderName: String
    let radioID: UUID
}
