import MC1Services
import SwiftUI

struct ConversationQuickActionsSection: View {
  @Environment(\.appTheme) private var theme
  @Binding var notificationLevel: NotificationLevel
  let availableLevels: [NotificationLevel]

  init(
    notificationLevel: Binding<NotificationLevel>,
    availableLevels: [NotificationLevel] = NotificationLevel.allCases
  ) {
    _notificationLevel = notificationLevel
    self.availableLevels = availableLevels
  }

  var body: some View {
    Section {
      NotificationLevelPicker(selection: $notificationLevel, availableLevels: availableLevels)
        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
    }
    .themedRowBackground(theme)
  }
}
