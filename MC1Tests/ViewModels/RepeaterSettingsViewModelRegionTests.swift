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
    var onSend: ((String) -> Void)?

    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      onSend?(command)
      if let error = errorsByCommand[command] { throw error }
      return repliesByCommand[command] ?? reply
    }

    func resetCommands() {
      commands.removeAll()
    }
  }

  static func makeViewModel(recorder: CommandRecorder) -> RepeaterSettingsViewModel {
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
  func `firmware flood-allow put reply adds the region as flood allowed`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    try await viewModel.addRegion(name: "Europe", parent: .unscoped)

    #expect(recorder.commands == ["region", "region put Europe"])
    #expect(viewModel.helper.errorMessage == nil)
    #expect(viewModel.regions.count == 2)
    #expect(viewModel.regions.last?.name == "Europe")
    #expect(viewModel.regions.last?.parentName == "*")
    #expect(viewModel.regions.last?.depth == 1)
    #expect(viewModel.regions.last?.floodAllowed == true)
    #expect(viewModel.hasUnsavedRegionChanges)

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "Europe", parent: .unscoped)
    }
  }

  @Test
  func `legacy OK put reply still adds the region as flood allowed`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    recorder.reply = "OK"
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    try await viewModel.addRegion(name: "UK", parent: .unscoped)

    #expect(viewModel.regions.last?.name == "UK")
    #expect(viewModel.regions.last?.floodAllowed == true)
  }

  @Test
  func `empty name throws rejected`() async {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "   ", parent: .unscoped)
    }
    #expect(recorder.commands == ["region"])
    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.helper.errorMessage == nil)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `invalid name sets addFailed and does not send`() async {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "my region", parent: .unscoped)
    }
    #expect(recorder.commands == ["region"])
    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed)
  }

  @Test
  func `non-OK put reply sets addFailed and does not append`() async {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    recorder.reply = "Err - unable to put"
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "Europe", parent: .unscoped)
    }
    #expect(recorder.commands == ["region", "region put Europe"])
    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `addRegion with named parent sends put name parent`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    try await viewModel.addRegion(name: "gta", parent: .named("on"))

    #expect(recorder.commands.last == "region put gta on")
    #expect(viewModel.regions.last?.parentName == "on")
    #expect(viewModel.regions.last?.depth == 2)
    #expect(viewModel.regions.last?.floodAllowed == true)
    #expect(viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `named parent missing from tree throws rejected and does not send`() async {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "gta", parent: .named("nope"))
    }
    #expect(recorder.commands == ["region"])
    #expect(viewModel.regions.map(\.name) == ["*", "on"])
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `addRegion under Unscoped with no star row still appends`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = []

    try await viewModel.addRegion(name: "Europe", parent: .unscoped)

    #expect(recorder.commands.last == "region put Europe")
    #expect(viewModel.regions.last?.parentName == "*")
    #expect(viewModel.regions.last?.depth == 1)
  }

  @Test
  func `addRegion inserts after grandchildren of the parent`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "gta", parentName: "on", depth: 2, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "downtown", parentName: "gta", depth: 3, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "can", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    try await viewModel.addRegion(name: "ottawa", parent: .named("on"))

    #expect(viewModel.regions.map(\.name) == ["*", "on", "gta", "downtown", "ottawa", "can"])
  }

  @Test
  func `addRegion re-finds the parent after the put reply`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = [
      RepeaterRegionEntry(name: "west", parentName: "*", depth: 1, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    try await viewModel.addRegion(name: "gta", parent: .named("on"))

    #expect(viewModel.regions.map(\.name) == ["west", "*", "on", "gta"])
    #expect(viewModel.regions.last?.parentName == "on")
    #expect(viewModel.regions.last?.depth == 2)
  }

  @Test
  func `addRegion uses live parent depth after the put reply`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    recorder.onSend = { command in
      guard command == "region put gta on" else { return }
      viewModel.regions = [
        RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
        RepeaterRegionEntry(name: "can", parentName: "*", depth: 1, floodAllowed: true, isHome: false),
        RepeaterRegionEntry(name: "on", parentName: "can", depth: 2, floodAllowed: true, isHome: false)
      ]
    }

    try await viewModel.addRegion(name: "gta", parent: .named("on"))

    #expect(viewModel.regions.last?.name == "gta")
    #expect(viewModel.regions.last?.parentName == "on")
    #expect(viewModel.regions.last?.depth == 3)
  }

  @Test
  func `saveRegions after a truncated refetch still persists unsaved edits`() async throws {
    let recorder = CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n"]
    let viewModel = Self.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    try await viewModel.addRegion(name: "Europe", parent: .unscoped)
    #expect(viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.regionsLoaded)

    recorder.repliesByCommand = ["region": "* F\n   gta F"]
    await viewModel.fetchRegions()

    #expect(!viewModel.regionsLoaded)
    #expect(viewModel.regionsError)
    #expect(viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.regions.map(\.name).contains("Europe"))

    recorder.resetCommands()
    recorder.repliesByCommand["region save"] = "OK"
    await viewModel.saveRegions()

    #expect(recorder.commands == ["region save"])
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `addRegion sends nothing when not loaded`() async {
    let recorder = CommandRecorder()
    let viewModel = Self.makeViewModel(recorder: recorder)

    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "Europe", parent: .unscoped)
    }
    #expect(recorder.commands.isEmpty)
  }
}

