import Foundation
@testable import MC1
import Testing

@Suite("ChatScrollToMentionPolicy Tests")
struct ChatScrollToMentionPolicyTests {
  @Test
  func `shouldScrollToBottom returns false when mentionTargetID is nil`() {
    let newestID = UUID()
    #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: nil, newestItemID: newestID) == false)
  }

  @Test
  func `shouldScrollToBottom returns false when newestItemID is nil`() {
    let mentionID = UUID()
    #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: mentionID, newestItemID: nil) == false)
  }

  @Test
  func `shouldScrollToBottom returns false when IDs differ`() {
    let mentionID = UUID()
    let newestID = UUID()
    #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: mentionID, newestItemID: newestID) == false)
  }

  @Test
  func `shouldScrollToBottom returns true when IDs match`() {
    let id = UUID()
    #expect(ChatScrollToMentionPolicy.shouldScrollToBottom(mentionTargetID: id, newestItemID: id) == true)
  }

  @Test
  func `nextTarget returns nil when there are no off-screen mentions`() {
    #expect(ChatScrollToMentionPolicy.nextTarget(offscreenMentions: []) == nil)
  }

  @Test
  func `nextTarget picks the newest (last) of an oldest-to-newest list`() {
    let oldest = UUID()
    let middle = UUID()
    let newest = UUID()
    #expect(ChatScrollToMentionPolicy.nextTarget(offscreenMentions: [oldest, middle, newest]) == newest)
  }

  @Test
  func `nextTarget walks upward to the earliest as newer targets are consumed`() {
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
