import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

// MARK: - Test Helpers

private func createTestHop(snr: Double, isStartNode: Bool = false, isEndNode: Bool = false) -> TraceHop {
  TraceHop(
    hashBytes: isStartNode || isEndNode ? nil : Data([0xAA]),
    resolvedName: nil,
    snr: snr,
    isStartNode: isStartNode,
    isEndNode: isEndNode,
    latitude: nil,
    longitude: nil
  )
}

private func createTestResult(hopSNRs: [Double], durationMs: Int, success: Bool = true) -> TraceResult {
  var hops: [TraceHop] = [createTestHop(snr: 0, isStartNode: true)]
  for snr in hopSNRs {
    hops.append(createTestHop(snr: snr))
  }
  hops.append(createTestHop(snr: hopSNRs.last ?? 0, isEndNode: true))

  return TraceResult(
    hops: hops,
    durationMs: durationMs,
    success: success,
    errorMessage: success ? nil : "Failed",
    tracedPathBytes: [0xAA],
    hashSize: 1
  )
}

@Suite("Batch Trace State")
@MainActor
struct BatchTraceStateTests {
  @Test
  func `batch properties have correct defaults`() {
    let viewModel = TracePathViewModel()

    #expect(viewModel.batchEnabled == false)
    #expect(viewModel.batchSize == 5)
    #expect(viewModel.currentTraceIndex == 0)
    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.isBatchInProgress == false)
    #expect(viewModel.isBatchComplete == false)
  }

  @Test
  func `successfulResults filters to successful traces only`() {
    let viewModel = TracePathViewModel()

    let successResult = TraceResult(
      hops: [],
      durationMs: 100,
      success: true,
      errorMessage: nil,
      tracedPathBytes: [0xAA],
      hashSize: 1
    )
    let failedResult = TraceResult(
      hops: [],
      durationMs: 0,
      success: false,
      errorMessage: "Timeout",
      tracedPathBytes: [0xAA],
      hashSize: 1
    )

    viewModel.completedResults = [successResult, failedResult, successResult]

    #expect(viewModel.successfulResults.count == 2)
  }

  @Test
  func `successCount returns number of successful traces`() {
    let viewModel = TracePathViewModel()

    let successResult = TraceResult(
      hops: [],
      durationMs: 100,
      success: true,
      errorMessage: nil,
      tracedPathBytes: [0xAA],
      hashSize: 1
    )
    let failedResult = TraceResult(
      hops: [],
      durationMs: 0,
      success: false,
      errorMessage: "Timeout",
      tracedPathBytes: [0xAA],
      hashSize: 1
    )

    viewModel.completedResults = [successResult, failedResult, successResult]

    #expect(viewModel.successCount == 2)
  }

  @Test
  func `batchEnabled didSet clears batch state when disabled`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.currentTraceIndex = 3
    viewModel.completedResults = [
      TraceResult(hops: [], durationMs: 100, success: true, errorMessage: nil, tracedPathBytes: [0xAA], hashSize: 1)
    ]

    viewModel.batchEnabled = false

    #expect(viewModel.currentTraceIndex == 0)
    #expect(viewModel.completedResults.isEmpty)
  }
}

