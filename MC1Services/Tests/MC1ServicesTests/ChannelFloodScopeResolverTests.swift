import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ChannelFloodScopeResolver")
struct ChannelFloodScopeResolverTests {
  @Test
  func `.inherit with device default set resolves to .scope(.region(default))`() {
    let resolved = ChannelFloodScopeResolver.resolve(
      channelFloodScope: .inherit,
      deviceDefaultFloodScopeName: "Germany",
      supportsUnscopedFloodSend: true
    )
    #expect(resolved == .scope(.region("Germany")))
  }

  @Test
  func `.inherit with no device default resolves to .scope(.disabled)`() {
    let resolved = ChannelFloodScopeResolver.resolve(
      channelFloodScope: .inherit,
      deviceDefaultFloodScopeName: nil,
      supportsUnscopedFloodSend: true
    )
    #expect(resolved == .scope(.disabled))
  }

  @Test
  func `.inherit with empty-string default treats it as no default`() {
    let resolved = ChannelFloodScopeResolver.resolve(
      channelFloodScope: .inherit,
      deviceDefaultFloodScopeName: "",
      supportsUnscopedFloodSend: true
    )
    #expect(resolved == .scope(.disabled))
  }

  @Test
  func `.allRegions on firmware v12+ resolves to .unscoped (true override)`() {
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

  @Test
  func `.allRegions on older firmware falls back to .scope(.disabled)`() {
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

  @Test
  func `.region(name) resolves to that region regardless of default or capability`() {
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
