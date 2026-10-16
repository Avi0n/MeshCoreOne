import Foundation
import MC1Services
import SwiftUI

/// Protocol for link preview caching, enabling dependency injection and testing
protocol LinkPreviewCaching: Sendable {
  /// Gets preview for a URL, fetching if needed
  func preview(
    for url: URL,
    using dataStore: any PersistenceStoreProtocol,
    isChannelMessage: Bool
  ) async -> LinkPreviewResult

  /// Manual fetch bypassing preference check (for tap-to-load)
  func manualFetch(
    for url: URL,
    using dataStore: any PersistenceStoreProtocol
  ) async -> LinkPreviewResult

  /// Checks if a fetch is currently in progress for a URL
  func isFetching(_ url: URL) async -> Bool

  /// Gets cached preview without triggering fetch
  func cachedPreview(for url: URL) async -> LinkPreviewDataDTO?
}

/// Result of a link preview fetch operation
enum LinkPreviewResult {
  case loaded(LinkPreviewDataDTO)
  case loading
  case noPreviewAvailable
  case disabled
  case failed
}

extension EnvironmentValues {
  @Entry var linkPreviewCache: any LinkPreviewCaching = LinkPreviewCache()
}
