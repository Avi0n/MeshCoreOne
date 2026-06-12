import Foundation
import MeshCore
import Testing

@testable import MC1
@testable import MC1Services

/// Verifies the trace listener targets the current connection's
/// `AdvertisementService`. The `ServiceContainer` is rebuilt on every
/// connection and finishes its event stream on teardown, so a listener
/// established once and never refreshed would keep iterating the torn-down
/// container's finished stream and silently drop every trace response.
@Suite("Trace Path Listener Resubscription")
@MainActor
struct TracePathListenerTests {

    private static let testTag: UInt32 = 0x00C0FFEE
    private static let pollAttempts = 200
    private static let pollIntervalMs = 5

    private func makeServices() throws -> ServiceContainer {
        ServiceContainer(
            session: MeshCoreSession(transport: MockTransport()),
            modelContainer: try PersistenceStore.createContainer(inMemory: true),
            radioID: UUID()
        )
    }

    private func makeTraceInfo(tag: UInt32) -> TraceInfo {
        TraceInfo(
            tag: tag,
            authCode: 0,
            flags: 0,
            pathLength: 1,
            path: [
                TraceNode(hash: 0xAB, snr: 5.0),
                TraceNode(hash: nil, snr: 3.0)
            ]
        )
    }

    /// Polls until the view model publishes a result or the deadline passes.
    private func waitForResult(on viewModel: TracePathViewModel) async -> Bool {
        for _ in 0..<Self.pollAttempts {
            if viewModel.result != nil { return true }
            try? await Task.sleep(for: .milliseconds(Self.pollIntervalMs))
        }
        return viewModel.result != nil
    }

    @Test("Listener established after a late connect receives trace responses")
    func lateConnectDeliversResponses() async throws {
        var currentServices: ServiceContainer?
        let viewModel = TracePathViewModel()
        viewModel.configure(advertisementService: { currentServices?.advertisementService })

        // Opened while disconnected: no services exist, so this subscribes to nothing.
        viewModel.startListening()

        // Connect: a fresh container appears and the hosting view re-invokes
        // startListening via its servicesVersion-keyed task.
        let services = try makeServices()
        currentServices = services
        viewModel.startListening()

        viewModel.setPendingTagForTesting(Self.testTag)
        services.advertisementService.eventBroadcaster.yield(
            .traceResponse(traceInfo: makeTraceInfo(tag: Self.testTag), radioID: UUID())
        )

        #expect(await waitForResult(on: viewModel), "Trace response after a late connect should produce a result")
        viewModel.stopListening()
    }

    @Test("Listener re-established after a container rebuild receives trace responses")
    func containerRebuildDeliversResponses() async throws {
        let oldServices = try makeServices()
        var currentServices: ServiceContainer? = oldServices

        let viewModel = TracePathViewModel()
        viewModel.configure(advertisementService: { currentServices?.advertisementService })
        viewModel.startListening()

        // Transport loss: the old container finishes its event stream and a
        // replacement container takes its place.
        oldServices.advertisementService.finishEvents()
        let newServices = try makeServices()
        currentServices = newServices
        viewModel.startListening()

        viewModel.setPendingTagForTesting(Self.testTag)
        newServices.advertisementService.eventBroadcaster.yield(
            .traceResponse(traceInfo: makeTraceInfo(tag: Self.testTag), radioID: UUID())
        )

        #expect(await waitForResult(on: viewModel), "Trace response after a rebuild should reach the re-subscribed listener")
        viewModel.stopListening()
    }
}