@Suite("Batch Aggregate Computation")
@MainActor
struct BatchAggregateTests {
  @Test
  func `RTT aggregates compute correctly`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100),
      createTestResult(hopSNRs: [5.0], durationMs: 200),
      createTestResult(hopSNRs: [5.0], durationMs: 150)
    ]

    #expect(viewModel.averageRTT == 150)
    #expect(viewModel.minRTT == 100)
    #expect(viewModel.maxRTT == 200)
  }

  @Test
  func `RTT aggregates exclude failed traces`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100),
      createTestResult(hopSNRs: [], durationMs: 0, success: false),
      createTestResult(hopSNRs: [5.0], durationMs: 200)
    ]

    #expect(viewModel.averageRTT == 150)
    #expect(viewModel.minRTT == 100)
    #expect(viewModel.maxRTT == 200)
  }

  @Test
  func `RTT aggregates return nil when no successful traces`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    viewModel.completedResults = [
      createTestResult(hopSNRs: [], durationMs: 0, success: false)
    ]

    #expect(viewModel.averageRTT == nil)
    #expect(viewModel.minRTT == nil)
    #expect(viewModel.maxRTT == nil)
  }

  @Test
  func `hop stats compute correctly for intermediate hops`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    // 2 intermediate hops per result
    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0, 3.0], durationMs: 100),
      createTestResult(hopSNRs: [7.0, 1.0], durationMs: 100),
      createTestResult(hopSNRs: [6.0, 2.0], durationMs: 100)
    ]

    // Hop index 1 is first intermediate hop (index 0 is start node)
    let stats1 = viewModel.hopStats(at: 1)
    #expect(stats1 != nil)
    #expect(stats1?.avg == 6.0) // (5+7+6)/3
    #expect(stats1?.min == 5.0)
    #expect(stats1?.max == 7.0)

    // Hop index 2 is second intermediate hop
    let stats2 = viewModel.hopStats(at: 2)
    #expect(stats2 != nil)
    #expect(stats2?.avg == 2.0) // (3+1+2)/3
    #expect(stats2?.min == 1.0)
    #expect(stats2?.max == 3.0)
  }

  @Test
  func `hop stats return nil for start node`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100)
    ]

    #expect(viewModel.hopStats(at: 0) == nil)
  }

  @Test
  func `latestHopSNR returns SNR from most recent successful result`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true

    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100),
      createTestResult(hopSNRs: [7.0], durationMs: 100)
    ]

    #expect(viewModel.latestHopSNR(at: 1) == 7.0)
  }
}

@Suite("Batch Execution")
@MainActor
struct BatchExecutionTests {
  @Test
  func `runBatchTrace resets batch state before starting`() async {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.batchSize = 3

    // Pre-populate with stale data
    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100)
    ]
    viewModel.currentTraceIndex = 2

    // Can't actually run without appState, but we can verify reset happens
    await viewModel.runBatchTrace()

    // Should have reset (even though trace won't actually run without appState)
    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.currentTraceIndex == 0)
  }

  @Test
  func `clearBatchState resets all batch properties`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.currentTraceIndex = 3
    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100)
    ]

    viewModel.clearBatchState()

    #expect(viewModel.currentTraceIndex == 0)
    #expect(viewModel.completedResults.isEmpty)
  }

  @Test
  func `cancelBatchTrace clears running state`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.isRunning = true
    viewModel.currentTraceIndex = 2
    viewModel.setPendingTagForTesting(12345)

    viewModel.cancelBatchTrace()

    #expect(viewModel.isRunning == false)
    #expect(viewModel.currentTraceIndex == 0)
  }
}

@Suite("Batch Cancellation Behavior")
@MainActor
struct BatchCancellationTests {
  @Test
  func `isBatchInProgress returns false after cancel`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.batchSize = 5
    viewModel.currentTraceIndex = 2
    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100)
    ]

    // Verify it's in progress
    #expect(viewModel.isBatchInProgress == true)

    viewModel.cancelBatchTrace()

    // Should no longer be in progress
    #expect(viewModel.isBatchInProgress == false)
  }

  @Test
  func `batch state preserved on cancel for partial results access`() {
    let viewModel = TracePathViewModel()
    viewModel.batchEnabled = true
    viewModel.batchSize = 5
    viewModel.completedResults = [
      createTestResult(hopSNRs: [5.0], durationMs: 100),
      createTestResult(hopSNRs: [6.0], durationMs: 110)
    ]
    viewModel.currentTraceIndex = 3

    viewModel.cancelBatchTrace()

    // Completed results should be preserved for viewing
    #expect(viewModel.completedResults.count == 2)
    // But execution state should be reset
    #expect(viewModel.currentTraceIndex == 0)
  }
}
