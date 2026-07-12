import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

// MARK: - Test Helpers

private func createTestSavedPath(runs: [TracePathRunDTO]) -> SavedTracePathDTO {
  SavedTracePathDTO(
    id: UUID(),
    radioID: UUID(),
    name: "Test Path",
    pathBytes: Data([0x01, 0x02, 0x01]),
    createdDate: Date(),
    runs: runs
  )
}

private func createTestRun(date: Date, roundTripMs: Int = 100, success: Bool = true) -> TracePathRunDTO {
  TracePathRunDTO(
    id: UUID(),
    date: date,
    success: success,
    roundTripMs: success ? roundTripMs : 0,
    hopsSNR: success ? [5.0, 3.0, -2.0] : []
  )
}

private func createTestContact() -> ContactDTO {
  let contact = Contact(
    id: UUID(),
    radioID: UUID(),
    publicKey: Data([0xAB] + Array(repeating: UInt8(0x00), count: 31)),
    name: "Test Repeater",
    typeRawValue: ContactType.repeater.rawValue,
    flags: 0,
    outPathLength: 0,
    outPath: Data(),
    lastAdvertTimestamp: 0,
    latitude: 0,
    longitude: 0,
    lastModified: 0
  )
  return ContactDTO(from: contact)
}

// MARK: - TraceHop Location Tests

@Suite("TraceHop Location")
@MainActor
struct TraceHopLocationTests {
  @Test
  func `hasLocation returns true with valid non-zero coordinates`() {
    let hop = TraceHop(
      hashBytes: Data([0x3F]),
      resolvedName: "Tower",
      snr: 5.0,
      isStartNode: false,
      isEndNode: false,
      latitude: 37.7749,
      longitude: -122.4194
    )
    #expect(hop.hasLocation == true)
  }

  @Test
  func `hasLocation returns false with zero coordinates`() {
    let hop = TraceHop(
      hashBytes: Data([0x3F]),
      resolvedName: "Tower",
      snr: 5.0,
      isStartNode: false,
      isEndNode: false,
      latitude: 0,
      longitude: 0
    )
    #expect(hop.hasLocation == false)
  }

  @Test
  func `hasLocation returns false with nil coordinates`() {
    let hop = TraceHop(
      hashBytes: Data([0x3F]),
      resolvedName: "Tower",
      snr: 5.0,
      isStartNode: false,
      isEndNode: false,
      latitude: nil,
      longitude: nil
    )
    #expect(hop.hasLocation == false)
  }

  @Test
  func `hasLocation returns true if only latitude is non-zero`() {
    let hop = TraceHop(
      hashBytes: Data([0x3F]),
      resolvedName: "Tower",
      snr: 5.0,
      isStartNode: false,
      isEndNode: false,
      latitude: 45.0,
      longitude: 0
    )
    #expect(hop.hasLocation == true)
  }

  @Test
  func `hasLocation returns true if only longitude is non-zero`() {
    let hop = TraceHop(
      hashBytes: Data([0x3F]),
      resolvedName: "Tower",
      snr: 5.0,
      isStartNode: false,
      isEndNode: false,
      latitude: 0,
      longitude: -122.0
    )
    #expect(hop.hasLocation == true)
  }
}

// MARK: - Path Edit Clears Saved Path Tests

@Suite("Path Edit Clears Saved Path")
@MainActor
struct PathEditClearsSavedPathTests {
  @Test
  func `addRepeater clears activeSavedPath`() {
    let viewModel = TracePathViewModel()
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    #expect(viewModel.activeSavedPath != nil)

    viewModel.addNode(createTestContact())

    #expect(viewModel.activeSavedPath == nil)
  }

  @Test
  func `removeRepeater clears activeSavedPath`() {
    let viewModel = TracePathViewModel()
    viewModel.activeSavedPath = createTestSavedPath(runs: [])
    viewModel.addNode(createTestContact())
    // Re-set since addRepeater clears it
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    #expect(viewModel.activeSavedPath != nil)

    viewModel.removeRepeater(at: 0)

    #expect(viewModel.activeSavedPath == nil)
  }

  @Test
  func `moveRepeater clears activeSavedPath`() {
    let viewModel = TracePathViewModel()

    // Add two repeaters
    viewModel.addNode(createTestContact())
    viewModel.addNode(createTestContact())
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    #expect(viewModel.activeSavedPath != nil)

    viewModel.moveRepeater(from: IndexSet(integer: 0), to: 2)

    #expect(viewModel.activeSavedPath == nil)
  }
}

// MARK: - Previous Run Comparison Tests

@Suite("Previous Run Comparison")
@MainActor
struct PreviousRunComparisonTests {
  @Test
  func `previousRun returns nil when no runs exist`() {
    let viewModel = TracePathViewModel()
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    #expect(viewModel.previousRun == nil)
  }

  @Test
  func `previousRun returns nil when only one run exists`() {
    let viewModel = TracePathViewModel()
    let run = createTestRun(date: Date())
    viewModel.activeSavedPath = createTestSavedPath(runs: [run])

    #expect(viewModel.previousRun == nil)
  }

  @Test
  func `previousRun returns second-to-last run when two runs exist`() {
    let viewModel = TracePathViewModel()
    let olderRun = createTestRun(date: Date().addingTimeInterval(-60), roundTripMs: 150)
    let newerRun = createTestRun(date: Date(), roundTripMs: 100)
    viewModel.activeSavedPath = createTestSavedPath(runs: [olderRun, newerRun])

    let previous = viewModel.previousRun
    #expect(previous != nil)
    #expect(previous?.roundTripMs == 150)
  }

  @Test
  func `previousRun returns second-to-last run when multiple runs exist`() {
    let viewModel = TracePathViewModel()
    let run1 = createTestRun(date: Date().addingTimeInterval(-120), roundTripMs: 200)
    let run2 = createTestRun(date: Date().addingTimeInterval(-60), roundTripMs: 150)
    let run3 = createTestRun(date: Date(), roundTripMs: 100)
    viewModel.activeSavedPath = createTestSavedPath(runs: [run1, run2, run3])

    let previous = viewModel.previousRun
    #expect(previous != nil)
    #expect(previous?.roundTripMs == 150) // Second-to-last (run2)
  }

