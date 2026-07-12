import Foundation

/// Closed enum: each variant describes one row of content inside a bubble.
/// Adding a case forces every consumer to update.
///
/// The payload type for `.text` is `MessageTextPayload` rather than
/// `MessageText` because module `MC1` already exports a SwiftUI view named
/// `MessageText`.
public enum MessageFragment: Sendable, Hashable {
  case text(MessageTextPayload)
  case inlineImage(InlineImage)
  case linkPreview(LinkPreviewFragmentState)
  case mapPreview(MapPreviewFragmentState)
  case malwareWarning(URL)
  /// Carries the raw summary string (`"👍:3,❤️:2,😂:1"` format produced by
  /// `ReactionParser`). The DTO field `reactionSummary` is `String?`; the
  /// fragment is only emitted when the value is non-nil and non-empty, so
  /// the payload is a non-optional `String`. Parsing back into per-emoji
  /// counts happens at render time.
  case reactionSummary(String)
}
