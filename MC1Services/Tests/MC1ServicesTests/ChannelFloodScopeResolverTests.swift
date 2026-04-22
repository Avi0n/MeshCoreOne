import Foundation
import MeshCore
import Testing
@testable import MC1Services

@Suite("ChannelFloodScopeResolver")
struct ChannelFloodScopeResolverTests {

    @Test(".inherit with device default set pushes .region(default)")
    func inheritWithDefaultPushesRegion() {
        let scope = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: "Germany"
        )
        #expect(scope.scopeKey() == FloodScope.region("Germany").scopeKey())
    }

    @Test(".inherit with no device default pushes .disabled")
    func inheritWithoutDefaultPushesDisabled() {
        let scope = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: nil
        )
        #expect(scope.scopeKey() == FloodScope.disabled.scopeKey())
    }

    @Test(".allRegions always pushes .disabled regardless of default")
    func allRegionsPushesDisabled() {
        let withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: "Germany"
        )
        let withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: nil
        )
        #expect(withDefault.scopeKey() == FloodScope.disabled.scopeKey())
        #expect(withoutDefault.scopeKey() == FloodScope.disabled.scopeKey())
    }

    @Test(".region(name) pushes that region regardless of default")
    func specificRegionPushesThatRegion() {
        let withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .region("France"),
            deviceDefaultFloodScopeName: "Germany"
        )
        let withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .region("France"),
            deviceDefaultFloodScopeName: nil
        )
        #expect(withDefault.scopeKey() == FloodScope.region("France").scopeKey())
        #expect(withoutDefault.scopeKey() == FloodScope.region("France").scopeKey())
    }

    @Test(".inherit with empty-string default treats it as no default")
    func inheritWithEmptyDefaultPushesDisabled() {
        let scope = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: ""
        )
        #expect(scope.scopeKey() == FloodScope.disabled.scopeKey())
    }
}