  @Test
  func `previousRun skips failed runs when finding comparison`() {
    let viewModel = TracePathViewModel()
    // Oldest: success @ 200ms
    let run1 = createTestRun(date: Date().addingTimeInterval(-120), roundTripMs: 200)
    // Middle: failed (roundTripMs = 0)
    let run2 = createTestRun(date: Date().addingTimeInterval(-60), success: false)
    // Newest: success @ 100ms
    let run3 = createTestRun(date: Date(), roundTripMs: 100)
    viewModel.activeSavedPath = createTestSavedPath(runs: [run1, run2, run3])

    let previous = viewModel.previousRun
    #expect(previous != nil)
    // Should skip the failed run2 and return run1 (200ms)
    #expect(previous?.roundTripMs == 200)
  }

  @Test
  func `previousRun returns nil when only one successful run exists among failures`() {
    let viewModel = TracePathViewModel()
    let failedRun1 = createTestRun(date: Date().addingTimeInterval(-120), success: false)
    let failedRun2 = createTestRun(date: Date().addingTimeInterval(-60), success: false)
    let successRun = createTestRun(date: Date(), roundTripMs: 100)
    viewModel.activeSavedPath = createTestSavedPath(runs: [failedRun1, failedRun2, successRun])

    // Only one successful run, so no previous successful run exists
    #expect(viewModel.previousRun == nil)
  }
}

// MARK: - Trace Response Hop Parsing Tests

@Suite("Trace Response Hop Parsing")
@MainActor
struct TraceResponseHopParsingTests {
  @Test
  func `handleTraceResponse creates correct hops with receiver SNR attribution`() {
    let viewModel = TracePathViewModel()

    // Create a TraceInfo with one repeater hop + final nil node
    // SNR values: 5.0 = what repeater measured, 3.0 = what we measured on return
    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0), // Repeater received at 5.0 dB
        TraceNode(hash: nil, snr: 3.0) // We received return at 3.0 dB
      ]
    )

    // Set up pending tag to match
    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    #expect(result.success == true)
    #expect(result.hops.count == 3) // Start + 1 repeater + End

    // Start node has no SNR (it transmitted first, didn't receive)
    #expect(result.hops[0].isStartNode == true)
    #expect(result.hops[0].hashBytes == nil)
    #expect(result.hops[0].snr == 0) // Receiver attribution: start didn't receive

    // Intermediate hop shows SNR it measured when receiving
    #expect(result.hops[1].isStartNode == false)
    #expect(result.hops[1].isEndNode == false)
    #expect(result.hops[1].hashBytes == Data([0xAB]))
    #expect(result.hops[1].snr == 5.0) // Receiver attribution: what repeater measured

    // End node shows SNR it measured when receiving
    #expect(result.hops[2].isEndNode == true)
    #expect(result.hops[2].hashBytes == nil)
    #expect(result.hops[2].snr == 3.0) // Receiver attribution: what we measured
  }

  @Test
  func `handleTraceResponse creates correct hops for multi-hop trace with receiver SNR attribution`() {
    let viewModel = TracePathViewModel()

    // Path: Start → AA → BB → CC → End
    // SNR values represent what each node recorded when receiving
    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 3,
      path: [
        TraceNode(hash: 0xAA, snr: 6.0), // AA heard Start at 6.0 dB
        TraceNode(hash: 0xBB, snr: 4.0), // BB heard AA at 4.0 dB
        TraceNode(hash: 0xCC, snr: 2.0), // CC heard BB at 2.0 dB
        TraceNode(hash: nil, snr: -1.0) // End heard CC at -1.0 dB
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    #expect(result.hops.count == 5) // Start + 3 repeaters + End

    // Receiver attribution: each node shows what it measured when receiving
    #expect(result.hops[0].snr == 0) // Start: didn't receive (transmitted first)
    #expect(result.hops[1].snr == 6.0) // AA: what AA measured
    #expect(result.hops[2].snr == 4.0) // BB: what BB measured
    #expect(result.hops[3].snr == 2.0) // CC: what CC measured
    #expect(result.hops[4].snr == -1.0) // End: what End measured

    // Verify all intermediate hops are present
    #expect(result.hops[1].hashBytes == Data([0xAA]))
    #expect(result.hops[2].hashBytes == Data([0xBB]))
    #expect(result.hops[3].hashBytes == Data([0xCC]))
  }

  @Test
  func `handleTraceResponse ignores non-matching tags`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 99999, // Different tag
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345) // Different from traceInfo.tag
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    #expect(viewModel.result == nil)
  }
}

// MARK: - Result ID Tests

@Suite("Result ID Behavior")
@MainActor
struct ResultIDBehaviorTests {
  @Test
  func `resultID is set on successful trace`() {
    let viewModel = TracePathViewModel()

    // Simulate successful trace response
    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    #expect(viewModel.resultID == nil)

    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    #expect(viewModel.resultID != nil)
  }

  @Test
  func `resultID changes on each successful trace`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)
    let firstID = viewModel.resultID

    // Run another trace
    viewModel.setPendingTagForTesting(12346)
    let traceInfo2 = TraceInfo(
      tag: 12346,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )
    viewModel.handleTraceResponse(traceInfo2, radioID: nil)

    #expect(viewModel.resultID != firstID)
  }
}

// MARK: - Error Handling Tests

@Suite("Error Handling")
@MainActor
struct ErrorHandlingTests {
  @Test
  func `setError sets errorMessage`() {
    let viewModel = TracePathViewModel()

    #expect(viewModel.errorMessage == nil)

    viewModel.setError("Test error")

    #expect(viewModel.errorMessage == "Test error")
  }

  @Test
  func `clearError clears errorMessage`() {
    let viewModel = TracePathViewModel()
    viewModel.setError("Test error")

    #expect(viewModel.errorMessage != nil)

    viewModel.clearError()

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `setError replaces previous error`() {
    let viewModel = TracePathViewModel()

    viewModel.setError("First error")
    viewModel.setError("Second error")

    #expect(viewModel.errorMessage == "Second error")
  }

  @Test
  func `addRepeater clears error`() {
    let viewModel = TracePathViewModel()
    viewModel.setError("Test error")

    #expect(viewModel.errorMessage != nil)

    viewModel.addNode(createTestContact())

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `removeRepeater clears error`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())
    viewModel.setError("Test error")

    #expect(viewModel.errorMessage != nil)

    viewModel.removeRepeater(at: 0)

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `moveRepeater clears error`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())
    viewModel.addNode(createTestContact())
    viewModel.setError("Test error")

    #expect(viewModel.errorMessage != nil)

