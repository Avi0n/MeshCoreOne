import Testing
import Foundation
@testable import MC1
@testable import MC1Services

private func makeMessage(id: UUID = UUID(), index: Int) -> MessageDTO {
    let timestamp = UInt32(1_700_000_000 + index * 60)
    return MessageDTO(
        id: id,
        radioID: UUID(),
        contactID: UUID(),
        channelIndex: nil,
        text: "message \(index)",
        timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        direction: .outgoing,
        status: .sent,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        senderKeyPrefix: nil,
        senderNodeName: nil,
        isRead: true,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )
}

@Suite("ChatRenderState")
@MainActor
struct ChatRenderStateTests {

    @Test("empty equals empty")
    func emptyEquality() {
        #expect(ChatRenderState.empty == ChatRenderState.empty)
    }

    @Test("with(...) replaces only specified fields")
    func withReplacesOnlySpecifiedFields() {
        let updated = ChatRenderState.empty.with(hasMoreMessages: false, totalFetchedCount: 42)

        #expect(updated.hasMoreMessages == false)
        #expect(updated.totalFetchedCount == 42)
        #expect(updated.isLoadingOlder == ChatRenderState.empty.isLoadingOlder)
        #expect(updated.items == ChatRenderState.empty.items)
        #expect(updated.itemIndexByID == ChatRenderState.empty.itemIndexByID)
    }

    @Test("itemIndexByID matches items after buildDisplayItems")
    func indexByIDMatchesItems() {
        let viewModel = ChatViewModel()
        let messages = (0..<5).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildDisplayItems()

        let state = viewModel.renderState
        #expect(state.items.count == messages.count)
        for (index, item) in state.items.enumerated() {
            #expect(state.itemIndexByID[item.messageID] == index)
        }
    }

    @Test("Two builds with identical inputs produce equal render states")
    func deterministicBuild() {
        let ids = (0..<3).map { _ in UUID() }
        let inputs = ids.enumerated().map { makeMessage(id: $1, index: $0) }

        let viewModel1 = ChatViewModel()
        viewModel1.messages = inputs
        viewModel1.buildDisplayItems()

        let viewModel2 = ChatViewModel()
        viewModel2.messages = inputs
        viewModel2.buildDisplayItems()

        #expect(viewModel1.renderState == viewModel2.renderState)
    }

    @Test("ChatViewModel accessors mirror renderState field-for-field")
    func viewModelAccessorParity() {
        let viewModel = ChatViewModel()
        let messages = (0..<3).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildDisplayItems()

        let state = viewModel.renderState
        #expect(viewModel.displayItems == state.items)
        #expect(viewModel.displayItemIndexByID == state.itemIndexByID)
        #expect(viewModel.hasMoreMessages == state.hasMoreMessages)
        #expect(viewModel.isLoadingOlder == state.isLoadingOlder)
        #expect(viewModel.totalFetchedCount == state.totalFetchedCount)
    }
}
