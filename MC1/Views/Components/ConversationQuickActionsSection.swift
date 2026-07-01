import MC1Services
import SwiftUI

struct ConversationQuickActionsSection: View {
  @Environment(\.appTheme) private var theme
  @Binding var notificationLevel: NotificationLevel
  @Binding var isFavorite: Bool
  let availableLevels: [NotificationLevel]

  init(
    notificationLevel: Binding<NotificationLevel>,
    isFavorite: Binding<Bool>,
    availableLevels: [NotificationLevel] = NotificationLevel.allCases
  ) {
    _notificationLevel = notificationLevel
    _isFavorite = isFavorite
    self.availableLevels = availableLevels
  }

  var body: some View {
    Section {
      VStack(spacing: 16) {
        NotificationLevelPicker(selection: $notificationLevel, availableLevels: availableLevels)

        FavoriteToggleRow(isFavorite: $isFavorite)
      }
      .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
    }
    .themedRowBackground(theme)
  }
}