    viewModel.moveRepeater(from: IndexSet(integer: 0), to: 2)

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `error auto-clears after delay`() async throws {
    let viewModel = TracePathViewModel()
    viewModel.errorAutoClearDelay = .milliseconds(100)

    viewModel.setError("Test error")
    #expect(viewModel.errorMessage != nil)

    try await waitUntil(timeout: .seconds(1), "error should auto-clear after delay") {
      viewModel.errorMessage == nil
    }

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `clearError cancels pending auto-clear`() async throws {
    let viewModel = TracePathViewModel()
    viewModel.errorAutoClearDelay = .milliseconds(100)

    viewModel.setError("Test error")

    // Clear error before auto-clear would happen
    viewModel.clearError()

    // Wait for what would have been auto-clear time
    try await Task.sleep(for: .milliseconds(150))

    // Should still be nil (auto-clear was cancelled)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `new setError cancels previous auto-clear timer`() async throws {
    let viewModel = TracePathViewModel()
    viewModel.errorAutoClearDelay = .milliseconds(200)

    // Set first error
    viewModel.setError("First error")

    // Wait 100ms (less than 200ms auto-clear)
    try await Task.sleep(for: .milliseconds(100))

    // Set second error - this should cancel the first timer
    viewModel.setError("Second error")

    // Wait 150ms more (250ms total since first error, but only 150ms since second)
    try await Task.sleep(for: .milliseconds(150))

    // Should still show second error (first timer was cancelled, second hasn't expired)
    #expect(viewModel.errorMessage == "Second error")

    // Wait another 100ms (250ms total since second error)
    try await Task.sleep(for: .milliseconds(100))

    // Now it should be cleared
    #expect(viewModel.errorMessage == nil)
  }
}

// MARK: - Multi-byte Hash Tests

@Suite("Multi-byte Hash Handling")
@MainActor
struct MultiByteHashTests {
  @Test
  func `multi-byte hash produces hop with full hashBytes and nil resolvedName`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hashBytes: Data([0xAB, 0xCD]), snr: 5.0),
        TraceNode(hashBytes: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // Intermediate hop should have full 2-byte hash
    #expect(result.hops[1].hashBytes == Data([0xAB, 0xCD]))
    // Multi-byte hash cannot be resolved
    #expect(result.hops[1].resolvedName == nil)
    // Display string shows both bytes
    #expect(result.hops[1].hashDisplayString == "ABCD")
  }

  @Test
  func `single-byte hash still resolves to contact name`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // Single-byte hash stored correctly
    #expect(result.hops[1].hashBytes == Data([0xAB]))
    #expect(result.hops[1].hashDisplayString == "AB")
  }
}

// MARK: - Saved Path Hash Size Tests

@Suite("Saved Path Hash Size")
@MainActor
struct SavedPathHashSizeTests {
  @Test
  func `loadSavedPath uses stored hashSize, not device hashSize`() {
    // Path saved with 2-byte hashes: 4 bytes = 2 hops
    let savedPath = SavedTracePathDTO(
      id: UUID(),
      radioID: UUID(),
      name: "2-byte hash path",
      pathBytes: Data([0xAA, 0xBB, 0xCC, 0xDD]),
      hashSize: 2,
      createdDate: Date(),
      runs: []
    )

    let vm = TracePathViewModel()
    // Device hashSize defaults to 1 (no connected device),
    // but the saved path has hashSize=2
    vm.loadSavedPath(savedPath)

    // With hashSize=2: 4 bytes = 2 total hops, outbound = (2+1)/2 = 1 hop of 2 bytes
    #expect(vm.outboundPath.count == 1)
    #expect(vm.outboundPath.first?.hashBytes == Data([0xAA, 0xBB]))
  }

  @Test
  func `fullPathString chunks by saved hash size, not device hash size`() {
    // Path saved with 2-byte hashes: 6 bytes = 3 total hops, outbound = 2 hops
    let savedPath = SavedTracePathDTO(
      id: UUID(),
      radioID: UUID(),
      name: "2-byte path",
      pathBytes: Data([0xAA, 0xBB, 0xCC, 0xDD, 0xAA, 0xBB]),
      hashSize: 2,
      createdDate: Date(),
      runs: []
    )

    let vm = TracePathViewModel()
    // No connected device → device hashSize defaults to 1
    vm.loadSavedPath(savedPath)

    // Should chunk as 2-byte groups from the outbound hops, not 1-byte
    let parts = vm.fullPathString.split(separator: ",")
    for part in parts {
      #expect(part.count == 4, "Each chunk should be 4 hex chars (2 bytes), got \(part)")
    }
  }

  @Test
  func `loadSavedPath with hashSize 1 produces correct hops`() {
    let savedPath = SavedTracePathDTO(
      id: UUID(),
      radioID: UUID(),
      name: "1-byte hash path",
      pathBytes: Data([0xAA, 0xBB, 0xCC]),
      hashSize: 1,
      createdDate: Date(),
      runs: []
    )

    let vm = TracePathViewModel()
    vm.loadSavedPath(savedPath)

    // With hashSize=1: 3 bytes = 3 total hops, outbound = (3+1)/2 = 2 hops of 1 byte each
    #expect(vm.outboundPath.count == 2)
    #expect(vm.outboundPath[0].hashBytes == Data([0xAA]))
    #expect(vm.outboundPath[1].hashBytes == Data([0xBB]))
  }
}

// MARK: - Device ID Validation Tests

@Suite("Device ID Validation")
@MainActor
struct DeviceIDValidationTests {
  @Test
  func `response from different device is ignored`() {
    let viewModel = TracePathViewModel()
    let pendingDevice = UUID()
    let differentDevice = UUID()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.setPendingDeviceIDForTesting(pendingDevice)
    viewModel.handleTraceResponse(traceInfo, radioID: differentDevice)

    // Result should be nil - response was ignored
    #expect(viewModel.result == nil)
  }

  @Test
  func `response accepted when device IDs match`() {
    let viewModel = TracePathViewModel()
    let deviceID = UUID()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.setPendingDeviceIDForTesting(deviceID)
    viewModel.handleTraceResponse(traceInfo, radioID: deviceID)

    #expect(viewModel.result != nil)
    #expect(viewModel.result?.success == true)
  }

  @Test
  func `tag-only matching works when pendingDeviceID is nil`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.setPendingDeviceIDForTesting(nil)
    viewModel.handleTraceResponse(traceInfo, radioID: UUID())

    // Should accept - pendingDeviceID is nil so skip device check
    #expect(viewModel.result != nil)
  }

  @Test
  func `tag-only matching works when received deviceID is nil`() {
    let viewModel = TracePathViewModel()

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.setPendingDeviceIDForTesting(UUID())
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    // Should accept - received deviceID is nil so skip device check
    #expect(viewModel.result != nil)
  }
}

