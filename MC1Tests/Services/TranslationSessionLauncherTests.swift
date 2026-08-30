import Foundation
@testable import MC1
import Testing
import Translation

@Suite("TranslationSessionLauncher")
@MainActor
struct TranslationSessionLauncherTests {
  @Test
  func `finished installed perform returns no configuration`() async {
    guard #available(iOS 26.0, *) else { return }
    let request = TranslationSessionRequest(
      messageID: UUID(),
      sourceLanguageCode: "de",
      targetLanguageCode: "en",
      generation: 1
    )
    let configuration = await TranslationSessionLauncher.launch(
      request: request,
      replacing: nil
    ) { _ in .finished }
    #expect(configuration == nil)
  }

  @Test
  func `needsDownload falls back to a configuration for the pair`() async {
    let request = TranslationSessionRequest(
      messageID: UUID(),
      sourceLanguageCode: "ja-JP",
      targetLanguageCode: "en-US",
      generation: 1
    )
    let configuration = await TranslationSessionLauncher.launch(
      request: request,
      replacing: nil
    ) { _ in .needsDownload }
    #expect(configuration != nil)
  }

  @Test
  func `needsDownload then finished on the second installed session returns no configuration`() async {
    guard #available(iOS 26.4, *) else { return }
    let request = TranslationSessionRequest(
      messageID: UUID(),
      sourceLanguageCode: "de",
      targetLanguageCode: "en",
      generation: 1
    )
    var performCount = 0
    let configuration = await TranslationSessionLauncher.launch(
      request: request,
      replacing: nil
    ) { _ in
      performCount += 1
      return performCount == 1 ? .needsDownload : .finished
    }
    #expect(configuration == nil)
    #expect(performCount == 2)
  }

  @Test
  func `wrapping remaps notInstalled`() {
    guard #available(iOS 26.0, *) else { return }
    let remapped = MessageTranslationNeedsDownloadError.wrapping(TranslationError.notInstalled)
    #expect(remapped is MessageTranslationNeedsDownloadError)
  }

  @Test
  func `presentSystemOverlay returns no configuration`() async {
    guard #available(iOS 26.0, *) else { return }
    let request = TranslationSessionRequest(
      messageID: UUID(),
      sourceLanguageCode: "de",
      targetLanguageCode: "en",
      generation: 1
    )
    let configuration = await TranslationSessionLauncher.launch(
      request: request,
      replacing: nil
    ) { _ in .presentSystemOverlay(text: "Guten Morgen") }
    #expect(configuration == nil)
  }

  @Test
  func `presentSystemOverlay on the first installed session does not try the second`() async {
    guard #available(iOS 26.4, *) else { return }
    let request = TranslationSessionRequest(
      messageID: UUID(),
      sourceLanguageCode: "de",
      targetLanguageCode: "en",
      generation: 1
    )
    var performCount = 0
    let configuration = await TranslationSessionLauncher.launch(
      request: request,
      replacing: nil
    ) { _ in
      performCount += 1
      return .presentSystemOverlay(text: "Guten Morgen")
    }
    #expect(configuration == nil)
    #expect(performCount == 1)
  }
}
