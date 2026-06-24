import Testing
import Foundation
@testable import MC1

@Suite("ChatScrollToMentionPolicy Tests")
struct ChatScrollToMentionPolicyTests {

    @Test("shouldScrollToBottom returns false when mentionTargetID is nil")
    func mentionTargetIDNilReturnsFalse() {
        let newestID = UUID()
        #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: nil, newestItemID: newestID) == false)
    }

    @Test("shouldScrollToBottom returns false when newestItemID is nil")
    func newestItemIDNilReturnsFalse() {
        let mentionID = UUID()
        #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: mentionID, newestItemID: nil) == false)
    }

    @Test("shouldScrollToBottom returns false when IDs differ")
    func differentIDsReturnFalse() {
        let mentionID = UUID()
        let newestID = UUID()
        #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: mentionID, newestItemID: newestID) == false)
    }

    @Test("shouldScrollToBottom returns true when IDs match")
    func matchingIDsReturnTrue() {
        let id = UUID()
        #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: id, newestItemID: id) == true)
    }
}
