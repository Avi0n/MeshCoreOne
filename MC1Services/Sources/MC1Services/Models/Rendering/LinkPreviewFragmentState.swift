import Foundation

public struct LinkPreviewFragmentState: Sendable, Hashable {
  public enum Mode: Sendable, Hashable {
    case idle
    case loading(URL)
    case loaded(LinkPreviewDataDTO, image: ImageReference?, icon: ImageReference?)
    case noPreview
    case disabled(URL)
    case legacy(url: URL, title: String?, image: ImageReference?, icon: ImageReference?)
  }

  public let mode: Mode

  /// Remembered width-over-height ratio of the hero image for this URL, from
  /// the persisted dimensions store. Lets the loading shimmer reserve the
  /// final card footprint instead of guessing `fallbackAspect`, so the cell
  /// keeps one height across the load. `nil` when the size was never seen.
  public let heroAspectHint: Double?

  public init(mode: Mode, heroAspectHint: Double? = nil) {
    self.mode = mode
    self.heroAspectHint = heroAspectHint
  }

  /// The single openable URL the preview resolves to: the destination of a
  /// loaded or legacy card. `nil` for modes that show no openable card
  /// (`idle`, `loading`, `noPreview`, `disabled`). The preview card's tap path
  /// and the bubble's accessibility "open link" action both read this so the
  /// URL is parsed in one place rather than re-derived per call site.
  public var primaryURL: URL? {
    switch mode {
    case let .loaded(preview, _, _):
      URL(string: preview.url)
    case let .legacy(url, _, _, _):
      url
    case .idle, .loading, .noPreview, .disabled:
      nil
    }
  }
}
