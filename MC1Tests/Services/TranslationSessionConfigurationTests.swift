import Foundation
@testable import MC1
import Testing
import Translation

@Suite("TranslationSession.Configuration replacing")
struct TranslationSessionConfigurationTests {
  @Test
  func `same pair invalidates so Equatable changes`() {
    let source = Locale.Language(identifier: "de")
    let target = Locale.Language(identifier: "en")
    let first = TranslationSession.Configuration.replacing(
      nil,
      source: source,
      target: target
    )
    let same = TranslationSession.Configuration.replacing(
      first,
      source: source,
      target: target
    )
    #expect(same.source == first.source)
    #expect(same.target == first.target)
    #expect(same != first)
  }
}
