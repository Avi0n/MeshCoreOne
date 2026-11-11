import MC1Services

/// Stored text that chat and room Translate hand to the system translation sheet.
enum MECPTranslateRequest {
  static func text(stored: String) -> String {
    MECPCatalog.displayText(stored)
  }
}
