import MC1Services
import SwiftUI

struct NewTextRuleSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @State private var phrase = ""
  @State private var lookIn = TextRulesPreference.Rule.LookIn.message
  @State private var match = TextRulesPreference.Rule.Match.contains
  @State private var errorMessage: String?

  private var trimmedPhrase: String {
    phrase.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private var ruleFooter: String {
    switch (lookIn, match) {
    case (.message, .contains):
      L10n.Settings.TextRules.New.containsMessageFooter
    case (.message, .wholeField):
      L10n.Settings.TextRules.New.wholeMessageFooter
    case (.sender, .contains):
      L10n.Settings.TextRules.New.containsSenderFooter
    case (.sender, .wholeField):
      L10n.Settings.TextRules.New.wholeNameFooter
    case (.messageOrSender, .contains):
      L10n.Settings.TextRules.New.containsMessageOrSenderFooter
    case (.messageOrSender, .wholeField):
      L10n.Settings.TextRules.New.wholeMessageOrSenderFooter
    }
  }

  var body: some View {
    NavigationStack {
      Form {
        Section {
          TextField(L10n.Settings.TextRules.New.phrase, text: $phrase, axis: .vertical)
          Picker(L10n.Settings.TextRules.New.lookIn, selection: $lookIn) {
            ForEach(TextRulesPreference.Rule.LookIn.allCases, id: \.self) { field in
              Text(field.title).tag(field)
            }
          }
          .pickerStyle(.menu)
          Picker(L10n.Settings.TextRules.New.match, selection: $match) {
            ForEach(TextRulesPreference.Rule.Match.allCases, id: \.self) { mode in
              Text(mode.title(for: lookIn)).tag(mode)
            }
          }
          .pickerStyle(.menu)
        } footer: {
          Text(ruleFooter)
        }
        .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Settings.TextRules.New.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Add.add, action: add)
            .disabled(trimmedPhrase.isEmpty)
        }
      }
      .errorAlert($errorMessage)
    }
  }

  private func add() {
    let rule = TextRulesPreference.Rule(phrase: trimmedPhrase, lookIn: lookIn, match: match)
    guard rule.isValid else { return }
    do {
      try appState.connectionManager.updateTextRules { $0.append(rule) }
      dismiss()
    } catch {
      errorMessage = L10n.Settings.TextRules.saveFailed
    }
  }
}

extension TextRulesPreference.Rule.LookIn {
  var title: String {
    switch self {
    case .message: L10n.Settings.TextRules.Field.message
    case .sender: L10n.Settings.TextRules.Field.sender
    case .messageOrSender: L10n.Settings.TextRules.Field.messageOrSender
    }
  }
}

extension TextRulesPreference.Rule.Match {
  func title(for field: TextRulesPreference.Rule.LookIn) -> String {
    switch (self, field) {
    case (.contains, _): L10n.Settings.TextRules.Match.contains
    case (.wholeField, .message): L10n.Settings.TextRules.Match.wholeMessage
    case (.wholeField, .sender): L10n.Settings.TextRules.Match.wholeName
    case (.wholeField, .messageOrSender): L10n.Settings.TextRules.Match.wholeField
    }
  }
}

extension TextRulesPreference.Rule {
  var summary: String {
    L10n.Settings.TextRules.ruleSummary(match.title(for: lookIn), lookIn.title)
  }
}
