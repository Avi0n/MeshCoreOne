// MC1/Views/Chats/Components/FallbackMatchIndicatorView.swift
import SwiftUI

/// Tappable indicator showing a node name was resolved from a short prefix with multiple matches.
struct FallbackMatchIndicatorView: View {
    @State private var isShowingExplanation = false

    var body: some View {
        Button {
            isShowingExplanation = true
        } label: {
            Image(systemName: "questionmark.circle")
                .imageScale(.small)
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.Chats.Chats.Path.Hop.possibleMatch)
        .accessibilityHint(L10n.Chats.Chats.Path.Hop.possibleMatchExplanation)
        .popover(isPresented: $isShowingExplanation) {
            VStack(alignment: .leading, spacing: 12) {
                Label {
                    Text(L10n.Chats.Chats.Path.Hop.possibleMatchTitle)
                        .font(.headline)
                } icon: {
                    Image(systemName: "questionmark.circle")
                        .foregroundStyle(.secondary)
                }

                Text(L10n.Chats.Chats.Path.Hop.possibleMatchExplanation)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: 300)
            .presentationCompactAdaptation(.popover)
        }
    }
}
