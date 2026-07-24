import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// Covers the airtime-free recovery of late CLI replies: a command that timed
/// out is remembered, and its belated answer is adopted instead of refetched.
@Suite("NodeSettingsViewModel late reply recovery")
@MainActor
struct NodeSettingsLateRecoveryTests {
  private func makeViewModel(
    responses: [String: Result<String, Error>]
  ) -> NodeSettingsViewModel {
    let viewModel = NodeSettingsViewModel()
    let send: (UUID, String, Duration) async throws -> String = { _, command, _ in
      switch responses[command] {
      case let .success(response): return response
      case let .failure(error): throw error
      case nil: throw RemoteNodeError.timeout
      }
    }
    viewModel.configure(
      session: RemoteNodeSessionDTO(
        radioID: UUID(),
        publicKey: Data(repeating: 0xCC, count: 32),
        name: "TestNode",
        role: .repeater
      ),
      sendCommand: send,
      sendRawCommand: send
    )
    return viewModel
  }

  @Test
  func `belated get radio reply is recovered after its command timed out`() async {
    let viewModel = makeViewModel(responses: [:])

    await viewModel.fetchRadioSettings()
    #expect(viewModel.frequency == nil)
    #expect(viewModel.bandwidth == nil)
    #expect(viewModel.spreadingFactor == nil)
    #expect(viewModel.codingRate == nil)
    #expect(viewModel.radioError)
    #expect(!viewModel.radioLoaded)

    viewModel.handleCommonLateResponse("> 915.000,250.0,10,5")
    #expect(viewModel.frequency == 915.0)
    #expect(viewModel.bandwidth == 250.0)
    #expect(viewModel.spreadingFactor == 10)
    #expect(viewModel.codingRate == 5)
    #expect(!viewModel.radioError)
    #expect(viewModel.radioLoaded)
  }

  @Test
  func `mesh duplicate of an answered reply is not recovered for another query`() async {
    let viewModel = makeViewModel(responses: [
      "get name": .success("Alpha Repeater"),
      "get lat": .success("38.5"),
    ])

    await viewModel.fetchIdentity()
    #expect(viewModel.latitude == 38.5)
    #expect(viewModel.longitude == nil)

    // A flood-routed duplicate of the latitude reply must not become the longitude.
    viewModel.handleCommonLateResponse("38.5")
    #expect(viewModel.longitude == nil)

    viewModel.handleCommonLateResponse("-122.4")
    #expect(viewModel.longitude == -122.4)
    #expect(!viewModel.identityError)
  }

  @Test
  func `a bare double is not recovered while both coordinates are unanswered`() async {
    let viewModel = makeViewModel(responses: [
      "get name": .success("Alpha Repeater"),
    ])

    await viewModel.fetchIdentity()
    #expect(viewModel.identityError)

    viewModel.handleCommonLateResponse("38.5")
    #expect(viewModel.latitude == nil)
    #expect(viewModel.longitude == nil)
    #expect(viewModel.identityError)
  }
}
