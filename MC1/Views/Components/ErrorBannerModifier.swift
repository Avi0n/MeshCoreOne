import SwiftUI

/// A passive error banner pinned to the bottom safe-area inset. Mirrors the
/// `ErrorAlertModifier` binding shape so call sites stay symmetric. Tap the
/// strip to dismiss; call sites typically clear the binding on the next
/// successful load.
struct ErrorBannerModifier: ViewModifier {
  @Binding var errorMessage: String?

  func body(content: Content) -> some View {
    content.safeAreaInset(edge: .bottom, spacing: 0) {
      if let message = errorMessage {
        Button {
          errorMessage = nil
        } label: {
          HStack(spacing: 8) {
            Image(systemName: "exclamationmark.circle")
              .foregroundStyle(.red)
            Text(message)
              .font(.footnote)
              .foregroundStyle(.primary)
              .multilineTextAlignment(.leading)
            Spacer(minLength: 0)
          }
          .padding(.horizontal, 16)
          .padding(.vertical, 8)
          .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          .contentShape(.rect)
          .background(
            Color(.systemRed).opacity(0.12),
            in: .rect(cornerRadius: 12)
          )
          .overlay(
            RoundedRectangle(cornerRadius: 12)
              .stroke(Color(.systemRed).opacity(0.25), lineWidth: 1)
          )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(message)
        .accessibilityHint(L10n.Chats.Chats.Error.Banner.dismissAccessibilityHint)
      }
    }
  }
}

extension View {
  /// Mounts a passive error banner driven by a `Binding<String?>`. The banner
  /// appears at the bottom safe-area inset when the binding is non-nil and
  /// stays visible until the user taps it or a call site clears the binding
  /// on the next successful load. Use for background failures (loads,
  /// prefetch). Use `.errorAlert(...)` for user-initiated failures.
  func errorBanner(_ errorMessage: Binding<String?>) -> some View {
    modifier(ErrorBannerModifier(errorMessage: errorMessage))
  }
}
