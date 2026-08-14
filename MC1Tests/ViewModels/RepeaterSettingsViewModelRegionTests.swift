import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// A successful `region put` stores `floodAllowed: true`. Firmware sets flags to 0 on put.
@Suite("RepeaterSettingsViewModel add region")
@MainActor
struct RepeaterSettingsRegionTests {
  @MainActor
  final class CommandRecorder {
    private(set) var commands: [String] = []
    var reply: String = "OK - (flood allowed)"

    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      return reply
    }
  }

  private func makeViewModel(recorder: CommandRecorder) -> RepeaterSettingsViewModel {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.configure(
      session: RemoteNodeSessionDTO(
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Test Repeater",
        role: .repeater,
        isConnected: true,
        permissionLevel: .admin
      ),
      sendCommand: recorder.send,
      sendRawCommand: recorder.send
    )
    return viewModel
  }

  @Test
  func `firmware flood-allow put reply adds the region as flood allowed`() async {
    let recorder = CommandRecorder()
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.addRegion(name: "Europe")

    #expect(recorder.commands == ["region put Europe"])
    #expect(viewModel.helper.errorMessage == nil)
    #expect(viewModel.regions.count == 1)
    #expect(viewModel.regions.first?.name == "Europe")
    #expect(viewModel.regions.first?.floodAllowed == true)
    #expect(viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `legacy OK put reply still adds the region as flood allowed`() async {
    let recorder = CommandRecorder()
    recorder.reply = "OK"
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.addRegion(name: "UK")

    #expect(viewModel.regions.first?.name == "UK")
    #expect(viewModel.regions.first?.floodAllowed == true)
  }

  @Test
  func `empty name is a silent no-op`() async {
    let recorder = CommandRecorder()
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.addRegion(name: "   ")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.regions.isEmpty)
    #expect(viewModel.helper.errorMessage == nil)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `invalid name sets addFailed and does not send`() async {
    let recorder = CommandRecorder()
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.addRegion(name: "my region")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.regions.isEmpty)
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed)
  }

  @Test
  func `non-OK put reply sets addFailed and does not append`() async {
    let recorder = CommandRecorder()
    recorder.reply = "Err - unable to put"
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.addRegion(name: "Europe")

    #expect(recorder.commands == ["region put Europe"])
    #expect(viewModel.regions.isEmpty)
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }
}
