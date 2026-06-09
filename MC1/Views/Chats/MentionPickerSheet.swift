import SwiftUI
import MC1Services

/// Disambiguation / status sheet shown when a tapped `@mention` does not resolve
/// to a single saved contact. Presented by `MentionTapHandler` on any chat
/// surface that renders mention links (DMs, channels, and rooms).
struct MentionPickerSheet: View {
    @Environment(\.appState) private var appState

    let context: MentionPickerContext
    let onSelect: (ContactDTO) -> Void
    let onDismiss: () -> Void

    private static let listSpacing: CGFloat = 12
    private static let compactDetentHeight: CGFloat = 180

    var body: some View {
        NavigationStack {
            Group {
                if context.isSelfMention {
                    ContentUnavailableView {
                        Label(L10n.Chats.Chats.Mention.Picker.selfTitle,
                              systemImage: "person.crop.circle")
                    } description: {
                        Text(L10n.Chats.Chats.Mention.Picker.selfSubtitle(context.name))
                    }
                } else if context.matches.isEmpty {
                    ContentUnavailableView {
                        Label(L10n.Chats.Chats.Mention.Picker.notSavedTitle,
                              systemImage: "person.crop.circle.badge.questionmark")
                    } description: {
                        Text(L10n.Chats.Chats.Mention.Picker.notSavedSubtitle(context.name))
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: Self.listSpacing) {
                            Text(L10n.Chats.Chats.Mention.Picker.matchingContacts)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            ForEach(context.matches) { match in
                                ContactMatchRow(
                                    contact: match,
                                    style: .tap,
                                    userLocation: appState.bestAvailableLocation,
                                    action: { onSelect(match) }
                                )
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle(L10n.Chats.Chats.Mention.Picker.title(context.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.done) {
                        onDismiss()
                    }
                }
            }
        }
        .presentationDetents([.height(Self.compactDetentHeight), .medium])
        .presentationDragIndicator(.visible)
    }
}
