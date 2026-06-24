import Foundation

enum ChatScrollToMentionPolicy {
    static func shouldScrollToBottom(mentionTargetID: UUID?, newestItemID: UUID?) -> Bool {
        guard let mentionTargetID, let newestItemID else { return false }
        return mentionTargetID == newestItemID
    }
}