@Suite("RepeaterSettingsViewModel parse region tree")
@MainActor
struct RepeaterSettingsParseRegionTreeTests {
  @Test
  func `parseRegionTree keeps Ontario indent and parents`() {
    let dump = """
    * F
     can F
      on F
       gta F
       ottawa F
       hamilton F
    """ + "\n"
    let parsed = RepeaterSettingsViewModel.parseRegionTree(dump)
    #expect(parsed.map(\.name) == ["*", "can", "on", "gta", "ottawa", "hamilton"])
    #expect(parsed.map(\.parentName) == [nil, "*", "can", "on", "on", "on"])
    #expect(parsed.map(\.depth) == [0, 1, 2, 3, 3, 3])
    #expect(parsed.allSatisfy(\.floodAllowed))
  }

  @Test
  func `parseRegionTree strips caret and reads deny-flood`() {
    let dump = "* F\n on^ F\n  gta\n"
    let parsed = RepeaterSettingsViewModel.parseRegionTree(dump)
    #expect(parsed.map(\.name) == ["*", "on", "gta"])
    #expect(parsed[1].floodAllowed)
    #expect(!parsed[2].floodAllowed)
    #expect(parsed[2].parentName == "on")
  }

  @Test
  func `parseRegionTree strips factory Unscoped caret`() {
    let parsed = RepeaterSettingsViewModel.parseRegionTree("*^ F\n")
    #expect(parsed.map(\.name) == ["*"])
    #expect(parsed[0].floodAllowed)
    #expect(parsed[0].parentName == nil)
  }

  @Test
  func `parseRegionTree rejects skipped indent`() {
    let dump = "* F\n   gta F\n"
    #expect(RepeaterSettingsViewModel.parseRegionTree(dump).isEmpty)
  }

