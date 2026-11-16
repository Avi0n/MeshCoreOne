import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Trace Path saved request snapshot", .timeLimit(.minutes(1)))
@MainActor
struct TracePathSaveSnapshotTests {
  @Test(arguments: [false, true])
  func `equal path bytes with different hop widths cannot change the saved trace`(isBatch: Bool) async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let device = DeviceDTO(from: Device(
      radioID: radioID,
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Local radio",
      firmwareVersion: 9
    ))
    let originalPath = Data([0xAB, 0xCD])
    let original = makeContact(prefix: [0xAB, 0xCD], radioID: radioID)
    let additional = makeContact(prefix: [0xCD], radioID: radioID)
    let clock = TestClock()
    let viewModel = TracePathViewModel(clock: clock)
    defer { viewModel.reset() }
    viewModel.configure(dependencies: .init(
      dataStore: { store },
      session: { nil },
      advertisementService: { nil },
      connectedDevice: { device },
      bestAvailableLocation: { nil }
    ))
    viewModel.setContactsForTesting([original, additional])
    viewModel.setTraceHashMode(1)
    viewModel.addNode(original)
    viewModel.batchEnabled = isBatch
    viewModel.batchSize = 1
    viewModel.responseTimeoutSecondsForTesting = 30

    var submissions: [(tag: UInt32, flags: UInt8, path: Data)] = []
    viewModel.sendTraceForTesting = { tag, flags, path in
      submissions.append((tag, flags, path))
      return MessageSentInfo(route: 1, expectedAck: Data(), suggestedTimeoutMs: 1000)
    }
    viewModel.startTrace()
    let execution = try #require(viewModel.executionTaskForTesting)
    try await waitUntil("the original trace should be waiting") { clock.sleeperCount == 1 }
    let submitted = try #require(submissions.first)
    #expect(submitted.flags == 1)
    #expect(submitted.path == originalPath)

    viewModel.setTraceHashMode(0)
    viewModel.autoReturnPath = false
    viewModel.addNode(additional)
    #expect(viewModel.fullPathData == originalPath)
    #expect(viewModel.outboundPath.map(\.hashBytes) == [Data([0xAB]), Data([0xCD])])

    viewModel.handleTraceResponse(response(tag: submitted.tag), radioID: radioID)
    await execution.value
    let result = try #require(viewModel.result)
    #expect(result.success)
    #expect(result.tracedPathBytes == Array(originalPath))
    #expect(result.hashSize == 2)
    #expect(viewModel.hashSize == 1)
    #expect(viewModel.canSavePath == false)

    // Exercise persistence independently of the UI's save-availability guard.
    #expect(await viewModel.savePath(name: "Original trace"))
    let savedPaths = try await store.fetchSavedTracePaths(radioID: radioID)
    #expect(savedPaths.count == 1)
    let saved = try #require(savedPaths.first)
    #expect(saved.pathBytes == originalPath)
    #expect(saved.hashSize == 2)
    #expect(saved.runs.count == 1)
    #expect(saved.runs.first?.success == true)

    viewModel.loadSavedPath(saved)
    #expect(viewModel.fullPathData == originalPath)
    #expect(viewModel.outboundPath.map(\.hashBytes) == [originalPath])
    #expect(viewModel.hashSize == 2)
    #expect(viewModel.effectiveTraceMode == 1)
  }

  private func makeContact(prefix: [UInt8], radioID: UUID) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(prefix + Array(repeating: 0, count: 32 - prefix.count)),
      name: "Repeater",
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 0
    ))
  }

  private func response(tag: UInt32) -> TraceInfo {
    TraceInfo(
      tag: tag,
      authCode: 0,
      flags: 1,
      pathLength: 1,
      path: [
        TraceNode(hashBytes: Data([0xAB, 0xCD]), snr: 5),
        TraceNode(hashBytes: nil, snr: 3)
      ]
    )
  }
}
