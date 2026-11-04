import Foundation
@testable import MC1Services
import Testing

@Suite("Radio WiFi state")
@MainActor
struct RadioWiFiStateTests {
  @Test
  func `a mutation invalidates old reads and only its token may settle`() throws {
    let state = RadioWiFiState(radioID: UUID())
    let initial = try #require(state.stableRevision())
    let first = try #require(state.beginMutation(expectedRevision: initial))
    #expect(state.stableRevision() == nil)
    #expect(state.beginMutation(expectedRevision: initial) == nil)
    let second = try #require(state.beginMutation(expectedRevision: nil))
    #expect(!state.isUncontended(first))
    #expect(state.settle(first, outcome: .accepted) == nil)
    #expect(state.stableRevision() == nil)
    #expect(state.rebootPending)
    #expect(state.settle(first, outcome: .rejected) == nil)
    state.settle(second, outcome: .rejected)
    #expect(state.stableRevision() != initial)
    #expect(state.rebootPending)
  }

  @Test
  func `expected reboot clears only after successful submission ended context and supported reset proof`() throws {
    let state = RadioWiFiState(radioID: UUID())
    let mutation = try #require(state.beginMutation(expectedRevision: nil))
    state.settle(mutation, outcome: .accepted)
    let oldContext = UUID()
    let (before, after) = resetObservations()
    let id = try #require(state.prepareExpectedReboot(contextID: oldContext, observation: before))
    let revision = try #require(state.stableRevision())
    let newContext = UUID()

    #expect(!state.clearExpectedReboot(id: id, contextID: newContext, readRevision: revision, observation: after))
    state.noteLinkEnded(contextID: oldContext)
    #expect(!state.clearExpectedReboot(id: id, contextID: newContext, readRevision: revision, observation: after))
    state.completeRebootSubmission(id: id, succeeded: true)
    #expect(!state.clearExpectedReboot(id: id, contextID: oldContext, readRevision: revision, observation: after))
    #expect(state.clearExpectedReboot(id: id, contextID: newContext, readRevision: revision, observation: after))
    #expect(!state.rebootPending)
  }

  @Test(arguments: [RadioWiFiState.MutationOutcome.accepted, .uncertain, .rejected])
  func `a newer mutation cannot be hidden by an older reboot candidate`(
    _ outcome: RadioWiFiState.MutationOutcome
  ) throws {
    let state = RadioWiFiState(radioID: UUID())
    let first = try #require(state.beginMutation(expectedRevision: nil))
    state.settle(first, outcome: .accepted)
    let oldContext = UUID()
    let (beforeReset, afterReset) = resetObservations()
    let id = try #require(state.prepareExpectedReboot(contextID: oldContext, observation: beforeReset))
    state.completeRebootSubmission(id: id, succeeded: true)
    state.noteLinkEnded(contextID: oldContext)
    let before = try #require(state.stableRevision())
    let newer = try #require(state.beginMutation(expectedRevision: nil))

    #expect(!state.clearExpectedReboot(id: id, contextID: UUID(), readRevision: before, observation: afterReset))
    state.settle(newer, outcome: outcome)
    let after = try #require(state.stableRevision())
    #expect(!state.clearExpectedReboot(id: id, contextID: UUID(), readRevision: after, observation: afterReset))
    #expect(state.rebootPending)
  }

  @Test
  func `ambiguous reboot and superseding choice retain apply evidence`() throws {
    let state = RadioWiFiState(radioID: UUID())
    let mutation = try #require(state.beginMutation(expectedRevision: nil))
    state.settle(mutation, outcome: .uncertain)
    let context = UUID()
    let failed = try #require(state.prepareExpectedReboot(contextID: context))
    state.noteLinkEnded(contextID: context)
    state.completeRebootSubmission(id: failed, succeeded: false)
    #expect(state.expectedRebootID == nil)
    #expect(state.rebootPending)
    let superseded = try #require(state.prepareExpectedReboot(contextID: context))
    state.completeRebootSubmission(id: superseded, succeeded: true)
    state.noteLinkEnded(contextID: context)
    state.cancelExpectedReboot()
    #expect(!state.clearExpectedReboot(id: superseded, contextID: UUID(), readRevision: state.revision))
    #expect(state.rebootPending)
  }

  private func resetObservations() -> (RadioWiFiResetObservation, RadioWiFiResetObservation) {
    let start = ContinuousClock.now
    return (
      RadioWiFiResetObservation(uptimeSeconds: 1000, startedAt: start, completedAt: start),
      RadioWiFiResetObservation(uptimeSeconds: 1, startedAt: start + .seconds(1), completedAt: start + .seconds(1))
    )
  }
}
