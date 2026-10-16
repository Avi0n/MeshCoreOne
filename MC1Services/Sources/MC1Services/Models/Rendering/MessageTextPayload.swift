import Foundation

public struct MessageTextPayload: Sendable, Hashable {
  public let raw: String
  public let formatted: AttributedString?
  public let baseColor: BaseColorSlot
  public let isOutgoing: Bool
  public let currentUserName: String
  /// Nil means no Translation offer row. `raw` / `formatted` always describe
  /// the stored original; the view chooses the visible body from `phase`.
  public let translation: MessageTranslationChrome?

  public init(
    raw: String,
    formatted: AttributedString?,
    baseColor: BaseColorSlot,
    isOutgoing: Bool,
    currentUserName: String,
    translation: MessageTranslationChrome? = nil
  ) {
    self.raw = raw
    self.formatted = formatted
    self.baseColor = baseColor
    self.isOutgoing = isOutgoing
    self.currentUserName = currentUserName
    self.translation = translation
  }
}
