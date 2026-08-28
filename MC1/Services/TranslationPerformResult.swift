import Foundation

enum TranslationPerformResult: Equatable, Sendable {
  case finished
  case needsDownload
  case presentSystemOverlay(text: String)
}
