import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("NodeCLIViewModel Tests")
@MainActor
struct NodeCLIViewModelTests {
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

  private func makeViewModel(spy: SendSpy) -> NodeCLIViewModel {
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: "TestNode", sendRawCommand: spy.closure())
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
    viewModel.configure(sessionName: "TestNode", sendRawCommand: SendSpy().closure())
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
    try await waitUntil("help output appears") { !viewModel.outputLines.isEmpty }
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
    try await waitUntil("A sent") { spyA.calls.contains { $0.command == "ver" } }

    #expect(spyB.calls.isEmpty)
    #expect(spyA.calls.allSatisfy { $0.command == "ver" })
  }

  @Test
  func `Output is capped at 1000 lines`() {
    let viewModel = NodeCLIViewModel()
    viewModel.configure(sessionName: "N", sendRawCommand: { _, _ in "" })
    for i in 0..<1001 {
      viewModel.appendOutput("line\(i)", type: .response)
    }
    #expect(viewModel.outputLines.count == 1000)
  }

  @Test
  func `History is capped at 100 entries`() {
    let viewModel = makeViewModel(spy: SendSpy())
    for i in 0..<150 {
      viewModel.executeCommand("command\(i)")
    }
    for _ in 0..<100 {
      viewModel.historyUp()
    }
    #expect(viewModel.currentInput == "command50")
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
}
