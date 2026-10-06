import Foundation
@testable import MC1
import MeshCore
import Testing

@MainActor
private final class NameResolutionGate {
  private(set) var isWaiting = false
  private var continuation: CheckedContinuation<Void, Never>?

  func wait() async {
    isWaiting = true
    await withCheckedContinuation { continuation = $0 }
  }

  func resume() {
    continuation?.resume()
    continuation = nil
  }
}

@Suite("Node Discovery workspace lifetime")
@MainActor
struct NodeDiscoveryWorkspaceTests {
  @Test
  func `inactive does not stop the scan`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    #expect(viewModel.isScanning)

    viewModel.noteWorkspaceVisible(false)

    #expect(viewModel.isScanning)
  }

  @Test
  func `reset stops the scan and clears results`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    viewModel.errorMessage = "scan failed"

    viewModel.reset()

    #expect(viewModel.isScanning == false)
    #expect(viewModel.results.isEmpty)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `foreground return finishes a scan whose deadline has passed`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: .distantPast)
    viewModel.expireScanIfDeadlinePassed()

    #expect(viewModel.isScanning == false)
  }

  @Test
  func `scan sends on the session it started with`() async throws {
    let prepared = try await prepareScan(seedName: nil)
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "started session should be asked to send") {
      await prepared.transport.sentData.count == 1
    }

    #expect(prepared.viewModel.isScanning)
    prepared.viewModel.stopScan()
  }

  @Test
  func `nil session ends the scan and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    prepared.box.session = nil
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "scan should end") {
      prepared.viewModel.isScanning == false
    }

    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
  }

  @Test
  func `replaced session ends the scan and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    let replacementTransport = MockTransport()
    try await replacementTransport.connect()
    prepared.box.session = MeshCoreSession(transport: replacementTransport)
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "scan should end") {
      prepared.viewModel.isScanning == false
    }

    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
    #expect(await replacementTransport.sentData.isEmpty)
  }

  @Test
  func `cancelled scan ends and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    prepared.viewModel.stopScan()
    prepared.gate.resume()
    try await Task.sleep(for: .milliseconds(50))

    #expect(prepared.viewModel.isScanning == false)
    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
  }

  private final class SessionBox {
    var session: MeshCoreSession?
  }

  private struct PreparedScan {
    let viewModel: NodeDiscoveryViewModel
    let transport: MockTransport
    let box: SessionBox
    let gate: NameResolutionGate
  }

  private func prepareScan(seedName: String?) async throws -> PreparedScan {
    let transport = MockTransport()
    try await transport.connect()
    let box = SessionBox()
    box.session = MeshCoreSession(transport: transport)
    let viewModel = NodeDiscoveryViewModel()
    viewModel.configure(dependencies: NodeDiscoveryViewModel.Dependencies(
      session: { box.session },
      dataStore: { nil },
      radioID: { UUID() },
      contactService: { nil },
      maxContacts: { nil }
    ))
    if let seedName {
      viewModel.results = [
        NodeDiscoveryResult(
          name: seedName,
          publicKey: Data(repeating: 0x11, count: 32),
          nodeType: 0x04,
          snr: 5,
          snrIn: 4,
          rssi: -80,
          scanFilter: .repeaters,
          receivedAt: Date()
        )
      ]
    }
    let gate = NameResolutionGate()
    viewModel.onNameResolutionFinishedForTesting = { await gate.wait() }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "name resolution should suspend") {
      gate.isWaiting
    }
    return PreparedScan(viewModel: viewModel, transport: transport, box: box, gate: gate)
  }
}
