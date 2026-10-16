import MC1Services
import SwiftUI
import UIKit

/// Read-only per-message lookups consumed by `MessageBubbleView`.
///
/// Pinned to `@MainActor` (not `Sendable`) because the captured closures
/// read state on `ChatViewModel`, which is `@Observable @MainActor` and
/// not `Sendable`. All bubble-view callers are already main-actor isolated,
/// so the non-Sendable cost is zero.
///
/// Bubble views adopt `Equatable` with `==` defined on `MessageItem` alone.
/// The resolver intentionally does not participate in equality — closures
/// are reference-typed and not directly comparable. SwiftUI relies on the
/// `Equatable` `MessageItem` value to decide whether a row needs a redraw;
/// every input that affects bubble rendering is encoded into that struct
/// during `rebuildDisplayItem`.
@MainActor
struct BubbleResolver {
  /// Resolves the full `MessageDTO` for a `MessageItem`. Returns `nil`
  /// when the message has been deleted out from under the timeline.
  let message: (MessageItem) -> MessageDTO?

  /// Resolves a decoded `UIImage` for an `ImageReference` (inline, link
  /// preview hero, or link preview icon).
  let image: (ImageReference) -> UIImage?
}

extension BubbleResolver {
  /// Convenience: build a resolver that proxies to a `ChatViewModel`.
  /// Per-message reads route through the VM's existing storage.
  init(viewModel: ChatViewModel) {
    self.init(
      message: { item in viewModel.message(for: item) },
      image: { ref in
        switch ref.role {
        case .inline:
          viewModel.decodedImage(for: ref.cacheKey)
        case .linkPreviewImage:
          viewModel.decodedPreviewImage(for: ref.cacheKey)
        case .linkPreviewIcon:
          viewModel.decodedPreviewIcon(for: ref.cacheKey)
        }
      }
    )
  }
}
