import Foundation

/// Store operations for cached link preview data.
public protocol LinkPreviewPersisting: Actor {
  /// Fetch link preview data by URL
  func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO?

  /// Save or update link preview data
  func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws
}
