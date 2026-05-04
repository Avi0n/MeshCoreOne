import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("MessageBubblePredicates")
struct MessageBubblePredicateTests {

    @Test("showHop: gated by both flag AND isFloodRouted", arguments: [
        // (showFlag, routeType, expected)
        (true,  RouteType.flood,    true),    // gate open
        (true,  RouteType.tcFlood,  true),    // tcFlood is treated as flood
        (true,  RouteType.direct,   false),   // direct-routed must suppress hop even with flag on
        (true,  RouteType.tcDirect, false),   // tcDirect is treated as direct
        (false, RouteType.flood,    false),   // flag off
        (false, RouteType.direct,   false),
    ])
    func showHop(showFlag: Bool, routeType: RouteType, expected: Bool) {
        let predicates = MessageBubblePredicates(
            message: makeMessage(routeType: routeType),
            displayState: makeState(showIncomingHopCount: showFlag)
        )
        #expect(predicates.showHop == expected)
    }

    @Test("regionToShow: nil unless flag AND isFloodRouted AND scope present", arguments: [
        // (showFlag, routeType, scope, expected)
        (true,  RouteType.flood,    "United States" as String?, "United States" as String?),  // happy path
        (true,  RouteType.tcFlood,  "United States" as String?, "United States" as String?),  // tcFlood is flood
        (true,  RouteType.flood,    nil,                        nil),                          // no scope to show
        // Direct-routed messages must hide region even when flag is on and scope is populated.
        // This is the regression row for the previously-shipped bug where regionToShow lacked
        // the isFloodRouted gate that its sibling showHop already had.
        (true,  RouteType.direct,   "United States" as String?, nil),
        (true,  RouteType.tcDirect, "United States" as String?, nil),                          // tcDirect is direct
        (false, RouteType.flood,    "United States" as String?, nil),                          // user setting off
        (false, RouteType.direct,   "United States" as String?, nil),
        (true,  RouteType.direct,   nil,                        nil),
    ])
    func regionToShow(showFlag: Bool, routeType: RouteType, scope: String?, expected: String?) {
        let predicates = MessageBubblePredicates(
            message: makeMessage(routeType: routeType, regionScope: scope),
            displayState: makeState(showIncomingRegion: showFlag)
        )
        #expect(predicates.regionToShow == expected)
    }

    @Test("regionToShow: channel messages render region regardless of routeType")
    func regionToShow_channelOverridesDirect() {
        // channelIndex != nil makes isFloodRouted always true (channels are always flood),
        // so the direct routeType is ignored. Verifies the channel-override path through
        // MessageDTO.isFloodRouted that the parameterized matrix doesn't otherwise exercise.
        let predicates = MessageBubblePredicates(
            message: makeMessage(channelIndex: 0, routeType: .direct, regionScope: "United States"),
            displayState: makeState(showIncomingRegion: true)
        )
        #expect(predicates.regionToShow == "United States")
    }

    @Test("regionToShow: legacy radio with no routeType respects pathLength 0xFF as direct")
    func regionToShow_legacyRadioPathLengthFF() {
        // Older radios may not populate routeType. In that case isFloodRouted falls back to
        // pathLength != 0xFF. A 0xFF marker means direct-routed, so region must hide.
        let predicates = MessageBubblePredicates(
            message: makeMessage(pathLength: 0xFF, routeType: nil, regionScope: "United States"),
            displayState: makeState(showIncomingRegion: true)
        )
        #expect(predicates.regionToShow == nil)
    }

    struct HasFooterCase: Sendable, CustomTestStringConvertible {
        let showHopFlag: Bool
        let showRegionFlag: Bool
        let routeType: RouteType
        let regionScope: String?
        let formattedPath: String?
        let expected: Bool

        var testDescription: String {
            "hop=\(showHopFlag) region=\(showRegionFlag) "
                + "route=\(routeType) scope=\(regionScope ?? "nil") "
                + "path=\(formattedPath ?? "nil") -> \(expected)"
        }
    }

    @Test("hasFooter true iff any of hop/path/region contributes", arguments: [
        // All off: no footer
        HasFooterCase(showHopFlag: false, showRegionFlag: false, routeType: .direct,
                      regionScope: nil, formattedPath: nil, expected: false),
        // Hop only contributes
        HasFooterCase(showHopFlag: true,  showRegionFlag: false, routeType: .flood,
                      regionScope: nil, formattedPath: nil, expected: true),
        // Path only contributes (path is direction-blind in hasFooter; gate lives upstream)
        HasFooterCase(showHopFlag: false, showRegionFlag: false, routeType: .direct,
                      regionScope: nil, formattedPath: "A3,7F", expected: true),
        // Region only contributes
        HasFooterCase(showHopFlag: false, showRegionFlag: true,  routeType: .flood,
                      regionScope: "US", formattedPath: nil, expected: true),
        // Region flag on but direct-routed must NOT contribute (regression row for the
        // previously-shipped missing-isFloodRouted-gate bug, expressed in OR composition).
        HasFooterCase(showHopFlag: false, showRegionFlag: true,  routeType: .direct,
                      regionScope: "US", formattedPath: nil, expected: false),
        // Region + path on with hop off: isolates the case where region is the only
        // gate-sensitive contributor. Catches a future regression where regionToShow
        // loses its gate but a co-contributing axis (hop) would otherwise mask it.
        HasFooterCase(showHopFlag: false, showRegionFlag: true,  routeType: .flood,
                      regionScope: "US", formattedPath: "A3,7F", expected: true),
        // All three on
        HasFooterCase(showHopFlag: true,  showRegionFlag: true,  routeType: .flood,
                      regionScope: "US", formattedPath: "A3,7F", expected: true),
    ])
    func hasFooter(testCase: HasFooterCase) {
        let predicates = MessageBubblePredicates(
            message: makeMessage(routeType: testCase.routeType, regionScope: testCase.regionScope),
            displayState: makeState(
                showIncomingHopCount: testCase.showHopFlag,
                showIncomingRegion: testCase.showRegionFlag,
                formattedPath: testCase.formattedPath
            )
        )
        #expect(predicates.hasFooter == testCase.expected)
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
