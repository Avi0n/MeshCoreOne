import MC1Services
import SwiftUI

struct ConversationQuickActionsSection: View {
  @Environment(\.appTheme) private var theme
  @Binding var isFavorite: Bool
  @Binding var notificationLevel: NotificationLevel
  let availableLevels: [NotificationLevel]

  init(
    isFavorite: Binding<Bool>,
    notificationLevel: Binding<NotificationLevel>,
    availableLevels: [NotificationLevel] = NotificationLevel.allCases
  ) {
    _isFavorite = isFavorite
    _notificationLevel = notificationLevel
    self.availableLevels = availableLevels
  }

  var body: some View {
    Section {
      Toggle(isOn: $isFavorite) {
        Label(L10n.Chats.Chats.Action.favorite, systemImage: "star")
      }

      NotificationLevelPicker(selection: $notificationLevel, availableLevels: availableLevels)
        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
    }
    .themedRowBackground(theme)
  }
}
