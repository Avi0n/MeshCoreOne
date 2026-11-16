import MC1Services
import SwiftUI

struct TextRuleChatsView: View {
  let kind: TextRulesPreference.Kind

  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var inventory: [TextRulesPreference.Conversation] = []
  @State private var errorMessage: String?

  var body: some View {
    List {
      if let preference = appState.textRulesPreference {
        selectionSection(preference.selection(for: kind))
      } else {
        TextRulesLoadNotice()
          .themedRowBackground(theme)
      }
    }
    .themedCanvas(theme)
    .navigationTitle(kind.title)
    .navigationBarTitleDisplayMode(.inline)
    .errorAlert($errorMessage)
    .task {
      do {
        inventory = try await appState.connectionManager.persistenceStore.textRulesConversations(kind: kind)
      } catch {
        errorMessage = L10n.Localizable.Common.Error.failedToLoad
      }
    }
  }

  private func selectionSection(_ selection: TextRulesPreference.Selection) -> some View {
    Section {
      Button {
        updateSelection { _ in .all }
      } label: {
        selectionLabel(L10n.Settings.TextRules.Selection.all, radio: nil, isSelected: selection == .all)
      }
      .accessibilityAddTraits(selection == .all ? .isSelected : [])
      ForEach(inventory) { conversation in
        let isSelected = selection.includes(conversation.key)
        Button {
          toggle(conversation.key)
        } label: {
          selectionLabel(conversation.name, radio: conversation.radioName, isSelected: isSelected)
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
      }
    } footer: {
      Text(L10n.Settings.TextRules.Selection.footer)
    }
    .buttonStyle(.plain)
    .disabled(!selection.isOn)
    .themedRowBackground(theme)
  }

  private func selectionLabel(_ title: String, radio: String?, isSelected: Bool) -> some View {
    HStack {
      VStack(alignment: .leading) {
        Text(verbatim: title)
        if let radio {
          Text(verbatim: radio)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
      }
      Spacer()
      if isSelected {
        Image(systemName: "checkmark")
          .foregroundStyle(.tint)
          .accessibilityHidden(true)
      }
    }
    .contentShape(.rect)
  }

  private func toggle(_ key: TextRulesPreference.Key) {
    updateSelection { selection in
      var selected: Set<TextRulesPreference.Key>
      switch selection {
      case .off: return .off
      case .all: selected = Set(inventory.map(\.key))
      case let .fixed(keys): selected = keys
      }
      if selected.remove(key) == nil {
        selected.insert(key)
      }
      return .fixed(selected)
    }
  }

  private func updateSelection(
    _ update: (TextRulesPreference.Selection) -> TextRulesPreference.Selection
  ) {
    do {
      try appState.connectionManager.updateTextRules { preference in
        let selection = preference.selection(for: kind)
        guard selection.isOn else { return }
        preference.setSelection(update(selection), for: kind)
      }
    } catch {
      errorMessage = L10n.Settings.TextRules.saveFailed
    }
  }
}
