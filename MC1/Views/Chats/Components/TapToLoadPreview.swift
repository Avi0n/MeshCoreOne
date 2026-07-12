import SwiftUI

/// Placeholder shown when auto-resolve is disabled but previews are enabled
struct TapToLoadPreview: View {
  let url: URL
  let isLoading: Bool
  let onTap: () -> Void

  var body: some View {
    HStack(spacing: 8) {
      if isLoading {
        ProgressView()
          .controlSize(.small)
      } else {
        Image(systemName: "globe")
          .foregroundStyle(.secondary)
      }

      Text(isLoading ? L10n.Chats.Chats.Preview.loading : L10n.Chats.Chats.Preview.tapToLoad)
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .background(.regularMaterial, in: .rect(cornerRadius: 12))
    .contentShape(Rectangle())
    .tapYieldingToLongPress { if !isLoading { onTap() } }
    .accessibilityLabel(isLoading ? L10n.Chats.Chats.Preview.loadingAccessibility(url.host ?? "link") : L10n.Chats.Chats.Preview.tapAccessibility(url.host ?? "link"))
    .accessibilityHint(isLoading ? L10n.Chats.Chats.Preview.loadingHint : L10n.Chats.Chats.Preview.tapHint)
    .accessibilityAddTraits(.isButton)
    .accessibilityAction { if !isLoading { onTap() } }
    .disabled(isLoading)
  }
}

#Preview("Idle") {
  TapToLoadPreview(url: URL(string: "https://example.com")!, isLoading: false, onTap: {})
    .padding()
}

#Preview("Loading") {
  TapToLoadPreview(url: URL(string: "https://example.com")!, isLoading: true, onTap: {})
    .padding()
}
