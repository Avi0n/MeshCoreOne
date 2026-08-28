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
    var repliesByCommand: [String: String] = [:]
    var errorsByCommand: [String: Error] = [:]

    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      if let error = errorsByCommand[command] { throw error }
      return repliesByCommand[command] ?? reply
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

@Suite("RepeaterSettingsViewModel default scope")
@MainActor
struct RepeaterSettingsDefaultScopeTests {
  private func makeViewModel(recorder: RepeaterSettingsRegionTests.CommandRecorder) -> RepeaterSettingsViewModel {
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
  func `parseDefaultScopeReply reads get and set lines`() {
    #expect(
      RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is <null>")
        == RepeaterSettingsViewModel.ParsedDefaultScope.cleared
    )
    #expect(
      RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is duckburg")
        == .named("duckburg")
    )
    #expect(
      RepeaterSettingsViewModel.parseDefaultScopeReply(">  default scope is now duckburg")
        == .named("duckburg")
    )
    #expect(
      RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is now <null>")
        == RepeaterSettingsViewModel.ParsedDefaultScope.cleared
    )
    #expect(RepeaterSettingsViewModel.parseDefaultScopeReply("OK") == nil)
    #expect(
      RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is *")
        == RepeaterSettingsViewModel.ParsedDefaultScope.cleared
    )
  }

  @Test
  func `fetchRegions reads default scope after the tree`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n  duckburg F",
      "region default": " default scope is duckburg"
    ]
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(recorder.commands == ["region", "region default"])
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `fetchRegions treats firmware null as no default scope`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F",
      "region default": " default scope is <null>"
    ]
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(viewModel.defaultScopeName == nil)
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `unparsed default reply does not fail the regions section`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F",
      "region default": "OK"
    ]
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `unparsed default reply keeps the previous default scope`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n  duckburg F",
      "region default": " default scope is duckburg"
    ]
    let viewModel = makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    recorder.repliesByCommand["region default"] = "OK"
    await viewModel.fetchRegions()

    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `default-scope timeout keeps the previous value and does not fail regions`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n  duckburg F",
      "region default": " default scope is duckburg"
    ]
    let viewModel = makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    recorder.errorsByCommand["region default"] = RemoteNodeError.timeout
    await viewModel.fetchRegions()

    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `setDefaultScope sends region default and does not mark unsaved`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.reply = " default scope is now duckburg"
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "duckburg", floodAllowed: false, isHome: false)
    ]

    await viewModel.setDefaultScope(name: "duckburg")

    #expect(recorder.commands == ["region default duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(!viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.regions.first?.floodAllowed == true)
    #expect(viewModel.helper.errorMessage == nil)
  }

  @Test
  func `setDefaultScope nil sends firmware null token`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.reply = " default scope is now <null>"
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.defaultScopeName = "duckburg"

    await viewModel.setDefaultScope(name: nil)

    #expect(recorder.commands == ["region default <null>"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `setDefaultScope non-matching reply leaves the current value`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.reply = "Err - unknown region"
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.defaultScopeName = "old"

    await viewModel.setDefaultScope(name: "nope")

    #expect(viewModel.defaultScopeName == "old")
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion)
  }

  @Test
  func `setDefaultScope does not send wildcard`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    let viewModel = makeViewModel(recorder: recorder)

    await viewModel.setDefaultScope(name: RepeaterSettingsViewModel.wildcardName)

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.defaultScopeName == nil)
  }

  @Test
  func `setDefaultScope same value is a no-op`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.defaultScopeName = "duckburg"

    await viewModel.setDefaultScope(name: "duckburg")

    #expect(recorder.commands.isEmpty)
  }

  @Test
  func `removeRegion of current default sends remove then default clear`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region remove duckburg": "OK",
      "region default <null>": " default scope is now <null>"
    ]
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "duckburg", floodAllowed: true, isHome: false)
    ]
    viewModel.defaultScopeName = "duckburg"
    viewModel.defaultScopeLoaded = true

    await viewModel.removeRegion(name: "duckburg")

    #expect(recorder.commands == ["region remove duckburg", "region default <null>"])
    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.helper.errorMessage == nil)
    #expect(!viewModel.helper.isApplying)
  }

  @Test
  func `removeRegion of current default keeps the name when trailing clear fails`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region remove duckburg": "OK",
      "region default <null>": "Err - save failed"
    ]
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "duckburg", floodAllowed: true, isHome: false)
    ]
    viewModel.defaultScopeName = "duckburg"
    viewModel.defaultScopeLoaded = true

    await viewModel.removeRegion(name: "duckburg")

    #expect(recorder.commands == ["region remove duckburg", "region default <null>"])
    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion)
    #expect(!viewModel.helper.isApplying)
  }

  @Test
  func `removeRegion of current default does not send default clear when remove is rejected`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.reply = "Err - not empty"
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "duckburg", floodAllowed: true, isHome: false)
    ]
    viewModel.defaultScopeName = "duckburg"
    viewModel.defaultScopeLoaded = true

    await viewModel.removeRegion(name: "duckburg")

    #expect(recorder.commands == ["region remove duckburg"])
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(!viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.notEmpty)
  }

  @Test
  func `removeRegion of a different region does not send region default`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.reply = "OK"
    let viewModel = makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "duckburg", floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "goosetown", floodAllowed: true, isHome: false)
    ]
    viewModel.defaultScopeName = "duckburg"
    viewModel.defaultScopeLoaded = true

    await viewModel.removeRegion(name: "goosetown")

    #expect(recorder.commands == ["region remove goosetown"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.regions.map(\.name) == ["duckburg"])
  }
}
