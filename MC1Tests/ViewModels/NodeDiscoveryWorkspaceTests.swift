import Foundation
@testable import MC1
import Testing

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
}