// MARK: - Path Capture Tests

@Suite("Path Capture in Result")
@MainActor
struct PathCaptureTests {
  @Test
  func `result contains original path even if outboundPath modified`() {
    let viewModel = TracePathViewModel()

    // Set up pending path hash (simulating what runTrace does)
    let originalPath: [UInt8] = [0xAA, 0xBB, 0xAA]
    viewModel.setPendingPathHashForTesting(originalPath)
    viewModel.setPendingTagForTesting(12345)

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAA, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // Result should contain the original path
    #expect(result.tracedPathBytes == originalPath)
    #expect(result.tracedPathString == "AA,BB,AA")
  }

  @Test
  func `canSavePath is false when path modified after trace`() {
    let viewModel = TracePathViewModel()

    // Simulate a completed trace with path [0xAA, 0xAA]
    let originalPath: [UInt8] = [0xAA, 0xAA]
    viewModel.setPendingPathHashForTesting(originalPath)
    viewModel.setPendingTagForTesting(12345)

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAA, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    // Verify result exists and has correct path
    #expect(viewModel.result?.tracedPathBytes == originalPath)

    // Now modify the outbound path (simulate user adding another hop)
    let contact = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0xBB] + Array(repeating: UInt8(0x00), count: 31)),
      name: "Different",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    viewModel.addNode(ContactDTO(from: contact))

    // fullPathBytes is now different from result.tracedPathBytes
    // canSavePath should be false
    #expect(viewModel.canSavePath == false)
  }

  @Test
  func `canSavePath is true when path unchanged after trace`() {
    let viewModel = TracePathViewModel()

    // Add a repeater first so fullPathBytes is populated
    let contact = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0xAA] + Array(repeating: UInt8(0x00), count: 31)),
      name: "Repeater",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    viewModel.addNode(ContactDTO(from: contact))

    // Get the current full path bytes
    let currentPath = viewModel.fullPathBytes

    // Simulate trace with same path
    viewModel.setPendingPathHashForTesting(currentPath)
    viewModel.setPendingTagForTesting(12345)

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAA, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    // Path unchanged, result successful - canSavePath should be true
    #expect(viewModel.canSavePath == true)
  }
}

// MARK: - Location Resolution Tests

// MARK: - Failure Result Tests

@Suite("Failure Result Path Display")
@MainActor
struct FailureResultTests {
  @Test
  func `timeout result contains attempted path`() {
    let attemptedPath: [UInt8] = [0xAA, 0xBB, 0xAA]
    let result = TraceResult.timeout(attemptedPath: attemptedPath, hashSize: 1)

    #expect(result.success == false)
    #expect(result.tracedPathBytes == attemptedPath)
    #expect(result.tracedPathString == "AA,BB,AA")
  }

  @Test
  func `sendFailed result contains attempted path`() {
    let attemptedPath: [UInt8] = [0xCC, 0xDD, 0xCC]
    let result = TraceResult.sendFailed("Connection lost", attemptedPath: attemptedPath, hashSize: 1)

    #expect(result.success == false)
    #expect(result.errorMessage == "Connection lost")
    #expect(result.tracedPathBytes == attemptedPath)
    #expect(result.tracedPathString == "CC,DD,CC")
  }

  @Test
  func `empty path produces empty tracedPathString`() {
    let result = TraceResult.timeout(attemptedPath: [], hashSize: 1)

    #expect(result.tracedPathBytes.isEmpty)
    #expect(result.tracedPathString == "")
  }

  @Test
  func `tracedPathString chunks by 2-byte hash size`() {
    let attemptedPath: [UInt8] = [0xAA, 0xBB, 0xCC, 0xDD, 0xAA, 0xBB]
    let result = TraceResult.timeout(attemptedPath: attemptedPath, hashSize: 2)

    #expect(result.tracedPathString == "AABB,CCDD,AABB")
  }

  @Test
  func `tracedPathString chunks by 3-byte hash size`() {
    let attemptedPath: [UInt8] = [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0xAA, 0xBB, 0xCC]
    let result = TraceResult.timeout(attemptedPath: attemptedPath, hashSize: 3)

    #expect(result.tracedPathString == "AABBCC,DDEEFF,AABBCC")
  }
}

// MARK: - Total Path Distance Tests

