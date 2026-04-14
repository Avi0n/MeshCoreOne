import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("MessageActionAvailability")
struct MessageActionAvailabilityTests {

    // MARK: - canViewPath

    @Test("flood-routed with non-empty pathNodes returns true")
    func canViewPath_floodRoutedWithNodes() {
        let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), routeType: .flood)
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == true)
    }

    @Test("flood-routed with empty pathNodes returns false")
    func canViewPath_floodRoutedEmptyNodes() {
        let message = makeMessage(pathNodes: Data(), routeType: .flood)
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == false)
    }

    @Test("flood-routed with nil pathNodes returns false")
    func canViewPath_floodRoutedNilNodes() {
        let message = makeMessage(pathNodes: nil, routeType: .flood)
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == false)
    }

    @Test("direct-routed returns false")
    func canViewPath_directRouted() {
        let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), routeType: .direct)
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == false)
    }

    @Test("outgoing message returns false")
    func canViewPath_outgoingMessage() {
        let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), direction: .outgoing, routeType: .flood)
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == false)
    }

    @Test("channel message with routeType .direct is still flood-routed (channelIndex overrides)")
    func canViewPath_channelOverridesDirectRouteType() {
        let message = makeMessage(
            channelIndex: 0,
            pathNodes: Data([0xA3, 0x7F]),
            routeType: .direct
        )
        let availability = MessageActionAvailability(message: message)
        #expect(availability.canViewPath == true)
    }

    // MARK: - isFloodRouted

    @Test(".flood routeType is flood-routed")
    func isFloodRouted_flood() {
        let message = makeMessage(routeType: .flood)
        #expect(message.isFloodRouted == true)
    }

    @Test(".tcFlood routeType is flood-routed")
    func isFloodRouted_tcFlood() {
        let message = makeMessage(routeType: .tcFlood)
        #expect(message.isFloodRouted == true)
    }

    @Test(".direct routeType is not flood-routed")
    func isFloodRouted_direct() {
        let message = makeMessage(routeType: .direct)
        #expect(message.isFloodRouted == false)
    }

    @Test(".tcDirect routeType is not flood-routed")
    func isFloodRouted_tcDirect() {
        let message = makeMessage(routeType: .tcDirect)
        #expect(message.isFloodRouted == false)
    }

    @Test("unknown routeType with channelIndex is flood-routed")
    func isFloodRouted_unknownRouteTypeWithChannel() {
        let message = makeMessage(channelIndex: 0, routeType: nil)
        #expect(message.isFloodRouted == true)
    }

    @Test("unknown routeType with pathLength 0xFF is direct-routed")
    func isDirectRouted_unknownRouteTypePathLength0xFF() {
        let message = makeMessage(pathLength: 0xFF, routeType: nil)
        #expect(message.isDirectRouted == true)
    }

    @Test("unknown routeType with non-0xFF pathLength is flood-routed")
    func isFloodRouted_unknownRouteTypeNonMaxPathLength() {
        let message = makeMessage(pathLength: 0x02, routeType: nil)
        #expect(message.isFloodRouted == true)
    }

    // MARK: - isDirectRouted

    @Test(".direct routeType is direct-routed")
    func isDirectRouted_direct() {
        let message = makeMessage(routeType: .direct)
        #expect(message.isDirectRouted == true)
    }

    @Test(".tcDirect routeType is direct-routed")
    func isDirectRouted_tcDirect() {
        let message = makeMessage(routeType: .tcDirect)
        #expect(message.isDirectRouted == true)
    }

    // MARK: - Helper

    private func makeMessage(
        contactID: UUID? = nil,
        channelIndex: UInt8? = nil,
        pathLength: UInt8 = 0x02,
        pathNodes: Data? = Data([0xA3, 0x7F]),
        direction: MessageDirection = .incoming,
        routeType: RouteType? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: contactID,
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
            routeType: routeType
        )
    }
}
