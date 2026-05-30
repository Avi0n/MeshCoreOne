import Foundation
import MeshCore
import Testing
@testable import MC1Services

@Suite("ChannelFloodScopeResolver")
struct ChannelFloodScopeResolverTests {

    @Test(".inherit with device default set resolves to .scope(.region(default))")
    func inheritWithDefaultResolvesRegion() {
        let resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: "Germany",
            supportsUnscopedFloodSend: true
        )
        #expect(resolved == .scope(.region("Germany")))
    }

    @Test(".inherit with no device default resolves to .scope(.disabled)")
    func inheritWithoutDefaultResolvesDisabled() {
        let resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: nil,
            supportsUnscopedFloodSend: true
        )
        #expect(resolved == .scope(.disabled))
    }

    @Test(".inherit with empty-string default treats it as no default")
    func inheritWithEmptyDefaultResolvesDisabled() {
        let resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .inherit,
            deviceDefaultFloodScopeName: "",
            supportsUnscopedFloodSend: true
        )
        #expect(resolved == .scope(.disabled))
    }

    @Test(".allRegions on firmware v12+ resolves to .unscoped (true override)")
    func allRegionsWithCapabilityResolvesUnscoped() {
        let withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: "Germany",
            supportsUnscopedFloodSend: true
        )
        let withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: nil,
            supportsUnscopedFloodSend: true
        )
        #expect(withDefault == .unscoped)
        #expect(withoutDefault == .unscoped)
    }

    @Test(".allRegions on older firmware falls back to .scope(.disabled)")
    func allRegionsWithoutCapabilityFallsBackToDisabled() {
        let withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: "Germany",
            supportsUnscopedFloodSend: false
        )
        let withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .allRegions,
            deviceDefaultFloodScopeName: nil,
            supportsUnscopedFloodSend: false
        )
        #expect(withDefault == .scope(.disabled))
        #expect(withoutDefault == .scope(.disabled))
    }

    @Test(".region(name) resolves to that region regardless of default or capability")
    func specificRegionResolvesThatRegion() {
        let withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .region("France"),
            deviceDefaultFloodScopeName: "Germany",
            supportsUnscopedFloodSend: true
        )
        let withoutCapability = ChannelFloodScopeResolver.resolve(
            channelFloodScope: .region("France"),
            deviceDefaultFloodScopeName: nil,
            supportsUnscopedFloodSend: false
        )
        #expect(withDefault == .scope(.region("France")))
        #expect(withoutCapability == .scope(.region("France")))
    }
}
