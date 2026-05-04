import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("MessageBubblePredicates")
struct MessageBubblePredicateTests {

    @Test("showHop true when flag set AND message is flood-routed")
    func showHop_floodRoutedWithFlag() {
        let predicates = MessageBubblePredicates(
            message: makeMessage(routeType: .flood),
            displayState: makeState(showIncomingHopCount: true)
        )
        #expect(predicates.showHop == true)
    }

    // MARK: - Helpers

    private func makeMessage(
        channelIndex: UInt8? = nil,
        pathLength: UInt8 = 0x02,
        pathNodes: Data? = Data([0xA3, 0x7F]),
        direction: MessageDirection = .incoming,
        routeType: RouteType? = nil,
        regionScope: String? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: nil,
            channelIndex: channelIndex,
            text: "Test",
            timestamp: 0,
            createdAt: Date(),
            direction: direction,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: pathLength,
            snr: nil,
            pathNodes: pathNodes,
            senderKeyPrefix: nil,
            senderNodeName: channelIndex != nil ? "RemoteNode" : nil,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0,
            routeType: routeType,
            regionScope: regionScope
        )
    }

    private func makeState(
        showIncomingHopCount: Bool = false,
        showIncomingRegion: Bool = false,
        formattedPath: String? = nil
    ) -> MessageDisplayState {
        var state = MessageDisplayState()
        state.showIncomingHopCount = showIncomingHopCount
        state.showIncomingRegion = showIncomingRegion
        state.formattedPath = formattedPath
        return state
    }
}