  @Test
  func `fetchRegions does not mark loaded on empty parse`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = ["region": "* F\n   gta F"]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(!viewModel.regionsLoaded)
    #expect(viewModel.regionsError)
  }

  @Test
  func `parseRegionTree rejects a space in the name`() {
    #expect(RepeaterSettingsViewModel.parseRegionTree("* F\n foo bar F\n").isEmpty)
  }

  @Test
  func `parseRegionTree CRLF dump matches LF`() {
    let lf = "* F\n on F\n"
    let crlf = "* F\r\n on F\r\n"
    #expect(
      RepeaterSettingsViewModel.parseRegionTree(crlf)
        == RepeaterSettingsViewModel.parseRegionTree(lf)
    )
  }

  @Test
  func `parseRegionTree single-line CRLF dump matches LF`() {
    #expect(
      RepeaterSettingsViewModel.parseRegionTree("* F\r\n")
        == RepeaterSettingsViewModel.parseRegionTree("* F\n")
    )
  }

  @Test
  func `parseRegionTree rejects a dump that does not end with a newline`() {
    #expect(RepeaterSettingsViewModel.parseRegionTree("* F\n on F").isEmpty)
  }

  /// One flood-allowed row: `<name> F\n`. `utf8Count` is the full dump size.
  private func lfTerminatedFloodDump(utf8Count: Int) -> String {
    let suffix = " F\n"
    let nameLen = utf8Count - suffix.utf8.count
    let dump = String(repeating: "a", count: nameLen) + suffix
    precondition(dump.utf8.count == utf8Count)
    precondition(dump.unicodeScalars.last == "\n")
    return dump
  }

  @Test
  func `parseRegionTree rejects a saturated newline-terminated dump`() {
    let dump = lfTerminatedFloodDump(
      utf8Count: RepeaterSettingsViewModel.firmwareRegionDumpMaxPayloadBytes
    )
    #expect(RepeaterSettingsViewModel.parseRegionTree(dump).isEmpty)
  }

  @Test
  func `parseRegionTree accepts a dump one byte under the firmware cap`() {
    let dump = lfTerminatedFloodDump(
      utf8Count: RepeaterSettingsViewModel.firmwareRegionDumpMaxPayloadBytes - 1
    )
    let parsed = RepeaterSettingsViewModel.parseRegionTree(dump)
    #expect(parsed.count == 1)
    #expect(parsed[0].floodAllowed)
  }

  @Test
  func `fetchRegions empty parse after a successful load unloads and does not wipe`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    #expect(viewModel.regionsLoaded)
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")

    recorder.repliesByCommand = ["region": "* F\n   gta F"]
    await viewModel.fetchRegions()

    #expect(!viewModel.regionsLoaded)
    #expect(viewModel.regionsError)
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeLoaded)
    #expect(viewModel.defaultScopeName == "duckburg")

    recorder.resetCommands()
    await #expect(throws: RepeaterSettingsViewModel.AddRegionError.rejected) {
      try await viewModel.addRegion(name: "gta", parent: .unscoped)
    }
    await viewModel.setDefaultScope(name: nil)
    await viewModel.removeRegion(name: "duckburg")
    await viewModel.toggleRegionFlood(name: "duckburg")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.regions.first { $0.name == "duckburg" }?.floodAllowed == true)
  }
}

@Suite("RepeaterSettingsViewModel default scope")
@MainActor
struct RepeaterSettingsDefaultScopeTests {
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
  func `fetchRegions does not send region default`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(recorder.commands == ["region"])
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `fetchDefaultScope reads the firmware default`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    recorder.resetCommands()

    await viewModel.fetchDefaultScope()

    #expect(recorder.commands == ["region default"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `fetchDefaultScope treats firmware null as no default scope`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n",
      "region default": " default scope is <null>"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    await viewModel.fetchDefaultScope()

