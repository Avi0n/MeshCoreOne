@testable import MC1
import MC1Services
import Testing

@MainActor
struct SettingsEditorPresentationTests {
  @Test(arguments: [false, true])
  func `name draft enforces UTF 8 limit`(multibyte: Bool) throws {
    let fixture = Fixture(kind: .deviceName)
    let request = try fixture.present(.deviceName)
    let limit = ProtocolLimits.maxUsableNameBytes
    let tail = multibyte ? "é" : "a"
    let accepted = String(repeating: "a", count: limit - tail.utf8.count) + tail

    fixture.presentation.updateDraft(accepted, requestID: request.id)
    #expect(fixture.presentation.draft == accepted)
    fixture.presentation.updateDraft(accepted + "🛰", requestID: request.id)
    #expect(fixture.presentation.draft == accepted)
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted == [accepted])
  }

  @Test
  func `name draft does not split A multibyte character`() throws {
    let fixture = Fixture(kind: .deviceName)
    let request = try fixture.present(.deviceName)
    let prefix = String(repeating: "a", count: ProtocolLimits.maxUsableNameBytes - 1)
    fixture.presentation.updateDraft(prefix + "é", requestID: request.id)
    #expect(fixture.presentation.draft == prefix)
  }

  @Test(arguments: ["  Relay\n", " \n", ""])
  func `name submission forwards raw draft to existing save pipeline`(value: String) throws {
    let fixture = Fixture(kind: .deviceName)
    let request = try fixture.present(.deviceName)
    fixture.presentation.updateDraft(value, requestID: request.id)
    fixture.presentation.setPresented(false, requestID: request.id)
    #expect(fixture.presentation.isPresented == false)
    #expect(fixture.context.submitted.isEmpty)

    fixture.presentation.confirm(requestID: request.id)
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted == [value])
    #expect(fixture.presentation.request == nil)
  }

  @Test
  func `regions use live duplicates and retry the same draft`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("  North  ", requestID: request.id)
    fixture.context.knownRegions = ["North"]
    fixture.presentation.setPresented(false, requestID: request.id)
    let retry = try #require(fixture.presentation.confirm(requestID: request.id))
    #expect(fixture.context.submitted.isEmpty)
    #expect(fixture.presentation.validationMessage == L10n.Chats.Chats.ChannelInfo.Region.duplicate)
    #expect(fixture.presentation.draft == "  North  ")
    await retry.value
    #expect(fixture.presentation.isPresented)

    fixture.context.knownRegions = []
    fixture.presentation.reconcile()
    fixture.presentation.setPresented(false, requestID: request.id)
    fixture.presentation.confirm(requestID: request.id)
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted == ["North"])
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.validationMessage == nil)
  }

  @Test
  func `invalid regions keep their draft until corrected`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("North\n", requestID: request.id)
    fixture.presentation.setPresented(false, requestID: request.id)
    let retry = try #require(fixture.presentation.confirm(requestID: request.id))
    #expect(fixture.presentation.validationMessage == L10n.Chats.Chats.ChannelInfo.Region.invalidName)
    #expect(fixture.presentation.draft == "North\n")
    await retry.value
    #expect(fixture.presentation.isPresented)

    fixture.presentation.updateDraft("South", requestID: request.id)
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted == ["South"])
    #expect(fixture.presentation.validationMessage == nil)
  }

  @Test
  func `empty region retains existing retry without A message`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("   ", requestID: request.id)
    fixture.presentation.setPresented(false, requestID: request.id)
    let retry = try #require(fixture.presentation.confirm(requestID: request.id))
    await retry.value
    #expect(fixture.presentation.isPresented)
    #expect(fixture.presentation.draft == "   ")
    #expect(fixture.presentation.validationMessage == nil)
    #expect(fixture.context.submitted.isEmpty)
    fixture.presentation.cancel(requestID: request.id)
  }

  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd])
  func `raw false before cancel retires the current request`(kind: SettingsEditorPresentation.Kind) throws {
    let fixture = Fixture(kind: kind)
    let request = try fixture.present(kind)
    fixture.presentation.setPresented(false, requestID: request.id)
    fixture.presentation.cancel(requestID: request.id)
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.draft.isEmpty)
    #expect(fixture.context.submitted.isEmpty)
  }
}
