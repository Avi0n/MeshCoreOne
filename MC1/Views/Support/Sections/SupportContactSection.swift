import SwiftUI

/// Required by App Store Review §1.5. Mirrors the developer support address already in About.
struct SupportContactSection: View {
  @Environment(\.appTheme) private var theme

  private enum Support {
    static let email = "info@meshcoreone.com"
    static let mailtoURL = URL(string: "mailto:\(email)")
  }

  var body: some View {
    Section {
      if let mailtoURL = Support.mailtoURL {
        Link(destination: mailtoURL) {
          HStack {
            TintedLabel(L10n.Settings.Support.Contact.link, systemImage: "envelope")
            Spacer()
            Image(systemName: "arrow.up.right")
              .font(.caption)
              .foregroundStyle(.tertiary)
          }
        }
        .foregroundStyle(.primary)
      }
    }
    .themedRowBackground(theme)
  }
}
