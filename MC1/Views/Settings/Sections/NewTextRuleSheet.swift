import MC1Services
import SwiftUI

struct NewTextRuleSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @State private var phrase = ""
  @State private var lookIn = TextRulesPreference.Rule.LookIn.message
  @State private var match = TextRulesPreference.Rule.Match.contains
  @State private var isSaving = false
  @State private var errorMessage: String?

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
          VStack(alignment: .leading) {
            Text(match == .contains ? L10n.Settings.TextRules.New.containsFooter : L10n.Settings.TextRules.New.wholeFieldFooter)
            Text(lookIn.footer)
            if lookIn != .message {
              Text(L10n.Settings.TextRules.reactionFooter)
            }
            Text(L10n.Settings.TextRules.New.deliveryFooter)
          }
        }
        .disabled(isSaving)
        .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Settings.TextRules.New.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) { dismiss() }
            .disabled(isSaving)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Add.add, action: add)
            .disabled(isSaving || phrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
      }
      .errorAlert($errorMessage)
    }
    .interactiveDismissDisabled(isSaving)
  }

  private func add() {
    let rule = TextRulesPreference.Rule(phrase: phrase, lookIn: lookIn, match: match)
    guard !isSaving, rule.isValid else { return }
    isSaving = true
    Task {
      defer { isSaving = false }
      do {
        try appState.connectionManager.updateTextRules { $0.append(rule) }
        dismiss()
      } catch {
        errorMessage = L10n.Settings.TextRules.saveFailed
      }
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

  var footer: String {
    switch self {
    case .message: L10n.Settings.TextRules.Field.messageFooter
    case .sender: L10n.Settings.TextRules.Field.senderFooter
    case .messageOrSender: L10n.Settings.TextRules.Field.messageOrSenderFooter
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
