import Foundation

extension ConnectionManager {
  static var defaultTextRulesURL: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appending(path: "TextRules.json")
  }

  func loadTextRules() {
    defer { isTextRulesLoading = false }
    do {
      let data = try Data(contentsOf: textRulesURL)
      let preference = try JSONDecoder().decode(TextRulesPreference.self, from: data)
      guard preference.isValid else { throw CocoaError(.fileReadCorruptFile) }
      textRulesPreference = preference
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
      textRulesPreference = TextRulesPreference()
    } catch {
      textRulesPreference = nil
      logger.error("Could not load Text Rules: \(error.localizedDescription)")
    }
  }

  public func retryTextRulesLoad() async {
    guard !isTextRulesLoading, textRulesPreference == nil else { return }
    isTextRulesLoading = true
    await Task.yield()
    loadTextRules()
  }

  public func updateTextRules(_ update: (inout TextRulesPreference) -> Void) throws {
    guard !isTextRulesLoading, var preference = textRulesPreference else {
      throw CocoaError(.fileReadCorruptFile)
    }
    update(&preference)
    guard preference.isValid else { throw CocoaError(.fileWriteInapplicableStringEncoding) }
    guard preference != textRulesPreference else { return }
    preference.revision = UUID()
    let data = try JSONEncoder().encode(preference)
    try FileManager.default.createDirectory(
      at: textRulesURL.deletingLastPathComponent(),
      withIntermediateDirectories: true
    )
    try data.write(to: textRulesURL, options: .atomic)
    textRulesPreference = preference
  }
}
