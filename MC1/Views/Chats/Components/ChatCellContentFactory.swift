import MC1Services
import SwiftUI

/// Builds the SwiftUI body for a chat cell from a `MessageItem`. Owned by
/// `ChatMessagesTableView` for the lifetime of the bound `ChatViewModel`
/// and passed to `ChatTableView` so the closure handed to
/// `UIHostingConfiguration` captures a stable struct rather than rebuilding
/// its closure body each SwiftUI render.
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
  /// auto-cross the `UIHostingConfiguration` boundary, so the bubble fill would otherwise
  /// see only `Theme.default`. The factory carries the current theme on every render, but a
  /// visible cell adopts a theme change only when it is reloaded — driven by
  /// `reconfigureAllItems()`, which the table fires when its tracked theme id changes — or
  /// recycled; re-wiring the cell closure alone does not re-host live cells.
  let theme: Theme
  /// The chat-content link router, injected into each hosted cell. Like `\.appTheme`, the
  /// `\.openURL` action does not cross the `UIHostingConfiguration` boundary, so a message
  /// link (coordinate, mention, hashtag, contact, channel) would otherwise reach the default
  /// system handler and never route through `ChatLinkRouter`. The factory carries the action
  /// the surrounding `mentionTapHandling` installed; a live cell adopts a change only on
  /// reconfigure or recycle, matching the theme injection.
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
