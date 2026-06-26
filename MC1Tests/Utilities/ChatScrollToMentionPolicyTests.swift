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

    @Test("nextTarget returns nil when there are no off-screen mentions")
    func nextTargetEmptyReturnsNil() {
        #expect(ChatScrollToMentionPolicy.nextTarget(offscreenMentions: []) == nil)
    }

    @Test("nextTarget picks the newest (last) of an oldest-to-newest list")
    func nextTargetPicksNewest() {
        let oldest = UUID()
        let middle = UUID()
        let newest = UUID()
        #expect(ChatScrollToMentionPolicy.nextTarget(offscreenMentions: [oldest, middle, newest]) == newest)
    }

    @Test("nextTarget walks upward to the earliest as newer targets are consumed")
    func nextTargetWalksUpward() {
        let oldest = UUID()
        let middle = UUID()
        let newest = UUID()
        var remaining = [oldest, middle, newest]

        var visited: [UUID] = []
        while let target = ChatScrollToMentionPolicy.nextTarget(offscreenMentions: remaining) {
            visited.append(target)
            remaining.removeAll { $0 == target }
        }

        #expect(visited == [newest, middle, oldest])
    }
}
