import SwiftUI

/// Trailing reload affordance for a collapsible status/settings section label.
/// Shows a spinner while the section is loading and a borderless reload button
/// once it has loaded, matching `ExpandableSettingsSection`'s reload idiom.
/// The button also appears when `hasError` is set so a first-load failure still
/// exposes a retry affordance instead of leaving only the inline error text.
struct SectionReloadButton: View {
  let isLoading: Bool
  let isLoaded: Bool
  let hasError: Bool
  let isDisabled: Bool
  let accessibilityLabel: String
  let onReload: () async -> Void

  private enum Layout {
    static let spinnerScale: CGFloat = 0.8
  }

  var body: some View {
    if isLoading {
      ProgressView()
        .scaleEffect(Layout.spinnerScale)
        .padding(.trailing)
    } else if isLoaded || hasError {
      Button {
        Task { await onReload() }
      } label: {
        Image(systemName: "arrow.clockwise")
          .font(.caption)
      }
      .buttonStyle(.borderless)
      .padding(.trailing)
      .disabled(isDisabled)
      .accessibilityLabel(accessibilityLabel)
    }
  }
}