@Suite("Total Path Distance")
@MainActor
struct TotalPathDistanceTests {
  @Test
  func `calculates full path distance when device has location`() throws {
    let viewModel = TracePathViewModel()

    // San Francisco to Oakland to Berkeley and back (full path)
    let sf = (lat: 37.7749, lon: -122.4194)
    let oakland = (lat: 37.8044, lon: -122.2712)
    let berkeley = (lat: 37.8716, lon: -122.2727)

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: sf.lat, longitude: sf.lon),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Oakland", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: oakland.lat, longitude: oakland.lon),
      TraceHop(hashBytes: Data([0x4F]), resolvedName: "Berkeley", snr: 4.0, isStartNode: false, isEndNode: false,
               latitude: berkeley.lat, longitude: berkeley.lon),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: sf.lat, longitude: sf.lon)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F, 0x4F], hashSize: 1)

    let distance = viewModel.totalPathDistance
    #expect(distance != nil)
    // SF→Oakland ~13km, Oakland→Berkeley ~8km, Berkeley→SF ~17km ≈ 38km total
    #expect(try #require(distance) > 30000) // > 30km
    #expect(try #require(distance) < 50000) // < 50km
  }

  @Test
  func `falls back to intermediate-only distance when device lacks location`() throws {
    let viewModel = TracePathViewModel()

    let oakland = (lat: 37.8044, lon: -122.2712)
    let berkeley = (lat: 37.8716, lon: -122.2727)

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil), // No device location
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Oakland", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: oakland.lat, longitude: oakland.lon),
      TraceHop(hashBytes: Data([0x4F]), resolvedName: "Berkeley", snr: 4.0, isStartNode: false, isEndNode: false,
               latitude: berkeley.lat, longitude: berkeley.lon),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil) // No device location
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F, 0x4F], hashSize: 1)

    let distance = viewModel.totalPathDistance
    #expect(distance != nil)
    // Falls back to Oakland→Berkeley only ≈ 7.5km
    #expect(try #require(distance) > 7000) // > 7km
    #expect(try #require(distance) < 9000) // < 9km
  }

  @Test
  func `returns nil when hop missing location`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: 37.7749, longitude: -122.4194),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Unknown", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: nil, longitude: nil),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: 37.7749, longitude: -122.4194)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    #expect(viewModel.totalPathDistance == nil)
  }

  @Test
  func `returns nil when hop has zero location`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: 37.7749, longitude: -122.4194),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: 0, longitude: 0),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: 37.7749, longitude: -122.4194)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    #expect(viewModel.totalPathDistance == nil)
  }

  @Test
  func `returns nil for failed result`() {
    let viewModel = TracePathViewModel()

    viewModel.result = TraceResult(hops: [], durationMs: 0, success: false, errorMessage: "Timeout", tracedPathBytes: [], hashSize: 1)
    #expect(viewModel.totalPathDistance == nil)
  }

  @Test
  func `calculates distance for single repeater when device has location`() {
    let viewModel = TracePathViewModel()

    let sf = (lat: 37.7749, lon: -122.4194)
    let tower = (lat: 37.8, lon: -122.3)

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: sf.lat, longitude: sf.lon),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: tower.lat, longitude: tower.lon),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: sf.lat, longitude: sf.lon)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    // Full path: SF→Tower→SF should calculate (device has location)
    #expect(viewModel.totalPathDistance != nil)
  }

  @Test
  func `returns nil with single repeater and no device location`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil), // No device location
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: 37.8, longitude: -122.3),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil) // No device location
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    // Only 1 intermediate repeater, device has no location - can't calculate distance
    #expect(viewModel.totalPathDistance == nil)
  }

  @Test
  func `isDistanceUsingFallback is false when device has location`() {
    let viewModel = TracePathViewModel()

    let sf = (lat: 37.7749, lon: -122.4194)
    let oakland = (lat: 37.8044, lon: -122.2712)
    let berkeley = (lat: 37.8716, lon: -122.2727)

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: sf.lat, longitude: sf.lon),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Oakland", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: oakland.lat, longitude: oakland.lon),
      TraceHop(hashBytes: Data([0x4F]), resolvedName: "Berkeley", snr: 4.0, isStartNode: false, isEndNode: false,
               latitude: berkeley.lat, longitude: berkeley.lon),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: sf.lat, longitude: sf.lon)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F, 0x4F], hashSize: 1)

    #expect(viewModel.isDistanceUsingFallback == false)
  }

  @Test
  func `isDistanceUsingFallback is true when device lacks location`() {
    let viewModel = TracePathViewModel()

    let oakland = (lat: 37.8044, lon: -122.2712)
    let berkeley = (lat: 37.8716, lon: -122.2727)

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil), // No device location
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Oakland", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: oakland.lat, longitude: oakland.lon),
      TraceHop(hashBytes: Data([0x4F]), resolvedName: "Berkeley", snr: 4.0, isStartNode: false, isEndNode: false,
               latitude: berkeley.lat, longitude: berkeley.lon),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil) // No device location
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F, 0x4F], hashSize: 1)

    #expect(viewModel.isDistanceUsingFallback == true)
  }

  @Test
  func `isDistanceUsingFallback is false when distance is nil`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: nil, longitude: nil), // Repeater also missing location
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 3.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    // Distance is nil because repeater lacks location, so fallback flag is false
    #expect(viewModel.totalPathDistance == nil)
    #expect(viewModel.isDistanceUsingFallback == false)
  }
}

// MARK: - Repeaters Without Location Tests

@Suite("Repeaters Without Location")
@MainActor
struct RepeatersWithoutLocationTests {
  @Test
  func `returns names of hops missing locations`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: 37.7749, longitude: -122.4194),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower A", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: nil, longitude: nil),
      TraceHop(hashBytes: Data([0x4F]), resolvedName: "Tower B", snr: 4.0, isStartNode: false, isEndNode: false,
               latitude: 37.8, longitude: -122.3),
      TraceHop(hashBytes: Data([0x5F]), resolvedName: "Tower C", snr: 3.0, isStartNode: false, isEndNode: false,
               latitude: 0, longitude: 0),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 2.0, isStartNode: false, isEndNode: true,
               latitude: 37.7749, longitude: -122.4194)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F, 0x4F, 0x5F], hashSize: 1)

    let missing = viewModel.repeatersWithoutLocation
    #expect(missing.count == 2)
    #expect(missing.contains("Tower A"))
    #expect(missing.contains("Tower C"))
    #expect(!missing.contains("Tower B"))
  }

  @Test
  func `uses hash display for unresolved names`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: 37.7749, longitude: -122.4194),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: nil, snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: nil, longitude: nil),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 2.0, isStartNode: false, isEndNode: true,
               latitude: 37.7749, longitude: -122.4194)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    let missing = viewModel.repeatersWithoutLocation
    #expect(missing.count == 1)
    #expect(missing[0] == "3F") // hex display
  }

  @Test
  func `excludes start and end nodes`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil), // Start node missing - excluded
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: 37.8, longitude: -122.3),
      TraceHop(hashBytes: nil, resolvedName: "Device", snr: 2.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil) // End node missing - excluded
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    let missing = viewModel.repeatersWithoutLocation
    #expect(missing.count == 0) // Only intermediate hops count
  }

  @Test
  func `returns empty when device location missing but no intermediate repeaters affected`() {
    let viewModel = TracePathViewModel()

    let hops = [
      TraceHop(hashBytes: nil, resolvedName: "My Device", snr: 0, isStartNode: true, isEndNode: false,
               latitude: nil, longitude: nil),
      TraceHop(hashBytes: Data([0x3F]), resolvedName: "Tower", snr: 5.0, isStartNode: false, isEndNode: false,
               latitude: 37.8, longitude: -122.3),
      TraceHop(hashBytes: nil, resolvedName: "My Device", snr: 2.0, isStartNode: false, isEndNode: true,
               latitude: nil, longitude: nil)
    ]

    viewModel.result = TraceResult(hops: hops, durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0x3F], hashSize: 1)

    let missing = viewModel.repeatersWithoutLocation
    #expect(missing.count == 0)
  }
}

// MARK: - Code Input Parsing Tests

@Suite("Code Input Parsing")
@MainActor
struct CodeInputParsingTests {
  private func createContact(prefix: UInt8, name: String) -> ContactDTO {
    let contact = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([prefix] + Array(repeating: UInt8(0x00), count: 31)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    return ContactDTO(from: contact)
  }

  @Test
  func `parses valid comma-separated codes and adds repeaters`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha"),
      createContact(prefix: 0xB7, name: "Bravo"),
      createContact(prefix: 0xF2, name: "Foxtrot")
    ]

