import Foundation
import MC1Services
import Testing
@testable import MC1

@Suite("MCOimg message content")
struct MCOImageMessageTests {
  @Test func encodedPixelsBakeWithoutTextPreviewsOrTranslation() throws {
    let image = MCOImageRaster(width: 2, height: 2, profile: 0, pixels: [0, 1, 1, 0])
    let body = try MCOImageV3Codec.encode(image, nonce: 42)
    let text = try MCOImageCodec.text(fromAppPayload: Data([MCOImageCodec.v3Subtype]) + body)
    let message = MessageFragmentBuilderFixtures.makeMessage(text: text)
    let inputs = MessageFragmentBuilderFixtures.makeMinimalInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs,
                                             envInputs: MessageFragmentBuilderFixtures.makeEnvInputs())
    #expect(item.content == [.mcoImage(.raster(image))])
    #expect(!item.shouldRequestPreviewFetch)
    #expect(item.translation == nil)
    #expect(MessageLanguageDetector.dominantLanguage(for: text) == .undetermined)
    let layout = FragmentLayout(content: item.content)
    #expect(layout.mcoImage == .raster(image))
    #expect(layout.textPayload == nil)
    #expect(layout.siblings.isEmpty)
  }

  @Test func terminalPlaceholdersDifferAndParticipateInItemIdentity() {
    let id = UUID()
    let unsupported = MessageFragmentBuilderFixtures.makeMessage(id: id, text: "im5:anything")
    let damaged = MessageFragmentBuilderFixtures.makeMessage(id: id, text: "im3:")
    let inputs = MessageFragmentBuilderFixtures.makeMinimalInputs(messageID: id)
    let environment = MessageFragmentBuilderFixtures.makeEnvInputs()
    let first = MessageFragmentBuilder.makeItem(for: unsupported, inputs: inputs, envInputs: environment)
    let second = MessageFragmentBuilder.makeItem(for: damaged, inputs: inputs, envInputs: environment)
    #expect(first.content == [.mcoImage(.unsupported)])
    #expect(second.content == [.mcoImage(.damaged)])
    #expect(first != second)
    #expect(Set([first, second]).count == 2)
  }
}
