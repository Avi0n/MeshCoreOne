import SwiftUI
import MC1Services

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
    }
}