    #expect(viewModel.defaultScopeName == nil)
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `unparsed default reply does not fail the regions section`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n",
      "region default": "OK"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)

    await viewModel.fetchRegions()

    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)

    await viewModel.fetchDefaultScope()

    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `unparsed default reply keeps the previous default scope`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()

    recorder.repliesByCommand["region default"] = "OK"
    await viewModel.fetchDefaultScope()

    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `default-scope timeout keeps the previous value and does not fail regions`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()

    recorder.errorsByCommand["region default"] = RemoteNodeError.timeout
    await viewModel.fetchDefaultScope()

    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.defaultScopeLoaded)
    #expect(!viewModel.regionsError)
  }

  @Test
  func `setDefaultScope sends nothing when not loaded`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)

    await viewModel.setDefaultScope(name: "duckburg")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.defaultScopeName == nil)
  }

  @Test
  func `setDefaultScope sends region default and does not mark unsaved`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg\n",
      "region default": " default scope is <null>",
      "region default duckburg": " default scope is now duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.setDefaultScope(name: "duckburg")

    #expect(recorder.commands == ["region default duckburg"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(!viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.regions.first { $0.name == "duckburg" }?.floodAllowed == true)
    #expect(viewModel.helper.errorMessage == nil)
  }

  @Test
  func `setDefaultScope nil sends firmware null token`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg",
      "region default <null>": " default scope is now <null>"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.setDefaultScope(name: nil)

    #expect(recorder.commands == ["region default <null>"])
    #expect(viewModel.defaultScopeName == nil)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `setDefaultScope non-matching reply leaves the current value`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg",
      "region default nope": "Err - unknown region"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.setDefaultScope(name: "nope")

    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion)
  }

  @Test
  func `setDefaultScope does not send wildcard`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.setDefaultScope(name: RepeaterRegionEntry.unscopedName)

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.defaultScopeName == "duckburg")
  }

  @Test
  func `setDefaultScope same value is a no-op`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.setDefaultScope(name: "duckburg")

    #expect(recorder.commands.isEmpty)
  }

  @Test
  func `removeRegion sends nothing when not loaded`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "duckburg", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    await viewModel.removeRegion(name: "duckburg")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
  }

  @Test
  func `toggleRegionFlood re-finds by name after the reply`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is <null>",
      "region denyf duckburg": "OK"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    recorder.resetCommands()

    recorder.onSend = { command in
      guard command == "region denyf duckburg" else { return }
      viewModel.regions = [
        RepeaterRegionEntry(
          name: "duckburg", parentName: "*", depth: 1, floodAllowed: true, isHome: false
        ),
        RepeaterRegionEntry(
          name: "other", parentName: "*", depth: 1, floodAllowed: true, isHome: false
        )
      ]
    }

    await viewModel.toggleRegionFlood(name: "duckburg")

    #expect(viewModel.regions.first { $0.name == "duckburg" }?.floodAllowed == false)
    #expect(viewModel.regions.first { $0.name == "other" }?.floodAllowed == true)
    #expect(viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `toggleRegionFlood does not trap when the row is gone after await`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is <null>",
      "region denyf duckburg": "OK"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()

    recorder.onSend = { command in
      guard command.hasPrefix("region denyf") else { return }
      viewModel.regions = [
        RepeaterRegionEntry(
          name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false
        )
      ]
    }

    await viewModel.toggleRegionFlood(name: "duckburg")

    #expect(viewModel.regions.map(\.name) == ["*"])
    #expect(viewModel.helper.errorMessage == nil)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `toggleRegionFlood sends nothing when not loaded`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    viewModel.regions = [
      RepeaterRegionEntry(name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false),
      RepeaterRegionEntry(name: "duckburg", parentName: "*", depth: 1, floodAllowed: true, isHome: false)
    ]

    await viewModel.toggleRegionFlood(name: "duckburg")

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.regions.first { $0.name == "duckburg" }?.floodAllowed == true)
    #expect(!viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `removeRegion of current default sends remove then default clear`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg",
      "region remove duckburg": "OK",
      "region default <null>": " default scope is now <null>"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

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
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg",
      "region remove duckburg": "OK",
      "region default <null>": "Err - save failed"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

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
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n",
      "region default": " default scope is duckburg",
      "region remove duckburg": "Err - not empty"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

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
    recorder.repliesByCommand = [
      "region": "* F\n duckburg F\n goosetown F\n",
      "region default": " default scope is duckburg",
      "region remove goosetown": "OK"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.removeRegion(name: "goosetown")

    #expect(recorder.commands == ["region remove goosetown"])
    #expect(viewModel.defaultScopeName == "duckburg")
    #expect(viewModel.regions.map(\.name) == ["*", "duckburg"])
  }

  @Test
  func `removeRegion of a parent maps not empty and does not drop the row`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n on F\n  gta F\n",
      "region default": " default scope is <null>",
      "region remove on": "Err - not empty"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    recorder.resetCommands()

    await viewModel.removeRegion(name: "on")

    #expect(recorder.commands == ["region remove on"])
    #expect(viewModel.regions.map(\.name) == ["*", "on", "gta"])
    #expect(viewModel.helper.errorMessage == L10n.RemoteNodes.RemoteNodes.Settings.Regions.notEmpty)
  }

  @Test
  func `removeRegion of nested non-default does not send region default`() async {
    let recorder = RepeaterSettingsRegionTests.CommandRecorder()
    recorder.repliesByCommand = [
      "region": "* F\n on F\n  gta F\n",
      "region default": " default scope is on",
      "region remove gta": "OK"
    ]
    let viewModel = RepeaterSettingsRegionTests.makeViewModel(recorder: recorder)
    await viewModel.fetchRegions()
    await viewModel.fetchDefaultScope()
    recorder.resetCommands()

    await viewModel.removeRegion(name: "gta")

    #expect(recorder.commands == ["region remove gta"])
    #expect(viewModel.defaultScopeName == "on")
  }
}