    let result = viewModel.addRepeatersFromCodes("A3, B7")

    #expect(result.added == ["A3", "B7"])
    #expect(result.notFound.isEmpty)
    #expect(result.alreadyInPath.isEmpty)
    #expect(viewModel.outboundPath.count == 2)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xA3]))
    #expect(viewModel.outboundPath[1].hashBytes == Data([0xB7]))
  }

  @Test
  func `handles case insensitive input`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]

    let result = viewModel.addRepeatersFromCodes("a3")

    #expect(result.added == ["A3"])
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `handles codes without spaces after commas`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha"),
      createContact(prefix: 0xB7, name: "Bravo")
    ]

    let result = viewModel.addRepeatersFromCodes("A3,B7")

    #expect(result.added.count == 2)
    #expect(viewModel.outboundPath.count == 2)
  }

  @Test
  func `reports codes not found in available repeaters`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]

    let result = viewModel.addRepeatersFromCodes("A3, 11, FF")

    #expect(result.added == ["A3"])
    #expect(result.notFound == ["11", "FF"])
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `reports codes already in outbound path`() {
    let viewModel = TracePathViewModel()
    let alpha = createContact(prefix: 0xA3, name: "Alpha")
    viewModel.availableRepeaters = [alpha]
    viewModel.addNode(alpha)

    let result = viewModel.addRepeatersFromCodes("A3")

    #expect(result.added.isEmpty)
    #expect(result.alreadyInPath == ["A3"])
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `deduplicates codes in input`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]

    let result = viewModel.addRepeatersFromCodes("A3, A3, a3")

    #expect(result.added == ["A3"])
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `reports invalid hex format`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]

    let result = viewModel.addRepeatersFromCodes("A3, ZZ, 123, X")

    #expect(result.added == ["A3"])
    #expect(result.invalidFormat == ["ZZ", "123", "X"])
  }

  @Test
  func `handles empty input`() {
    let viewModel = TracePathViewModel()

    let result = viewModel.addRepeatersFromCodes("")

    #expect(result.added.isEmpty)
    #expect(result.notFound.isEmpty)
    #expect(result.invalidFormat.isEmpty)
  }

  @Test
  func `handles whitespace-only input`() {
    let viewModel = TracePathViewModel()

    let result = viewModel.addRepeatersFromCodes("   ,  , ")

    #expect(result.added.isEmpty)
  }

  @Test
  func `hasErrors returns false when all codes are valid and new`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]

    let result = viewModel.addRepeatersFromCodes("A3")

    #expect(result.hasErrors == false)
    #expect(result.errorMessage == nil)
  }

  @Test
  func `hasErrors returns true when errors exist`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = []

    let result = viewModel.addRepeatersFromCodes("A3")

    #expect(result.hasErrors == true)
    #expect(result.errorMessage != nil)
  }

  @Test
  func `errorMessage formats multiple error types with separator`() {
    let viewModel = TracePathViewModel()
    let alpha = createContact(prefix: 0xA3, name: "Alpha")
    viewModel.availableRepeaters = [alpha]
    viewModel.addNode(alpha)

    let result = viewModel.addRepeatersFromCodes("ZZ, 11, A3")

    #expect(result.errorMessage?.contains("Invalid format: ZZ") == true)
    #expect(result.errorMessage?.contains("11 not found") == true)
    #expect(result.errorMessage?.contains("A3 already in path") == true)
  }

  @Test
  func `clears saved path state when repeaters are added`() {
    let viewModel = TracePathViewModel()
    viewModel.availableRepeaters = [
      createContact(prefix: 0xA3, name: "Alpha")
    ]
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    _ = viewModel.addRepeatersFromCodes("A3")

    #expect(viewModel.activeSavedPath == nil)
  }
}

// MARK: - OutboundPath Name Resolution Tests

@Suite("OutboundPath Name Resolution")
@MainActor
struct OutboundPathNameResolutionTests {
  private func createContact(prefix: UInt8, name: String, lat: Double = 0, lon: Double = 0) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([prefix] + Array(repeating: UInt8(0), count: 31)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: lat,
      longitude: lon,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  @Test
  func `resolves name using best match when contact collision exists`() {
    let viewModel = TracePathViewModel()

    // Two contacts with same first byte (collision)
    let contact1 = createContact(prefix: 0x3F, name: "Flint Hill - KC3ELT")
    let contact2 = createContact(prefix: 0x3F, name: "Other Tower")
    // Make contact2 have different second byte so they're distinct
    var contact2Key = contact2.publicKey
    contact2Key = Data([0x3F, 0x01] + Array(repeating: UInt8(0), count: 30))
    let contact1Modified = ContactDTO(
      id: contact1.id,
      radioID: contact1.radioID,
      publicKey: contact1.publicKey,
      name: contact1.name,
      typeRawValue: contact1.typeRawValue,
      flags: contact1.flags,
      outPathLength: contact1.outPathLength,
      outPath: contact1.outPath,
      lastAdvertTimestamp: 10,
      latitude: contact1.latitude,
      longitude: contact1.longitude,
      lastModified: contact1.lastModified,
      nickname: contact1.nickname,
      isBlocked: contact1.isBlocked,
      isMuted: contact1.isMuted,
      isFavorite: contact1.isFavorite,
      lastMessageDate: contact1.lastMessageDate,
      unreadCount: contact1.unreadCount
    )
    let contact2Modified = ContactDTO(
      id: contact2.id,
      radioID: contact2.radioID,
      publicKey: contact2Key,
      name: "Other Tower",
      typeRawValue: contact2.typeRawValue,
      flags: contact2.flags,
      outPathLength: contact2.outPathLength,
      outPath: contact2.outPath,
      lastAdvertTimestamp: 50,
      latitude: contact2.latitude,
      longitude: contact2.longitude,
      lastModified: contact2.lastModified,
      nickname: contact2.nickname,
      isBlocked: contact2.isBlocked,
      isMuted: contact2.isMuted,
      isFavorite: contact2.isFavorite,
      lastMessageDate: contact2.lastMessageDate,
      unreadCount: contact2.unreadCount
    )

    viewModel.setContactsForTesting([contact1Modified, contact2Modified])

    // User selects contact1 for their path
    viewModel.addNode(contact1Modified)

    // Run trace
    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0x3F, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // With full public key stored, resolves to the exact repeater the user selected
    #expect(result.hops[1].resolvedName == "Flint Hill - KC3ELT")
  }

  @Test
  func `addRepeater stores full public key in PathHop`() {
    let viewModel = TracePathViewModel()
    let key = Data([0x3F] + Array(repeating: UInt8(0), count: 31))
    let contact = createContact(prefix: 0x3F, name: "Tower")
    viewModel.addNode(contact)

    #expect(viewModel.outboundPath.count == 1)
    #expect(viewModel.outboundPath[0].publicKey == key)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0x3F]))
  }

  @Test
  func `handleTraceResponse resolves correct repeater when hash collision exists using stored key`() {
    let viewModel = TracePathViewModel()

    // Two repeaters with same first byte
    let nearRepeater = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0x3F, 0x01] + Array(repeating: UInt8(0), count: 30)),
      name: "Near Tower",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 100,
      latitude: 37.0,
      longitude: -122.0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let farRepeater = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0x3F, 0x02] + Array(repeating: UInt8(0), count: 30)),
      name: "Far Tower",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 200,
      latitude: 38.0,
      longitude: -123.0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )

    viewModel.setContactsForTesting([nearRepeater, farRepeater])

    // User explicitly selects the near repeater
    viewModel.addNode(nearRepeater)

    let traceInfo = TraceInfo(
      tag: 42,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0x3F, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(42)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // Should resolve to the exact repeater the user selected, not the one with higher advert timestamp
    #expect(result.hops[1].resolvedName == "Near Tower")
  }

  @Test
  func `falls back to contact lookup when hop not in outboundPath`() {
    let viewModel = TracePathViewModel()

    // Single contact (no collision)
    let contact = createContact(prefix: 0xAB, name: "Test Tower")
    viewModel.setContactsForTesting([contact])

    // Empty outboundPath - user didn't select any repeaters
    // (simulating an unexpected hop in the trace response)

    let traceInfo = TraceInfo(
      tag: 12345,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )

    viewModel.setPendingTagForTesting(12345)
    viewModel.handleTraceResponse(traceInfo, radioID: nil)

    guard let result = viewModel.result else {
      Issue.record("Result should not be nil")
      return
    }

    // Should fall back to contact lookup since outboundPath is empty
    #expect(result.hops[1].resolvedName == "Test Tower")
  }
}

