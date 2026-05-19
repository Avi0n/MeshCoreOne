import SwiftUI
import MC1Services

/// Per-row action wiring for `MessageBubbleView`. Each closure is invoked
/// in response to a user interaction on a bubble and forwards to the
/// owning view model or environment.
///
/// Pinned to `@MainActor` because the closures call into `ChatViewModel`
/// (an `@Observable @MainActor` class). Not `Sendable`; bubble views
/// already run on the main actor.
///
/// Action callbacks are intentionally excluded from `Equatable` on the
/// bubble views: closure identity is not stable across body invocations,
/// so comparing them would defeat the equatable optimization. The
/// rendering invariant is that action wiring stays a function of the
/// message identity, which is captured by `MessageItem.id`.
@MainActor
struct BubbleActions {
    let onRetryMessage: (MessageDTO) -> Void
    let onReaction: (String, MessageDTO) -> Void
    let onLongPress: (MessageDTO) -> Void
    let onImageTap: (MessageDTO) -> Void
    let onRetryImageFetch: (UUID) -> Void
    let onRequestPreviewFetch: (UUID) -> Void
    let onManualPreviewFetch: (UUID) -> Void
}
