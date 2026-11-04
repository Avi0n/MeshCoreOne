import MC1Services
import SwiftUI

struct ConversationTextRulesSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  private typealias Strings = L10n.Settings.TextRules.Info

  let kind: TextRulesPreference.Kind
  let radioID: UUID
  let conversationID: UUID?
  let savedChoice: TextRulesPreference.Override

  @State private var committedChoice: TextRulesPreference.Override?
  @State private var radioName: String?

  private var key: TextRulesPreference.Key? {
    conversationID.map { .init(radioID: radioID, conversationID: $0) }
  }

  private var selectedChoice: TextRulesPreference.Override {
    committedChoice ?? savedChoice
  }

  private var effective: TextRulesPreference.Effective? {
    guard key != nil else { return nil }
    return appState.textRulesPreference?.effective(for: kind, key: key, override: selectedChoice)
  }

  private var isSaving: Bool {
    key.map(appState.textRulesOverrideWrites.contains) ?? false
  }

  private var errorMessage: Binding<String?> {
    Binding(
      get: { key.flatMap { appState.textRulesOverrideErrors[$0] } },
      set: { value in
        if let key { appState.textRulesOverrideErrors[key] = value }
      }
    )
  }

  var body: some View {
    Section {
      LabeledContent(Strings.status, value: effective.map { status($0.isOn) } ?? Strings.unavailable)
      if let effective {
        Text(source(effective.source))
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }

      Picker(L10n.Settings.TextRules.title, selection: Binding(
        get: { selectedChoice },
        set: { choice in
          guard let key, choice != selectedChoice else { return }
          appState.setTextRulesOverride(choice, kind: kind, key: key)
        }
      )) {
        Text(inheritedLabel).tag(TextRulesPreference.Override.inherit)
        Text(explicitLabel(isOn: true)).tag(TextRulesPreference.Override.on)
        Text(explicitLabel(isOn: false)).tag(TextRulesPreference.Override.off)
      }
      .pickerStyle(.inline)
      .labelsHidden()
      .disabled(effective == nil || isSaving)

      if isSaving {
        ProgressView()
      }
    } header: {
      Text(L10n.Settings.TextRules.title)
    } footer: {
      VStack(alignment: .leading) {
        if appState.textRulesPreference == nil {
          Text(L10n.Settings.TextRules.loadFailed)
        } else if key == nil {
          Text(Strings.conversationUnavailable)
        } else {
          Text(Strings.footer)
        }
        if let radioName {
          Text(Strings.radio(radioName))
        }
      }
    }
    .themedRowBackground(theme)
    .task(id: appState.textRulesOverrideVersion) {
      let store = appState.connectionManager.persistenceStore
      if let key, let conversation = try? await store.textRulesConversation(kind: kind, key: key) {
        committedChoice = conversation.override
        let radios = try? await store.fetchDevices()
        if let radios, Set(radios.map(\.radioID)).count > 1 {
          radioName = conversation.radioName
        }
      }
    }
    .errorAlert(errorMessage)
  }

  private var inheritedLabel: String {
    guard let preference = appState.textRulesPreference else { return kind.title }
    return Strings.inheritedChoice(kind.title, status(preference.selection(for: kind).isOn))
  }

  private func explicitLabel(isOn: Bool) -> String {
    switch kind {
    case .publicChannel, .hashtagChannel, .privateChannel:
      isOn ? Strings.onChannel : Strings.offChannel
    case .directMessage:
      isOn ? Strings.onDm : Strings.offDm
    case .room:
      isOn ? Strings.onRoom : Strings.offRoom
    }
  }

  private func status(_ enabled: Bool) -> String {
    enabled ? L10n.Localizable.Accessibility.on : L10n.Localizable.Accessibility.off
  }

  private func source(_ source: TextRulesPreference.Effective.Source) -> String {
    switch source {
    case .type: Strings.follows(kind.title)
    case .fixedExclusion: Strings.excluded(kind.title)
    case .explicit: Strings.explicit
    }
  }
}
