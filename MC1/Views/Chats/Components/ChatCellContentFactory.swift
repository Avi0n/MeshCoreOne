import MC1Services
import SwiftUI

/// Builds the SwiftUI body for a chat cell from a `MessageItem`. Built by
/// `ChatConversationMessagesContent` for the bound `ChatViewModel` and routed
/// to `ChatTiledView` (via `CellContentHost`) so the closure that `MessagingUI`
/// hosts in each cell reflects the current theme and callbacks.
///
/// Pinned to `@MainActor` because `BubbleResolver` and `BubbleActions`
/// proxy to `ChatViewModel` (an `@Observable @MainActor` class). Not
/// `Sendable`; bubble views already run on the main actor.
@MainActor
struct ChatCellContentFactory {
  let contactName: String
  let deviceName: String
  let configuration: MessageBubbleConfiguration
  /// The active theme, injected into each hosted cell. Custom environment values do not
  /// auto-cross the cell-hosting boundary, so the bubble fill would otherwise see only
  /// `Theme.default`. A visible cell adopts a theme change only when reconfigured or
  /// recycled; `ChatTiledView` rebuilds the whole list (keyed on the appearance identity)
  /// when the theme changes so every cell repaints.
  let theme: Theme
  /// The chat-content link router, injected into each hosted cell. Like `\.appTheme`, the
  /// `\.openURL` action does not cross the cell-hosting boundary, so a message link
  /// (coordinate, mention, hashtag, contact, channel) would otherwise reach the default
  /// system handler and never route through `ChatLinkRouter`. The factory carries the action
  /// the surrounding `mentionTapHandling` installed.
  let openURL: OpenURLAction
  let resolver: BubbleResolver
  let actions: BubbleActions

  func makeContent(for item: MessageItem) -> some View {
    MessageBubbleView(
      item: item,
      contactName: contactName,
      deviceName: deviceName,
      configuration: configuration,
      resolver: resolver,
      actions: actions
    )
    .environment(\.appTheme, theme)
    .environment(\.openURL, openURL)
  }
}
