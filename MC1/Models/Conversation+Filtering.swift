import Foundation

extension Array where Element == Conversation {
    /// Filters conversations by category and search text
    /// - Parameters:
    ///   - filter: Filter category
    ///   - searchText: Search string to match against display names
    /// - Returns: Filtered array of conversations
    func filtered(by filter: ChatFilter, searchText: String) -> [Conversation] {
        // When searching, ignore the selected filter and search all conversations
        if !searchText.isEmpty {
            return self.filter { conversation in
                conversation.displayName.localizedStandardContains(searchText)
            }
        }

        switch filter {
        case .all:
            return self
        case .unread:
            return self.filter { $0.unreadCount > 0 && !$0.isMuted }
        case .directMessages:
            return self.filter {
                if case .direct = $0 { return true }
                return false
            }
        case .channels:
            return self.filter {
                if case .channel = $0 { return true }
                return false
            }
        case .rooms:
            return self.filter {
                if case .room = $0 { return true }
                return false
            }
        }
    }
}
