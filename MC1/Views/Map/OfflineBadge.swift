import Accessibility
import SwiftUI

// MARK: - Offline Badge

struct OfflineBadge: View {
  var body: some View {
    Text(L10n.Map.Map.OfflineBadge.label)
      .font(.caption)
      .bold()
      .padding(.horizontal)
      .padding(.vertical, 6)
      .background(.ultraThinMaterial, in: .capsule)
      .accessibilityAddTraits(.isStaticText)
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
      .padding(.trailing)
      .padding(.top)
      .onAppear {
        AccessibilityNotification.Announcement(L10n.Map.Map.OfflineBadge.label).post()
      }
  }
}
