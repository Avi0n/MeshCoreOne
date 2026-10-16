import Foundation
@testable import MC1
import Testing

@Suite("CLI session selection")
@MainActor
struct CLISessionSelectionTests {
  @Test
  func `minimum integer session number reports an error without changing the active session`() async throws {
    let viewModel = CLIToolViewModel()
    let activeSession = CLISession.local(deviceName: "Test radio")
    viewModel.activeSession = activeSession

    viewModel.executeCommand("session \(Int.min)")
    try await waitUntil("Session command should finish") { !viewModel.isWaitingForResponse }

    #expect(viewModel.activeSession == activeSession)
    let output = try #require(viewModel.outputLines.last)
    #expect(output.type == .error)
    #expect(output.text == "\(L10n.Tools.Tools.Cli.sessionNotFound) \(Int.min)")
  }

  @Test(arguments: [Int.min, Int.min + 1, -1, 0, 4, Int.max], ["session ", "s"])
  func `invalid numeric sessions preserve the active remote session`(number: Int, commandPrefix: String) async throws {
    let viewModel = makeViewModel()
    let activeSession = try #require(viewModel.remoteSessions.last)
    viewModel.activeSession = activeSession

    viewModel.executeCommand("\(commandPrefix)\(number)")
    try await waitUntil("Session command should finish") { !viewModel.isWaitingForResponse }

    #expect(viewModel.activeSession == activeSession)
    let output = try #require(viewModel.outputLines.last)
    #expect(output.type == .error)
    #expect(output.text == "\(L10n.Tools.Tools.Cli.sessionNotFound) \(number)")
  }

  @Test(arguments: [
    ("session 1", "Test radio"), ("s1", "Test radio"),
    ("session 2", "Alpha"), ("s2", "Alpha"),
    ("session 3", "Bravo"), ("s3", "Bravo"),
    ("session local", "Test radio"),
    ("session bravo", "Bravo"), ("session ALPHA", "Alpha")
  ])
  func `valid session selectors keep their existing mapping`(command: String, expectedName: String) async throws {
    let viewModel = makeViewModel()
    viewModel.activeSession = viewModel.remoteSessions.last

    viewModel.executeCommand(command)
    try await waitUntil("Session command should finish") { !viewModel.isWaitingForResponse }

    let activeSession = try #require(viewModel.activeSession)
    #expect(activeSession.name == expectedName)
    if expectedName == viewModel.localDeviceName {
      #expect(activeSession.isLocal)
    } else {
      #expect(activeSession == viewModel.remoteSessions.first { $0.name == expectedName })
    }
    #expect(viewModel.outputLines.last?.type == .success)
  }

  private func makeViewModel() -> CLIToolViewModel {
    let viewModel = CLIToolViewModel()
    viewModel.localDeviceName = "Test radio"
    viewModel.remoteSessions = [
      .remote(id: UUID(), name: "Alpha", pathLength: 1),
      .remote(id: UUID(), name: "Bravo", pathLength: 2)
    ]
    return viewModel
  }
}
