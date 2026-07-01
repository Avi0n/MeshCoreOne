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

  public init(mode: Mode) {
    self.mode = mode
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