// MARK: - Room Support Tests

@Suite("Room Support")
@MainActor
struct RoomSupportTests {
  private func createContact(prefix: UInt8, name: String, type: ContactType = .repeater) -> ContactDTO {
    let contact = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([prefix] + Array(repeating: UInt8(0x00), count: 31)),
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    return ContactDTO(from: contact)
  }

  @Test
  func `setContactsForTesting populates both availableRepeaters and availableRooms`() {
    let viewModel = TracePathViewModel()
    let repeater = createContact(prefix: 0xA1, name: "Repeater")
    let room = createContact(prefix: 0xB2, name: "Room", type: .room)

    viewModel.setContactsForTesting([repeater, room])

    #expect(viewModel.availableRepeaters.count == 1)
    #expect(viewModel.availableRepeaters[0].name == "Repeater")
    #expect(viewModel.availableRooms.count == 1)
    #expect(viewModel.availableRooms[0].name == "Room")
  }

  @Test
  func `availableNodes returns union of repeaters and rooms`() {
    let viewModel = TracePathViewModel()
    let repeater = createContact(prefix: 0xA1, name: "Repeater")
    let room = createContact(prefix: 0xB2, name: "Room", type: .room)

    viewModel.setContactsForTesting([repeater, room])

    #expect(viewModel.availableNodes.count == 2)
    #expect(viewModel.availableNodes.contains { $0.name == "Repeater" })
    #expect(viewModel.availableNodes.contains { $0.name == "Room" })
  }

  @Test
  func `addRepeater works with a room contact`() {
    let viewModel = TracePathViewModel()
    let room = createContact(prefix: 0xB2, name: "Room Server", type: .room)

    viewModel.addNode(room)

    #expect(viewModel.outboundPath.count == 1)
    #expect(viewModel.outboundPath[0].resolvedName == "Room Server")
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xB2]))
  }

  @Test
  func `addRepeatersFromCodes finds rooms via availableNodes`() {
    let viewModel = TracePathViewModel()
    let room = createContact(prefix: 0xB2, name: "Room Server", type: .room)

    viewModel.setContactsForTesting([room])

    let result = viewModel.addRepeatersFromCodes("B2")

    #expect(result.added == ["B2"])
    #expect(result.notFound.isEmpty)
    #expect(viewModel.outboundPath.count == 1)
    #expect(viewModel.outboundPath[0].resolvedName == "Room Server")
  }
}

// MARK: - Trace Hash Size Override Tests

@Suite("Trace Hash Size Override")
@MainActor
struct TraceHashSizeOverrideTests {
  @Test
  func `setTraceHashMode rebuilds hop hash bytes from the public key prefix`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())

    // Default (no override, no appState) is mode 0 -> 1 byte per hop.
    #expect(viewModel.effectiveTraceMode == 0)
    #expect(viewModel.hashSize == 1)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xAB]))

    viewModel.setTraceHashMode(1)
    #expect(viewModel.effectiveTraceMode == 1)
    #expect(viewModel.hashSize == 2)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xAB, 0x00]))

    viewModel.setTraceHashMode(2)
    #expect(viewModel.hashSize == 4)
    #expect(viewModel.outboundPath[0].hashBytes.count == 4)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xAB, 0x00, 0x00, 0x00]))

    viewModel.setTraceHashMode(0)
    #expect(viewModel.hashSize == 1)
    #expect(viewModel.outboundPath[0].hashBytes == Data([0xAB]))
  }

  @Test
  func `fullPathData width tracks the active hash mode`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())
    viewModel.addNode(createTestContact())

    // Two outbound hops with auto-return mirror -> 3 hops on the wire.
    #expect(viewModel.autoReturnPath)
    #expect(viewModel.fullPathData.count == 3 * 1)

    viewModel.setTraceHashMode(1)
    #expect(viewModel.fullPathData.count == 3 * 2)

    viewModel.setTraceHashMode(2)
    #expect(viewModel.fullPathData.count == 3 * 4)
  }

  @Test
  func `setTraceHashMode clears a stale result and saved-path reference`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    viewModel.setTraceHashMode(2)

    #expect(viewModel.activeSavedPath == nil)
    #expect(viewModel.result == nil)
  }

  @Test
  func `widening zero-pads key-less hops so the wire stays a uniform width`() {
    let viewModel = TracePathViewModel()
    // Saved path bytes match no known contact, so every hop is key-less.
    viewModel.loadSavedPath(createTestSavedPath(runs: []))
    #expect(!viewModel.outboundPath.isEmpty)
    #expect(viewModel.outboundPath.allSatisfy { $0.publicKey == nil })

    viewModel.setTraceHashMode(2)

    #expect(viewModel.hashSize == 4)
    #expect(viewModel.outboundPath.allSatisfy { $0.hashBytes.count == 4 })
    // A short hop would break divisibility and misalign the firmware's parse.
    #expect(viewModel.fullPathData.count % 4 == 0)
  }

  @Test
  func `clearPath resets the per-trace hash mode override`() {
    let viewModel = TracePathViewModel()
    viewModel.addNode(createTestContact())
    viewModel.setTraceHashMode(2)
    #expect(viewModel.effectiveTraceMode == 2)

    viewModel.clearPath()

    #expect(viewModel.effectiveTraceMode == 0)
    #expect(viewModel.outboundPath.isEmpty)
  }

  @Test
  func `loadSavedPath sets no override when the radio can't honor it`() {
    let viewModel = TracePathViewModel()
    // No appState configured, so supportsTraceHashSizeOverride is not true.
    viewModel.loadSavedPath(createTestSavedPath(runs: []))

    #expect(viewModel.effectiveTraceMode == 0)
  }
}

