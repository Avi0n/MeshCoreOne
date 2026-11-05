import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("NodeCLIViewModel Tests")
@MainActor
struct NodeCLIViewModelTests {
  @Test
  func `sensor host timeout is an error and does not claim reboot was sent`() async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.timeout
    let model = makeViewModel(spy: spy, remoteTarget: .sensor)
    model.executeCommand("reboot")
    try await waitUntil("sensor command should finish") { !model.isWaitingForResponse }
    #expect(spy.calls.count == 1)
    #expect(model.outputLines.contains { $0.type == .error })
    #expect(!model.outputLines.contains { $0.type == .success })
  }

  @Test
  func `sensor no reply submission cannot be cancelled and duplicated`() async throws {
    let send = HeldSend()
    let model = NodeCLIViewModel()
    model.configure(sessionName: "Sensor", remoteTarget: .sensor, sendRawCommand: send.send)
    model.executeCommand("rebootx")
    try await waitUntil("host acceptance should be pending") { send.commands.count == 1 }
    #expect(!model.canCancelCurrentCommand)
    model.cancelCurrentCommand()
    model.executeCommand("rebootx")
    #expect(model.isWaitingForResponse)
    #expect(send.commands == ["rebootx"])
    send.finish("rebootx", with: .success(""))
    try await waitUntil("accepted submission should finish") { !model.isWaitingForResponse }
    #expect(model.outputLines.contains { $0.text == L10n.RemoteNodes.RemoteNodes.Sensor.rebootSent })
  }

  /// Records the commands/timeouts passed to the injected send closure and
  /// returns a configurable result (or throws a configurable error).
  @MainActor
  final class SendSpy {
    private(set) var calls: [(command: String, timeout: Duration)] = []
    var result: String = "OK"
    var error: Error?

    func closure() -> (@MainActor (String, Duration) async throws -> String) {
      { [self] command, timeout in
        calls.append((command, timeout))
        if let error { throw error }
        return result
      }
    }
  }

  @MainActor
  private final class HeldSend {
    private(set) var commands: [String] = []
    private(set) var finishedCommands: [String] = []
    private var continuations: [String: CheckedContinuation<String, Error>] = [:]

    func send(_ command: String, timeout: Duration) async throws -> String {
      commands.append(command)
      defer { finishedCommands.append(command) }
      return try await withCheckedThrowingContinuation { continuations[command] = $0 }
    }

    func finish(_ command: String, with result: Result<String, Error>) {
      continuations.removeValue(forKey: command)?.resume(with: result)
    }

    func finishAll() {
      let pending = continuations.values
      continuations.removeAll()
      for continuation in pending {
        continuation.resume(throwing: CancellationError())
      }
    }
  }

  private func makeViewModel(
    spy: SendSpy,
    sessionName: String = "TestNode",
    remoteTarget: CLIRemoteTarget = .repeater
  ) -> NodeCLIViewModel {
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: sessionName, remoteTarget: remoteTarget, sendRawCommand: spy.closure())
    return viewModel
  }

  @Test
  func `Configure shows a banner naming the node`() {
    let viewModel = makeViewModel(spy: SendSpy())
    let output = viewModel.outputLines.map(\.text).joined(separator: "\n")
    #expect(output.contains("TestNode"))
  }

  @Test
  func `configure is idempotent: re-configuring does not duplicate the banner`() {
    let viewModel = makeViewModel(spy: SendSpy())
    let lineCountAfterFirstConfigure = viewModel.outputLines.count
    viewModel.configure(sessionName: "TestNode", remoteTarget: .repeater, sendRawCommand: SendSpy().closure())
    #expect(viewModel.outputLines.count == lineCountAfterFirstConfigure)
  }

  @Test
  func `help prints help and does not send`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("help")
    try await waitUntil("help output appears") {
      viewModel.outputLines.contains { $0.text.contains("Available commands") }
    }
    #expect(spy.calls.isEmpty)
  }

  @Test
  func `bare clear empties output and does not send`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("help")
    try await waitUntil("help output appears") {
      viewModel.outputLines.contains { $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.helpHeader }
        && !viewModel.isWaitingForResponse
    }
    viewModel.executeCommand("clear")
    try await waitUntil("output cleared") { viewModel.outputLines.isEmpty }
    #expect(spy.calls.isEmpty)
  }

  @Test
  func `clear stats sends the raw command`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("clear stats")
    try await waitUntil("send recorded") { spy.calls.contains { $0.command == "clear stats" } }
  }

  @Test
  func `reboot sends with a 2s timeout and renders timeout as success`() async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.timeout
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("reboot")
    try await waitUntil("reboot acknowledged as success") {
      viewModel.outputLines.contains { $0.type == .success }
    }
    let call = try #require(spy.calls.first { $0.command == "reboot" })
    #expect(call.timeout == .seconds(2))
  }

  @Test
  func `reboot now is treated as a reboot`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("reboot now")
    try await waitUntil("reboot now sent with 2s timeout") {
      spy.calls.contains { $0.command == "reboot now" && $0.timeout == .seconds(2) }
    }
  }

  @Test
  func `a reboot typo is sent as a normal command, not treated as a reboot`() async throws {
    let spy = SendSpy()
    spy.result = "Unknown command"
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("rebootnow")
    try await waitUntil("typo sent with the default command timeout") {
      spy.calls.contains { $0.command == "rebootnow" && $0.timeout == .seconds(10) }
    }
    #expect(viewModel.outputLines.contains { $0.text == "Unknown command" && $0.type == .response })
    #expect(!viewModel.outputLines.contains { $0.type == .success })
  }

  @Test
  func `a cancelled reboot does not surface a raw CancellationError`() async throws {
    let spy = SendSpy()
    spy.error = CancellationError()
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("reboot")
    try await waitUntil("reboot dispatched") { spy.calls.contains { $0.command == "reboot" } }
    #expect(!viewModel.outputLines.contains { $0.type == .error })
    #expect(!viewModel.outputLines.contains { $0.type == .success })
  }

  @Test
  func `arbitrary command sends with default timeout and appends response`() async throws {
    let spy = SendSpy()
    spy.result = "af: 1"
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("get af")
    try await waitUntil("response appended") {
      viewModel.outputLines.contains { $0.text == "af: 1" && $0.type == .response }
    }
    let call = try #require(spy.calls.first { $0.command == "get af" })
    #expect(call.timeout == .seconds(10))
  }

  @Test
  func `a thrown error appends an .error line`() async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.permissionDenied
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("set tx 22")
    try await waitUntil("error line appended") {
      viewModel.outputLines.contains { $0.type == .error }
    }
  }

  @Test
  func `Two view models with two closures each only invoke their own`() async throws {
    let spyA = SendSpy()
    let spyB = SendSpy()
    let viewModelA = makeViewModel(spy: spyA)
    let viewModelB = makeViewModel(spy: spyB)

    viewModelA.executeCommand("ver")
    viewModelB.executeCommand("reboot")
    try await waitUntil("A sent") { spyA.calls.contains { $0.command == "ver" } }
    try await waitUntil("B sent") { spyB.calls.contains { $0.command == "reboot" } }

    #expect(spyA.calls.allSatisfy { $0.command == "ver" })
    #expect(spyB.calls.allSatisfy { $0.command == "reboot" })
  }

  @Test
  func `Output is capped at 1000 lines`() {
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: "N", remoteTarget: .repeater, sendRawCommand: { _, _ in "" })
    for i in 0..<1001 {
      viewModel.appendOutput("line\(i)", type: .response)
    }
    #expect(viewModel.outputLines.count == 1000)
  }

  @Test
  func `History is capped at 100 entries`() async throws {
    let viewModel = makeViewModel(spy: SendSpy())
    for i in 0..<150 {
      viewModel.executeCommand("command\(i)")
      try await waitUntil("command completed") { !viewModel.isWaitingForResponse }
    }
    for _ in 0..<100 {
      viewModel.historyUp()
    }
    #expect(viewModel.currentInput == "command50")
  }

  @Test
  func `poweroff timeout prints the power off sent line`() async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.timeout
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("poweroff")
    let sent = L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
    try await waitUntil("poweroff acknowledged as success") {
      viewModel.outputLines.contains { $0.text == sent && $0.type == .success }
    }
    let call = try #require(spy.calls.first { $0.command == "poweroff" })
    #expect(call.timeout == .seconds(2))
  }

  @Test
  func `an empty poweroff reply prints the sent line`() async throws {
    let spy = SendSpy()
    spy.result = ""
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("poweroff")
    let sent = L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
    try await waitUntil("empty poweroff reply reads as sent") {
      viewModel.outputLines.contains { $0.text == sent && $0.type == .success }
    }
  }

  @Test
  func `a repeater reboot with a body prints the body`() async throws {
    let spy = SendSpy()
    spy.result = "busy"
    let viewModel = makeViewModel(spy: spy)
    viewModel.executeCommand("reboot")
    try await waitUntil("reboot body printed") {
      viewModel.outputLines.contains { $0.text == "busy" && $0.type == .response }
    }
    #expect(!viewModel.outputLines.contains { $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.rebootSent })
  }

  @Test
  func `radio reboot now prints the reply on the normal timeout`() async throws {
    let spy = SendSpy()
    spy.result = "Unknown command"
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.executeCommand("reboot now")
    try await waitUntil("unknown command printed") {
      viewModel.outputLines.contains { $0.text == "Unknown command" && $0.type == .response }
    }
    let call = try #require(spy.calls.first { $0.command == "reboot now" })
    #expect(call.timeout == .seconds(10))
    #expect(!viewModel.outputLines.contains { $0.type == .success })
  }

  @Test
  func `radio configure shows the radio banner`() {
    let viewModel = makeViewModel(spy: SendSpy(), remoteTarget: .radio)
    let banner = L10n.RemoteNodes.RemoteNodes.NodeCli.radioBanner("TestNode")
    #expect(viewModel.outputLines.contains { $0.text == banner })
    #expect(viewModel.outputLines.contains { $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.bannerHint })
    #expect(!viewModel.outputLines.contains {
      $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.bannerConnected("TestNode")
    })
  }

  @Test
  func `radio command timeout prints the no reply line`() async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.timeout
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.executeCommand("get name")
    let line = L10n.RemoteNodes.RemoteNodes.NodeCli.radioNoReply("TestNode")
    try await waitUntil("no reply line printed") {
      viewModel.outputLines.contains { $0.text == line && $0.type == .error }
    }
  }

  @Test
  func `getResponseBlock walks the full multi-line response block, not just one line`() async throws {
    let viewModel = makeViewModel(spy: SendSpy())
    viewModel.executeCommand("help")
    try await waitUntil("help output appears") {
      viewModel.outputLines.contains { $0.text.contains("Available commands") }
    }
    let helpLine = try #require(viewModel.outputLines.first { $0.text.contains("Available commands") })
    let block = viewModel.getResponseBlock(containing: helpLine)
    // Spanning the header through a later help line proves the walk crosses
    // multiple .response entries, not just the single line passed in.
    #expect(block.contains("Available commands"))
    #expect(block.contains("Reset node statistics"))
  }

  @Test(arguments: [
    ("get wifi.ss", "get wifi.ssid"),
    ("set wifi.cl", "set wifi.clear"),
    ("pow", "poweroff"),
    ("set multi.acks 1", "set multi.acks 1")
  ])
  func `completed radio commands submit without an added argument`(_ input: String, _ expected: String) async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.currentInput = input

    viewModel.tabComplete()
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("completed command submitted") { !spy.calls.isEmpty && !viewModel.isWaitingForResponse }

    #expect(spy.calls.map(\.command) == [expected])
    #expect(viewModel.commandHistory.last == expected)
  }

  @Test
  func `a selected radio completion submits without an added argument`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.currentInput = "get wifi."
    let suggestions = try #require(viewModel.tabComplete())
    viewModel.tabSelectionIndex = try #require(suggestions.firstIndex(of: "wifi.ssid"))

    #expect(viewModel.applySelectedSuggestion())
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("selected command submitted") { !spy.calls.isEmpty }

    #expect(spy.calls.map(\.command) == ["get wifi.ssid"])
  }

  @Test(arguments: [
    ("ge", "wifi.ss", "get wifi.ssid"),
    ("set", "wifi.pw", "set wifi.pwd ")
  ])
  func `radio namespaces keep the separator needed to complete a key`(
    _ verb: String,
    _ key: String,
    _ expected: String
  ) async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.currentInput = verb

    viewModel.tabComplete()
    viewModel.currentInput += key
    viewModel.tabComplete()
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("completed namespace submitted") { !spy.calls.isEmpty }

    #expect(spy.calls.map(\.command) == [expected])
  }

  @Test
  func `radio app command completion keeps its separator and stays local`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.currentInput = "hel"

    viewModel.tabComplete()
    #expect(viewModel.currentInput == "help ")
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("completed help displayed") {
      viewModel.outputLines.contains { $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.helpHeader }
    }

    #expect(spy.calls.isEmpty)
  }

  @Test(arguments: [
    ("set wifi.pw", "secret  ", "set wifi.pwd secret  "),
    ("set wifi.ss", " my net  ", "set wifi.ssid  my net  ")
  ])
  func `completing a radio setter preserves the entered value`(
    _ input: String,
    _ value: String,
    _ expected: String
  ) async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.currentInput = input

    viewModel.tabComplete()
    viewModel.currentInput += value
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("setter submitted") { !spy.calls.isEmpty }

    #expect(spy.calls.map(\.command) == [expected])
  }

  @Test
  func `repeater completion keeps its separator and trimmed forwarding`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .repeater)
    viewModel.currentInput = "get af"

    viewModel.tabComplete()
    #expect(viewModel.currentInput == "get af ")
    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("remote command submitted") { !spy.calls.isEmpty }

    #expect(spy.calls.map(\.command) == ["get af"])
  }

  @Test(arguments: [
    "set wifi.pwd ",
    "set wifi.pwd secret  ",
    "set wifi.ssid  my net  ",
    "set wifi.ssid café"
  ])
  func `radio sends echoes and recalls the submitted line`(_ line: String) async throws {
    let spy = SendSpy()
    spy.result = "OK"
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)

    viewModel.executeCommand(line)
    try await waitUntil("line settled") {
      spy.calls.contains { $0.command == line } && !viewModel.isWaitingForResponse
    }

    let echoed = try #require(viewModel.outputLines.last { $0.type == .command })
    #expect(viewModel.getResponseBlock(containing: echoed) == line)
    viewModel.historyUp()
    #expect(viewModel.currentInput == line)

    viewModel.executeCommand(viewModel.currentInput)
    try await waitUntil("line replayed") { spy.calls.filter { $0.command == line }.count == 2 }
  }

  @Test
  func `radio whitespace and spaced app commands do not send`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)

    viewModel.executeCommand("   ")
    viewModel.executeCommand("  help  ")
    try await waitUntil("help output appears") {
      viewModel.outputLines.contains { $0.text.contains("Available commands") }
    }
    viewModel.executeCommand("  clear  ")

    #expect(spy.calls.isEmpty)
    viewModel.historyUp()
    #expect(viewModel.currentInput == "clear")
    viewModel.historyUp()
    #expect(viewModel.currentInput == "help")
  }

  @Test
  func `repeater and room forwarding still trims and matches no-reply commands`() async throws {
    let repeater = SendSpy()
    repeater.error = RemoteNodeError.timeout
    let repeaterModel = makeViewModel(spy: repeater, remoteTarget: .repeater)
    repeaterModel.executeCommand("poweroff ")
    try await waitUntil("repeater poweroff settled") {
      repeater.calls.contains { $0.command == "poweroff" } && !repeaterModel.isWaitingForResponse
    }
    repeaterModel.executeCommand("set wifi.pwd ")
    try await waitUntil("repeater commands sent") { repeater.calls.count == 2 }
    #expect(repeater.calls.map(\.command) == ["poweroff", "set wifi.pwd"])
    #expect(repeater.calls[0].timeout == .seconds(2))
    #expect(repeater.calls[1].timeout == .seconds(10))
    #expect(repeaterModel.outputLines.contains {
      $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent && $0.type == .success
    })

    let room = SendSpy()
    room.error = RemoteNodeError.timeout
    let roomModel = makeViewModel(spy: room, remoteTarget: .room)
    roomModel.executeCommand("reboot ")
    try await waitUntil("room reboot sent") { room.calls.contains { $0.command == "reboot" } }
    #expect(room.calls[0].timeout == .seconds(2))
    #expect(roomModel.outputLines.contains {
      $0.text == L10n.RemoteNodes.RemoteNodes.NodeCli.rebootSent && $0.type == .success
    })
  }

  @Test(arguments: radioNoReplyLines)
  func `radio empty reply follows the transmitted line`(_ sample: RadioNoReplyLine) async throws {
    let spy = SendSpy()
    spy.result = ""
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.executeCommand(sample.line)
    try await waitUntil("empty reply settled") { !viewModel.isWaitingForResponse && !spy.calls.isEmpty }
    let call = try #require(spy.calls.first)
    #expect(call.command == sample.line)
    #expect(call.timeout == (sample.kind == .ordinary ? .seconds(10) : .seconds(2)))
    if let sent = sample.kind.sentText {
      #expect(viewModel.outputLines.contains { $0.text == sent && $0.type == .success })
    } else {
      #expect(!viewModel.outputLines.contains { $0.type == .success })
    }
  }

  @Test(arguments: radioNoReplyLines)
  func `radio timeout follows the transmitted line`(_ sample: RadioNoReplyLine) async throws {
    let spy = SendSpy()
    spy.error = RemoteNodeError.timeout
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.executeCommand(sample.line)
    try await waitUntil("timeout settled") { !viewModel.isWaitingForResponse && !spy.calls.isEmpty }
    let call = try #require(spy.calls.first)
    #expect(call.command == sample.line)
    #expect(call.timeout == (sample.kind == .ordinary ? .seconds(10) : .seconds(2)))
    if let sent = sample.kind.sentText {
      #expect(viewModel.outputLines.contains { $0.text == sent && $0.type == .success })
    } else {
      let noReply = L10n.RemoteNodes.RemoteNodes.NodeCli.radioNoReply("TestNode")
      #expect(viewModel.outputLines.contains { $0.text == noReply && $0.type == .error })
      #expect(!viewModel.outputLines.contains { $0.type == .success })
    }
  }

  @Test(arguments: radioNoReplyLines)
  func `radio reply body follows the transmitted line`(_ sample: RadioNoReplyLine) async throws {
    let spy = SendSpy()
    spy.result = "still on"
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    viewModel.executeCommand(sample.line)
    try await waitUntil("body printed") {
      viewModel.outputLines.contains { $0.text == "still on" && $0.type == .response }
    }
    let call = try #require(spy.calls.first)
    #expect(call.command == sample.line)
    #expect(call.timeout == (sample.kind == .ordinary ? .seconds(10) : .seconds(2)))
    #expect(!viewModel.outputLines.contains { $0.type == .success })
    if let sent = sample.kind.sentText {
      #expect(!viewModel.outputLines.contains { $0.text == sent })
    }
  }

  @Test(arguments: ["get name", "reboot"])
  func `cancelled command cleanup preserves the next command ownership`(_ command: String) async throws {
    let heldSend = HeldSend()
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: "TestNode", remoteTarget: .radio, sendRawCommand: heldSend.send)
    defer {
      viewModel.reset()
      heldSend.finishAll()
    }

    viewModel.executeCommand(command)
    try await waitUntil("first command started") { heldSend.commands == [command] }
    viewModel.cancelCurrentCommand()
    viewModel.executeCommand("board")
    try await waitUntil("next command started") { heldSend.commands == [command, "board"] }

    heldSend.finish(command, with: .failure(CancellationError()))
    try await waitUntil("cancelled send returned") { heldSend.finishedCommands == [command] }
    #expect(viewModel.isWaitingForResponse)

    viewModel.executeCommand("ver")
    #expect(viewModel.commandHistory == [command, "board"])
    viewModel.cancelCurrentCommand()
    #expect(!viewModel.isWaitingForResponse)
    let cancelled = L10n.RemoteNodes.RemoteNodes.NodeCli.cancelled
    #expect(viewModel.outputLines.filter { $0.text == cancelled && $0.type == .error }.count == 2)

    heldSend.finish("board", with: .failure(CancellationError()))
    try await waitUntil("next cancelled send returned") { heldSend.finishedCommands.count == 2 }
    #expect(heldSend.commands == [command, "board"])
  }

  @Test
  func `same-turn duplicate submit is rejected and immediate cancel prevents sending`() async throws {
    let spy = SendSpy()
    let viewModel = makeViewModel(spy: spy, remoteTarget: .radio)
    let command = "get name"

    viewModel.executeCommand(command)
    #expect(viewModel.isWaitingForResponse)
    viewModel.executeCommand("board")
    #expect(viewModel.commandHistory == [command])
    viewModel.cancelCurrentCommand()
    viewModel.cancelCurrentCommand()
    #expect(!viewModel.isWaitingForResponse)
    let cancelled = L10n.RemoteNodes.RemoteNodes.NodeCli.cancelled
    #expect(viewModel.outputLines.filter { $0.text == cancelled && $0.type == .error }.count == 1)

    viewModel.executeCommand("ver")
    try await waitUntil("next command completed") {
      spy.calls.contains { $0.command == "ver" } && !viewModel.isWaitingForResponse
    }
    #expect(spy.calls.map(\.command) == ["ver"])
    #expect(viewModel.commandHistory == [command, "ver"])
  }

  @Test(arguments: ["get name", "reboot"], [
    Result<String, RemoteNodeError>.success(""),
    .success("stale reply"),
    .failure(.timeout),
    .failure(.permissionDenied)
  ])
  func `cancelled commands suppress late responses and errors`(
    _ command: String,
    _ result: Result<String, RemoteNodeError>
  ) async throws {
    let heldSend = HeldSend()
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: "TestNode", remoteTarget: .radio, sendRawCommand: heldSend.send)
    defer {
      viewModel.reset()
      heldSend.finishAll()
    }

    viewModel.executeCommand(command)
    try await waitUntil("command started") { heldSend.commands == [command] }
    viewModel.cancelCurrentCommand()
    let linesAfterCancellation = viewModel.outputLines.map(\.text)

    heldSend.finish(command, with: result.mapError { $0 as Error })
    try await waitUntil("cancelled send returned") { heldSend.finishedCommands == [command] }

    #expect(viewModel.outputLines.map(\.text) == linesAfterCancellation)
    #expect(!viewModel.isWaitingForResponse)
  }
}

struct RadioNoReplyLine: Sendable {
  var line: String
  var kind: RadioNoReplyKind
}

enum RadioNoReplyKind: Sendable {
  case powerOff
  case reboot
  case ordinary

  var sentText: String? {
    switch self {
    case .powerOff:
      L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
    case .reboot:
      L10n.RemoteNodes.RemoteNodes.NodeCli.rebootSent
    case .ordinary:
      nil
    }
  }
}

private let radioNoReplyLines: [RadioNoReplyLine] = [
  RadioNoReplyLine(line: "poweroff", kind: .powerOff),
  RadioNoReplyLine(line: "shutdown", kind: .powerOff),
  RadioNoReplyLine(line: "reboot", kind: .reboot),
  RadioNoReplyLine(line: "poweroff ", kind: .ordinary),
  RadioNoReplyLine(line: "POWEROFF", kind: .ordinary),
  RadioNoReplyLine(line: "poweroff now", kind: .ordinary)
]
