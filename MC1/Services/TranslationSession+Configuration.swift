import Foundation
import Translation

extension TranslationSession.Configuration {
  /// Copy-mutate-reassign so a same-pair second message re-runs `.translationTask`.
  /// `invalidate()` is mutating on a struct; optional-struct mutate in place does not publish.
  static func replacing(
    _ current: Self?,
    source: Locale.Language,
    target: Locale.Language
  ) -> Self {
    if var config = current, config.source == source, config.target == target {
      config.invalidate()
      return config
    }
    // highFidelity is Apple Intelligence; lowLatency is Translate language packs.
    // Installed probe tries both; `.translationTask` prefers Intelligence.
    if #available(iOS 26.4, *) {
      return TranslationSession.Configuration(
        source: source,
        target: target,
        preferredStrategy: .highFidelity
      )
    }
    return TranslationSession.Configuration(source: source, target: target)
  }
}
