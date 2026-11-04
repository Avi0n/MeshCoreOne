import MC1Services

extension AppState {
  func setTextRulesOverride(
    _ choice: TextRulesPreference.Override,
    kind: TextRulesPreference.Kind,
    key: TextRulesPreference.Key
  ) {
    guard textRulesPreference != nil, textRulesOverrideWrites.insert(key).inserted else { return }
    textRulesOverrideErrors[key] = nil
    Task {
      defer { textRulesOverrideWrites.remove(key) }
      do {
        try await connectionManager.persistenceStore.setTextRulesOverride(choice, kind: kind, key: key)
        textRulesOverrideVersion += 1
      } catch {
        textRulesOverrideErrors[key] = L10n.Settings.TextRules.saveFailed
      }
    }
  }
}
