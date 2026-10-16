import MC1Services
import SwiftUI

struct NotificationLevelPicker: View {
  @Binding var selection: NotificationLevel
  let availableLevels: [NotificationLevel]

  init(selection: Binding<NotificationLevel>, availableLevels: [NotificationLevel] = NotificationLevel.allCases) {
    _selection = selection
    self.availableLevels = availableLevels
  }

  var body: some View {
    HStack(spacing: 12) {
      ForEach(availableLevels, id: \.self) { level in
        NotificationLevelPill(
          level: level,
          isSelected: selection == level
        ) {
          selection = level
        }
      }
    }
    .accessibilityElement(children: .contain)
    .accessibilityLabel(L10n.Chats.Chats.NotificationLevel.label)
    .accessibilityValue(selection.localizedAccessibilityDescription)
    .accessibilityHint(L10n.Chats.Chats.NotificationLevel.hint)
  }
}

private struct NotificationLevelPill: View {
  let level: NotificationLevel
  let isSelected: Bool
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      VStack(spacing: 4) {
        Image(systemName: level.iconName)
          .font(.title3)
        Text(level.localizedName)
          .font(.caption)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 12)
      .background {
        if isSelected {
          Color.accentColor
        } else {
          Color(uiColor: .tertiarySystemFill)
        }
      }
      .foregroundStyle(isSelected ? .white : .primary)
      .clipShape(.rect(cornerRadius: 10))
    }
    .buttonStyle(.plain)
    .accessibilityLabel(level.localizedName)
    .accessibilityAddTraits(isSelected ? .isSelected : [])
  }
}