// MARK: - Inferred Trace Hash Mode Tests

@Suite("Inferred Trace Hash Mode")
@MainActor
struct InferredTraceHashModeTests {
  @Test
  func `Uniform 1-byte codes infer mode 0`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A,2B") == 0)
  }

  @Test
  func `Uniform 2-byte codes infer mode 1`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A2B,3C4D") == 1)
  }

  @Test
  func `Uniform 4-byte codes infer mode 2`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "AABBCCDD,11223344") == 2)
  }

  @Test
  func `Whitespace around codes is tolerated`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: " 1A , 2B ") == 0)
  }

  @Test
  func `Mixed widths infer nothing`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A,2B3C") == nil)
  }

  @Test
  func `Odd-length code infers nothing`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A,2") == nil)
  }

  @Test
  func `Non-hex code infers nothing`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A,ZZ") == nil)
  }

  @Test
  func `Uniform but non-power-of-2 width (3 bytes) infers nothing`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "1A2B3C,4D5E6F") == nil)
  }

  @Test
  func `Empty and separator-only input infer nothing`() {
    #expect(TracePathViewModel.inferredTraceHashMode(from: "") == nil)
    #expect(TracePathViewModel.inferredTraceHashMode(from: ",") == nil)
  }
}

// MARK: - Adopt Hash Size From Paste Tests

@Suite("Adopt Hash Size From Paste")
@MainActor
struct AdoptHashSizeFromPasteTests {
  private func makeViewModel(supportsOverride: Bool) -> TracePathViewModel {
    let device = DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "TestDevice",
      firmwareVersion: 8,
      firmwareVersionString: supportsOverride ? "v1.11.0" : "v1.10.0",
      manufacturerName: "TestMfg",
      buildDate: "01 Jan 2025",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: false,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
    let viewModel = TracePathViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { nil },
      connectedDevice: { device },
      bestAvailableLocation: { nil }
    ))
    return viewModel
  }

  @Test
  func `Pasting narrower codes switches the active hash size down`() {
    let viewModel = makeViewModel(supportsOverride: true)
    viewModel.addNode(createTestContact())
    viewModel.setTraceHashMode(1)
    #expect(viewModel.hashSize == 2)

    viewModel.adoptHashSize(forPastedCodes: "1A,2B")

    #expect(viewModel.effectiveTraceMode == 0)
    #expect(viewModel.hashSize == 1)
  }

  @Test
  func `Pasting the active width leaves a stale saved-path reference intact`() {
    let viewModel = makeViewModel(supportsOverride: true)
    viewModel.addNode(createTestContact())
    viewModel.setTraceHashMode(1)
    viewModel.activeSavedPath = createTestSavedPath(runs: [])

    viewModel.adoptHashSize(forPastedCodes: "1A2B,3C4D")

    #expect(viewModel.effectiveTraceMode == 1)
    #expect(viewModel.activeSavedPath != nil)
  }

  @Test
  func `Mixed-width paste leaves the active hash size unchanged`() {
    let viewModel = makeViewModel(supportsOverride: true)
    viewModel.setTraceHashMode(1)

    viewModel.adoptHashSize(forPastedCodes: "1A,2B3C")

    #expect(viewModel.effectiveTraceMode == 1)
  }

  @Test
  func `Unsupported firmware ignores the pasted width`() {
    let viewModel = makeViewModel(supportsOverride: false)
    viewModel.setTraceHashMode(1)

    viewModel.adoptHashSize(forPastedCodes: "1A,2B")

    #expect(viewModel.effectiveTraceMode == 1)
  }
}

// MARK: - Trace Hash Size Capability Gate Tests

@Suite("Trace Hash Size Capability Gate")
struct TraceHashSizeCapabilityGateTests {
  private func makeDevice(firmwareVersion: UInt8, firmwareVersionString: String) -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "TestDevice",
      firmwareVersion: firmwareVersion,
      firmwareVersionString: firmwareVersionString,
      manufacturerName: "TestMfg",
      buildDate: "01 Jan 2025",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: false,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
  }

  @Test
  func `VER_CODE 8 disambiguates v1.10 (off) from v1.11/v1.12 (on)`() {
    // VER_CODE stayed 8 across v1.10.0 (no feature) through v1.12.0 (feature).
    #expect(makeDevice(firmwareVersion: 8, firmwareVersionString: "v1.10.0").supportsTraceHashSizeOverride == false)
    #expect(makeDevice(firmwareVersion: 8, firmwareVersionString: "v1.11.0").supportsTraceHashSizeOverride == true)
    #expect(makeDevice(firmwareVersion: 8, firmwareVersionString: "v1.12.0").supportsTraceHashSizeOverride == true)
  }

  @Test
  func `VER_CODE >= 9 enables the override even without a usable version string`() {
    #expect(makeDevice(firmwareVersion: 9, firmwareVersionString: "").supportsTraceHashSizeOverride == true)
  }

  @Test
  func `older firmware does not support the override`() {
    #expect(makeDevice(firmwareVersion: 7, firmwareVersionString: "v1.9.0").supportsTraceHashSizeOverride == false)
  }
}
