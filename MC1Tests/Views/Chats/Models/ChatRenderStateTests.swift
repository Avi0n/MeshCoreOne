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

    @Test("itemIndexByID matches items after buildItems")
    func indexByIDMatchesItems() async {
        let viewModel = ChatViewModel()
        let messages = (0..<5).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let state = viewModel.renderState
        #expect(state.items.count == messages.count)
        for (index, item) in state.items.enumerated() {
            #expect(state.itemIndexByID[item.id] == index)
        }
    }

    @Test("Two builds with identical inputs produce equal render states")
    func deterministicBuild() async {
        let ids = (0..<3).map { _ in UUID() }
        let inputs = ids.enumerated().map { makeMessage(id: $1, index: $0) }

        let viewModel1 = ChatViewModel()
        viewModel1.messages = inputs
        viewModel1.buildItems()
        await viewModel1.buildItemsTask?.value

        let viewModel2 = ChatViewModel()
        viewModel2.messages = inputs
        viewModel2.buildItems()
        await viewModel2.buildItemsTask?.value

        #expect(viewModel1.renderState == viewModel2.renderState)
    }

    @Test("ChatViewModel accessors mirror renderState field-for-field")
    func viewModelAccessorParity() async {
        let viewModel = ChatViewModel()
        let messages = (0..<3).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let state = viewModel.renderState
        #expect(viewModel.items.count == state.items.count)
        #expect(viewModel.itemIndexByID == state.itemIndexByID)
        #expect(viewModel.hasMoreMessages == state.hasMoreMessages)
        #expect(viewModel.isLoadingOlder == state.isLoadingOlder)
        #expect(viewModel.totalFetchedCount == state.totalFetchedCount)
    }

    @Test("updatingItem replaces a single row, leaves others untouched")
    func updatingItem_replacesNamedRow() async {
        let viewModel = ChatViewModel()
        let messages = (0..<3).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let target = viewModel.renderState.items[1]
        let replaced = makeFakeMessageItem(id: target.id, senderName: "replaced")
        let updated = viewModel.renderState.updatingItem(id: target.id) { _ in replaced }

        #expect(updated.items[1].envelope.senderName == "replaced")
        #expect(updated.items[0] == viewModel.renderState.items[0])
        #expect(updated.items[2] == viewModel.renderState.items[2])
        #expect(updated.itemIndexByID == viewModel.renderState.itemIndexByID)
    }

    @Test("updatingItem no-ops on missing ID")
    func updatingItem_noOpOnMissingID() async {
        let viewModel = ChatViewModel()
        viewModel.messages = (0..<2).map { makeMessage(index: $0) }
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let before = viewModel.renderState
        let after = before.updatingItem(id: UUID()) { _ in
            makeFakeMessageItem(id: UUID(), senderName: "ignored")
        }
        #expect(before == after)
    }

    @Test("removingItem removes the row and rebuilds the index")
    func removingItem_removesRowAndRebuildsIndex() async {
        let viewModel = ChatViewModel()
        let messages = (0..<3).map { makeMessage(index: $0) }
        viewModel.messages = messages
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let removedID = messages[0].id
        let after = viewModel.renderState.removingItem(id: removedID)

        #expect(after.items.count == 2)
        #expect(after.itemIndexByID[removedID] == nil)
        for (index, item) in after.items.enumerated() {
            #expect(after.itemIndexByID[item.id] == index)
        }
    }

    @Test("removingItem no-ops on missing ID")
    func removingItem_noOpOnMissingID() async {
        let viewModel = ChatViewModel()
        viewModel.messages = (0..<2).map { makeMessage(index: $0) }
        viewModel.buildItems()
        await viewModel.buildItemsTask?.value

        let before = viewModel.renderState
        let after = before.removingItem(id: UUID())
        #expect(before == after)
    }

    @Test("appendingItem appends and updates totalFetchedCount")
    func appendingItem_appendsAndIncrementsCount() {
        let initial = ChatRenderState.empty
        let item = makeFakeMessageItem(id: UUID(), senderName: "sender")
        let after = initial.appendingItem(item)

        #expect(after.items.count == 1)
        #expect(after.items[0].id == item.id)
        #expect(after.itemIndexByID[item.id] == 0)
        #expect(after.totalFetchedCount == initial.totalFetchedCount + 1)
    }
}

@MainActor
private func makeFakeMessageItem(id: UUID, senderName: String) -> MessageItem {
    MessageItem(
        id: id,
        envelope: MessageEnvelope(
            messageID: id,
            isOutgoing: true,
            senderName: senderName,
            senderResolution: NodeNameResolution(displayName: senderName, matchKind: .exact),
            status: .sent,
            date: Date(timeIntervalSince1970: 1_700_000_000),
            hasFailed: false,
            containsSelfMention: false,
            mentionSeen: false
        ),
        content: [],
        footer: MessageFooter(
            showHop: false,
            hopCount: 0,
            formattedPath: nil,
            regionToShow: nil,
            showStatusRow: false,
            status: .sent,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        ),
        grouping: GroupingFlags(
            showTimestamp: false,
            showDirectionGap: false,
            showSenderName: false,
            showNewMessagesDivider: false
        ),
        shouldRequestPreviewFetch: false
    )
}
