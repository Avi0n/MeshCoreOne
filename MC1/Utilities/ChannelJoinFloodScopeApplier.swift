import Foundation
import MC1Services
import OSLog

/// Best-effort local flood-scope preference after a channel join from URL/QR.
///
/// Does not touch session flood scope (global). That is applied when the
/// conversation loads via `ChatViewModel.syncFloodScope`.
enum ChannelJoinFloodScopeApplier {
  /// Persists `.region(name)` when `regionScope` is non-empty after normalization.
  /// On store failure, logs and returns the original channel so the radio join
  /// still completes (local preference stays `.inherit` if the write failed).
  static func applyIfNeeded(
    channel: ChannelDTO,
    regionScope: String?,
    setFloodScope: @Sendable (UUID, ChannelFloodScope) async throws -> Void,
    logger: Logger
  ) async -> ChannelDTO {
    guard let regionScope,
          let preferred = preferredFloodScope(from: regionScope) else {
      return channel
    }

    do {
      try await setFloodScope(channel.id, preferred)
      return channel.with(floodScope: preferred)
    } catch {
      logger.error("Failed to persist channel flood scope after join: \(error.localizedDescription)")
      return channel
    }
  }

  /// Resolves a URL `region_scope` value to a local flood preference.
  /// - Returns: `.region(name)` for non-empty normalized scopes; otherwise `nil` (no write).
  static func preferredFloodScope(from regionScope: String?) -> ChannelFloodScope? {
    guard let name = MeshCoreURLParser.normalizedRegionScope(regionScope) else {
      return nil
    }
    return .region(name)
  }
}
