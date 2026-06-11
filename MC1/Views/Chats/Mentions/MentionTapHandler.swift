import SwiftUI
import MC1Services

/// Installs the chat-content `OpenURLAction` and the mention disambiguation
/// sheet on any chat surface that renders `MessageText`. Mention links
/// (`meshcoreone://mention/...`) are resolved locally through
/// `MentionTapEvaluator`; every other chat-relevant URL is forwarded to
/// `ChatLinkRouter`. Used by both `ChatConversationView` (DMs, channels) and
/// `RoomConversationView` so mention taps behave identically across all chat
/// surfaces rather than dying silently where no interceptor is installed.
struct MentionTapHandler: ViewModifier {
    @Environment(\.appState) private var appState

    let contacts: [ContactDTO]
    let radioID: UUID

    @State private var pickerContext: MentionPickerContext?
    @State private var resolverTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .environment(\.openURL, OpenURLAction { url in
                if let mentionName = MentionDeeplinkSupport.name(from: url) {
                    handleMentionTap(name: mentionName)
                    return .handled
                }
                return ChatLinkRouter.route(url, appState: appState) ? .handled : .systemAction
            })
            .sheet(item: $pickerContext) { context in
                MentionPickerSheet(
                    context: context,
                    onSelect: { contact in
                        pickerContext = nil
                        appState.navigation.navigateToContactDetail(contact)
                    },
                    onDismiss: { pickerContext = nil }
                )
            }
            .onDisappear {
                resolverTask?.cancel()
                resolverTask = nil
            }
    }

    /// Defers resolution into a `@MainActor` task so the sheet mutation lands on
    /// a later runloop turn rather than inside the synchronous `openURL`
    /// callback, matching `ChatLinkRouter`'s deferral convention.
    private func handleMentionTap(name: String) {
        resolverTask?.cancel()
        resolverTask = Task { @MainActor in
            let outcome = MentionTapEvaluator.evaluate(
                rawName: name,
                contacts: contacts,
                connectedDeviceName: appState.connectedDevice?.nodeName,
                radioID: radioID
            )
            guard !Task.isCancelled else { return }
            switch outcome {
            case .navigate(let contact):
                appState.navigation.navigateToContactDetail(contact)
            case .picker(let context):
                pickerContext = context
            }
        }
    }
}

extension View {
    /// Routes mention-link taps through `MentionTapEvaluator` and forwards every
    /// other chat URL to `ChatLinkRouter`. Apply to any chat surface that renders
    /// `MessageText`.
    func mentionTapHandling(contacts: [ContactDTO], radioID: UUID) -> some View {
        modifier(MentionTapHandler(contacts: contacts, radioID: radioID))
    }
}
