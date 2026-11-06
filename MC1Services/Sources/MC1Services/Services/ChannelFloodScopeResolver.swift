import Foundation
import MeshCore

/// Resolves a channel preference for transmission. Inheritance clears the temporary
/// override so the radio uses its persisted default.
public enum ChannelFloodScopeResolver {
  public static func resolve(
    channelFloodScope: ChannelFloodScope,
    supportsUnscopedFloodSend: Bool
  ) -> ResolvedFloodScope {
    switch channelFloodScope {
    case .inherit:
      .scope(.disabled)
    case .allRegions:
      // Older firmware cannot override a persisted default with Unscoped.
      supportsUnscopedFloodSend ? .unscoped : .scope(.disabled)
    case let .region(name):
      .scope(.region(name))
    }
  }
}
