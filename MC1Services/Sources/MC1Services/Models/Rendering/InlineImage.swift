import Foundation

public struct InlineImage: Sendable, Hashable {
  public enum LoadState: Sendable, Hashable {
    case idle(URL)
    case loading(URL)
    case loaded(ImageReference, isGIF: Bool)
    case failed(URL)
    /// Scope-off tap-to-load placeholder: the master toggle is on but
    /// auto-resolve is disabled for this conversation type, so the image is
    /// not fetched until the user taps. Mirrors `LinkPreviewFragmentState.Mode.disabled`.
    case disabled(URL)
  }

  public let state: LoadState
  public let autoPlayGIFs: Bool
  /// Width-over-height ratio resolved from `InlineImageDimensionsStore` at
  /// build time. `nil` means dimensions are unknown and the view layer
  /// must fall back to its 16:9 reservation skeleton.
  public let cachedAspect: Double?

  public init(state: LoadState, autoPlayGIFs: Bool, cachedAspect: Double? = nil) {
    self.state = state
    self.autoPlayGIFs = autoPlayGIFs
    self.cachedAspect = cachedAspect
  }
}
