import MC1Services
import SwiftUI

struct ConversationQuickActionsSection: View {
  @Environment(\.appTheme) private var theme
  @Binding var isPinned: Bool
  @Binding var notificationLevel: NotificationLevel
  let availableLevels: [NotificationLevel]

  init(
    isPinned: Binding<Bool>,
    notificationLevel: Binding<NotificationLevel>,
    availableLevels: [NotificationLevel] = NotificationLevel.allCases
  ) {
    _isPinned = isPinned
    _notificationLevel = notificationLevel
    self.availableLevels = availableLevels
  }

  var body: some View {
    Section {
      NotificationLevelPicker(selection: $notificationLevel, availableLevels: availableLevels)
        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))

      Toggle(isOn: $isPinned) {
        Label(L10n.Chats.Chats.Action.pin, systemImage: "pin")
      }
    }
    .themedRowBackground(theme)
  }
}
