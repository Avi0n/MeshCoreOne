import SwiftUI

struct FavoriteToggleRow: View {
  @Binding var isFavorite: Bool

  var body: some View {
    HStack {
      Image(systemName: isFavorite ? "star.fill" : "star")
        .foregroundStyle(isFavorite ? .yellow : .secondary)

      Text(L10n.Chats.Chats.Row.favorite)

      Spacer()

      Toggle("", isOn: $isFavorite)
        .labelsHidden()
    }
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Row.favorite)
    .accessibilityValue(isFavorite ? L10n.Localizable.Accessibility.on : L10n.Localizable.Accessibility.off)
  }
}
