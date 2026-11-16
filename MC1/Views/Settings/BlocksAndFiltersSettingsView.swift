import MC1Services
import SwiftUI

struct BlocksAndFiltersSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var isAddingRule = false
  @State private var inventory: [TextRulesPreference.Conversation] = []
  @State private var errorMessage: String?

  var body: some View {
    let preference = appState.textRulesPreference
    List {
      BlockingSection()
      rulesSection(preference?.rules)

      if let preference {
        typesSection(preference)
      }
    }
    .themedCanvas(theme)
    .settingsSubpageDestinations()
    .navigationTitle(L10n.Settings.BlocksAndFilters.title)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .primaryAction) {
        Button(L10n.Settings.TextRules.New.title, systemImage: "plus") {
          isAddingRule = true
        }
        .disabled(preference == nil)
      }
    }
    .sheet(isPresented: $isAddingRule) {
      NewTextRuleSheet()
    }
    .errorAlert($errorMessage)
    .task {
      do {
        inventory = try await appState.connectionManager.persistenceStore.textRulesConversations()
      } catch {
        errorMessage = L10n.Localizable.Common.Error.failedToLoad
      }
    }
  }

  private func rulesSection(_ rules: [TextRulesPreference.Rule]?) -> some View {
    Section {
      if let rules {
        if rules.isEmpty {
          Text(L10n.Settings.TextRules.empty)
            .foregroundStyle(.secondary)
        }
        ForEach(rules) { rule in
          VStack(alignment: .leading) {
            Text(verbatim: rule.phrase)
            Text(verbatim: rule.summary)
              .font(.subheadline)
              .foregroundStyle(.secondary)
          }
          .accessibilityElement(children: .combine)
          .swipeActions {
            Button(L10n.Localizable.Common.delete, systemImage: "trash", role: .destructive) {
              delete(rule)
            }
          }
          .accessibilityAction(named: Text(L10n.Localizable.Common.delete)) {
            delete(rule)
          }
        }
      } else {
        TextRulesLoadNotice()
      }
    } header: {
      Text(L10n.Settings.TextRules.rules)
    }
    .themedRowBackground(theme)
  }

  private func typesSection(_ preference: TextRulesPreference) -> some View {
    Section {
      ForEach(TextRulesPreference.Kind.allCases, id: \.self) { kind in
        let selection = preference.selection(for: kind)
        Toggle(kind.title, isOn: Binding(get: { selection.isOn }, set: { isOn in
          updatePreference { $0.setSelection(isOn ? .all : .off, for: kind) }
        }))
        if selection.isOn {
          let value = summary(for: kind, selection: selection)
          NavigationLink(value: SettingsSubpage.textRuleChats(kind)) {
            LabeledContent(L10n.Settings.ChatSettings.title, value: value)
          }
          .accessibilityLabel(L10n.Settings.TextRules.Selection.accessibilityChats(kind.title))
          .accessibilityValue(Text(verbatim: value))
        }
      }
    } header: {
      Text(L10n.Settings.TextRules.Types.header)
    } footer: {
      Text(L10n.Settings.TextRules.Types.footer)
    }
    .themedRowBackground(theme)
  }

  private func summary(for kind: TextRulesPreference.Kind, selection: TextRulesPreference.Selection) -> String {
    if selection == .all { return L10n.Settings.TextRules.Selection.all }
    let names = inventory.filter { $0.kind == kind && selection.includes($0.key) }.map(\.name)
    switch names.count {
    case 0: return L10n.Settings.TextRules.Selection.none
    case 1...2: return names.formatted(.list(type: .and))
    default: return L10n.Settings.TextRules.Selection.count(names.count)
    }
  }

  private func delete(_ rule: TextRulesPreference.Rule) {
    updatePreference { $0.removeRule(id: rule.id) }
  }

  private func updatePreference(_ update: (inout TextRulesPreference) -> Void) {
    do {
      try appState.connectionManager.updateTextRules(update)
    } catch {
      errorMessage = L10n.Settings.TextRules.saveFailed
    }
  }
}

extension TextRulesPreference.Kind {
  var title: String {
    switch self {
    case .publicChannel: L10n.Settings.TextRules.Types.publicChannel
    case .hashtagChannel: L10n.Settings.TextRules.Types.hashtagChannel
    case .privateChannel: L10n.Settings.TextRules.Types.privateChannel
    case .directMessage: L10n.Settings.DirectMessages.header
    case .room: L10n.Chats.Chats.Filter.rooms
    }
  }
}
