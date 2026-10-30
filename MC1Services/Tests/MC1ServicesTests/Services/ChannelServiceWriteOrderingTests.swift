import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Channel write ordering")
struct ChannelServiceWriteOrderingTests {
  private let secretB = Data(repeating: 0xB1, count: ProtocolLimits.channelSecretSize)
  private let secretA = Data(repeating: 0xA1, count: ProtocolLimits.channelSecretSize)
  private let secretLater = Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize)

  @Test
  func `a later set survives an older sync observation and keeps its message and draft`() async throws {
    for path in ReadPath.syncPaths {
      for observation in [SlotObservation.configuredA, .empty] {
        for order in AdmissionOrder.allCases {
          let label = "\(path) \(observation) \(order) set"
          try await overlap(
            path: path,
            observation: observation,
            order: order,
            mutation: .setB,
            label: label
          )
        }
      }
    }
  }

  @Test
  func `a later clear is not restored by an older configured observation`() async throws {
    for path in ReadPath.syncPaths {
      for order in AdmissionOrder.allCases {
        let label = "\(path) \(order) clear"
        try await overlap(
          path: path,
          observation: .configuredA,
          order: order,
          mutation: .clear,
          label: label
        )
      }
    }
  }

  @Test
  func `retry overlaps set and clear in both admission orders`() async throws {
    for order in AdmissionOrder.allCases {
      try await overlap(
        path: .retry,
        observation: .configuredA,
        order: order,
        mutation: .setB,
        label: "retry \(order) set"
      )
      try await overlap(
        path: .retry,
        observation: .configuredA,
        order: order,
        mutation: .clear,
        label: "retry \(order) clear"
      )
    }
  }

  @Test
  func `set and clear run in admission order`() async throws {
    let firstSet = try await Fixture.make()
    await firstSet.session.holdNextSetChannel()
    let setB = Task {
      try await firstSet.service.setChannelWithSecret(
        radioID: firstSet.radioID, index: 0, name: "B", secret: secretB
      )
    }
    await firstSet.session.waitForSetChannelHold()
    let clear = Task {
      try await firstSet.service.clearChannel(radioID: firstSet.radioID, index: 0)
    }
    try await firstSet.waitUntilWaiters(equal: 1)
    await firstSet.session.releaseSetChannelHold()
    try await setB.value
    try await clear.value
    try await firstSet.expectCleared(label: "set then clear")

    let firstClear = try await Fixture.make()
    try await firstClear.seed(name: "A", secret: secretA, message: "gone")
    await firstClear.session.holdNextSetChannel()
    let clearing = Task {
      try await firstClear.service.clearChannel(radioID: firstClear.radioID, index: 0)
    }
    await firstClear.session.waitForSetChannelHold()
    let setting = Task {
      try await firstClear.service.setChannelWithSecret(
        radioID: firstClear.radioID, index: 0, name: "B", secret: secretB
      )
    }
    try await firstClear.waitUntilWaiters(equal: 1)
    await firstClear.session.releaseSetChannelHold()
    try await clearing.value
    try await setting.value
    let stored = try await firstClear.store.fetchChannel(radioID: firstClear.radioID, index: 0)
    #expect(stored?.name == "B")
    #expect(stored?.secret == secretB)
    #expect(try await firstClear.messages(at: 0).isEmpty)
  }

  @Test
  func `a current secret change still deletes history and a missing slot is removed`() async throws {
    let changed = try await Fixture.make()
    try await changed.seed(name: "Old", secret: secretB, message: "drop")
    await changed.session.setStubbedChannels([
      0: ChannelInfo(index: 0, name: "A", secret: secretA)
    ])
    _ = try await changed.service.syncChannels(radioID: changed.radioID, maxChannels: 1, usePipelinedRead: false)
    let replaced = try #require(try await changed.store.fetchChannel(radioID: changed.radioID, index: 0))
    #expect(replaced.name == "A")
    #expect(replaced.secret == secretA)
    #expect(try await changed.messages(at: 0).isEmpty)
    #expect(await changed.drafts.clearedSlot(0))

    let missing = try await Fixture.make()
    try await missing.seed(name: "Gone", secret: secretB, message: "drop")
    _ = try await missing.service.syncChannels(radioID: missing.radioID, maxChannels: 1, usePipelinedRead: true)
    #expect(try await missing.store.fetchChannel(radioID: missing.radioID, index: 0) == nil)
    #expect(try await missing.messages(at: 0).isEmpty)
    #expect(await missing.drafts.clearedSlot(0))
  }

  @Test
  func `partial failure leaves the unread slot, and capacity pruning still removes higher indices`() async throws {
    for path in ReadPath.syncPaths {
      let failed = try await Fixture.make()
      try await failed.seed(name: "Keep", secret: secretB, message: "stay")
      _ = try await failed.store.saveChannel(
        radioID: failed.radioID,
        from: ChannelInfo(index: 1, name: "Unread", secret: secretA)
      )
      try await failed.store.saveMessage(.testChannelMessage(
        radioID: failed.radioID, channelIndex: 1, text: "unread"
      ))
      await failed.session.setStubbedChannels([
        0: ChannelInfo(index: 0, name: "Renamed", secret: secretB),
        1: ChannelInfo(index: 1, name: "ShouldNotLand", secret: secretLater)
      ])
      await failed.session.setGetChannelError(MeshCoreError.notConnected, at: 1)
      _ = try await failed.service.syncChannels(
        radioID: failed.radioID,
        maxChannels: 2,
        usePipelinedRead: path == .pipelined
      )
      let renamed = try #require(try await failed.store.fetchChannel(radioID: failed.radioID, index: 0))
      #expect(renamed.name == "Renamed", "\(path)")
      #expect(try await failed.messages(at: 0) == ["stay"], "\(path)")
      let unread = try #require(try await failed.store.fetchChannel(radioID: failed.radioID, index: 1))
      #expect(unread.name == "Unread", "\(path)")
      #expect(unread.secret == secretA, "\(path)")
      #expect(try await failed.messages(at: 1) == ["unread"], "\(path)")
    }

    let pruned = try await Fixture.make()
    try await pruned.seed(name: "InRange", secret: secretB, message: "stay")
    _ = try await pruned.store.saveChannel(
      radioID: pruned.radioID,
      from: ChannelInfo(index: 4, name: "Orphan", secret: secretA)
    )
    try await pruned.store.saveMessage(.testChannelMessage(
      radioID: pruned.radioID, channelIndex: 4, text: "pruned"
    ))
    await pruned.session.setStubbedChannels([
      0: ChannelInfo(index: 0, name: "InRange", secret: secretB)
    ])
    _ = try await pruned.service.syncChannels(radioID: pruned.radioID, maxChannels: 2, usePipelinedRead: false)
    #expect(try await pruned.store.fetchChannel(radioID: pruned.radioID, index: 4) == nil)
    #expect(try await pruned.messages(at: 4).isEmpty)
    #expect(try await pruned.messages(at: 0) == ["stay"])
  }

  @Test
  func `a second sync is rejected while the first read is in flight`() async throws {
    let fixture = try await Fixture.make()
    await fixture.session.holdGetChannel(at: 0)
    await fixture.session.setStubbedChannels([
      0: ChannelInfo(index: 0, name: "A", secret: secretA)
    ])
    let sync = Task {
      try await fixture.service.syncChannels(radioID: fixture.radioID, maxChannels: 1, usePipelinedRead: false)
    }
    _ = await fixture.session.waitForHeldGetChannel()
    do {
      _ = try await fixture.service.syncChannels(radioID: fixture.radioID, maxChannels: 1, usePipelinedRead: false)
      Issue.record("Expected syncAlreadyInProgress")
    } catch ChannelServiceError.syncAlreadyInProgress {
    } catch {
      Issue.record("Unexpected error: \(error)")
    }
    await fixture.session.releaseGetChannel(at: 0)
    _ = try await sync.value
  }

  @Test
  func `queued cancellation issues no command and a later write succeeds`() async throws {
    let fixture = try await Fixture.make()
    await fixture.session.holdNextSetChannel()
    let first = Task {
      try await fixture.service.setChannelWithSecret(
        radioID: fixture.radioID, index: 0, name: "First", secret: secretA
      )
    }
    await fixture.session.waitForSetChannelHold()
    let second = Task {
      try await fixture.service.setChannelWithSecret(
        radioID: fixture.radioID, index: 0, name: "Second", secret: secretB
      )
    }
    try await fixture.waitUntilWaiters(equal: 1)
    second.cancel()
    await fixture.session.releaseSetChannelHold()
    try await first.value
    await #expect(throws: CancellationError.self) {
      try await second.value
    }
    let names = await fixture.session.setChannelInvocations.map(\.name)
    #expect(names == ["First"])
    try await fixture.service.setChannel(
      radioID: fixture.radioID, index: 0, name: "Third", passphrase: "later"
    )
    let stored = try #require(try await fixture.store.fetchChannel(radioID: fixture.radioID, index: 0))
    #expect(stored.name == "Third")
    #expect(stored.secret == ChannelService.hashSecret("later"))
    #expect(await fixture.service.channelOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `a failed write releases the gate and cancelling retry leaves a later write free`() async throws {
    let failed = try await Fixture.make()
    await failed.session.setSetChannelError(MeshCoreError.notConnected)
    await #expect(throws: ChannelServiceError.self) {
      try await failed.service.setChannelWithSecret(
        radioID: failed.radioID, index: 0, name: "Nope", secret: secretA
      )
    }
    #expect(await failed.service.channelOperationWaiterCountForTesting() == 0)
    await failed.session.setSetChannelError(nil)
    try await failed.service.setChannelWithSecret(
      radioID: failed.radioID, index: 0, name: "After", secret: secretB
    )
    #expect(try await failed.store.fetchChannel(radioID: failed.radioID, index: 0)?.name == "After")

    let cancelled = try await Fixture.make()
    let retry = Task {
      try await cancelled.service.retryFailedChannels(radioID: cancelled.radioID, indices: [0])
    }
    retry.cancel()
    await #expect(throws: CancellationError.self) {
      try await retry.value
    }
    try await cancelled.service.setChannelWithSecret(
      radioID: cancelled.radioID, index: 0, name: "AfterCancel", secret: secretB
    )
    #expect(try await cancelled.store.fetchChannel(radioID: cancelled.radioID, index: 0)?.name == "AfterCancel")
    _ = try await cancelled.service.syncChannels(
      radioID: cancelled.radioID, maxChannels: 1, usePipelinedRead: false
    )
    #expect(try await cancelled.store.fetchChannel(radioID: cancelled.radioID, index: 0)?.name == "AfterCancel")
  }

  private func overlap(
    path: ReadPath,
    observation: SlotObservation,
    order: AdmissionOrder,
    mutation: LaterMutation,
    label: String
  ) async throws {
    let fixture = try await Fixture.make()
    switch mutation {
    case .setB:
      try await fixture.seed(name: "B", secret: secretB, message: "keep")
    case .clear:
      try await fixture.seed(name: "A", secret: secretA, message: "gone")
    }
    await fixture.installObservation(observation, secretA: secretA, secretLater: secretLater)

    switch order {
    case .observationFirst:
      try await fixture.duringLaterSlotHold(path, mutation: mutation, secretB: secretB)
    case .mutationFirst:
      try await fixture.whileMutationHoldsTheGate(path, mutation: mutation, secretB: secretB)
    }

    switch mutation {
    case .setB:
      try await fixture.expectSurvivor(name: "B", secret: secretB, message: "keep", label: label)
    case .clear:
      try await fixture.expectCleared(label: label)
    }
    let later = try await fixture.store.fetchChannel(radioID: fixture.radioID, index: 1)
    #expect(later?.name == "Later", "\(label)")
    #expect(later?.secret == secretLater, "\(label)")
  }
}

private enum ReadPath: String, CaseIterable {
  case serial
  case pipelined
  case retry

  static let syncPaths: [ReadPath] = [.serial, .pipelined]
}

private enum SlotObservation: String {
  case configuredA
  case empty
}

private enum AdmissionOrder: String, CaseIterable {
  case observationFirst
  case mutationFirst
}

private enum LaterMutation {
  case setB
  case clear
}

private struct Fixture: Sendable {
  let radioID: UUID
  let service: ChannelService
  let session: MockMeshCoreSession
  let store: PersistenceStore
  let drafts: DraftClearCapture

  static func make() async throws -> Fixture {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    let session = MockMeshCoreSession()
    let service = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let drafts = DraftClearCapture()
    await service.setSlotOccupantChangedHandler { _, indices in
      await drafts.record(indices)
    }
    return Fixture(radioID: radioID, service: service, session: session, store: store, drafts: drafts)
  }

  func seed(name: String, secret: Data, message: String) async throws {
    _ = try await store.saveChannel(
      radioID: radioID,
      from: ChannelInfo(index: 0, name: name, secret: secret)
    )
    try await store.saveMessage(.testChannelMessage(
      radioID: radioID, channelIndex: 0, text: message
    ))
  }

  func installObservation(_ observation: SlotObservation, secretA: Data, secretLater: Data) async {
    var channels: [UInt8: ChannelInfo] = [
      1: ChannelInfo(index: 1, name: "Later", secret: secretLater)
    ]
    if observation == .configuredA {
      channels[0] = ChannelInfo(index: 0, name: "A", secret: secretA)
    }
    await session.setStubbedChannels(channels)
  }

  /// Parks the read after slot 0 is visible to the service, or after the pipelined batch is snapshotted.
  func duringLaterSlotHold(_ path: ReadPath, mutation: LaterMutation, secretB: Data) async throws {
    switch path {
    case .serial, .retry:
      await session.holdGetChannel(at: 1)
    case .pipelined:
      await session.holdNextGetChannels()
    }
    let read = Task { try await self.runRead(path) }
    switch path {
    case .serial, .retry:
      let held = await session.waitForHeldGetChannel()
      #expect(held == 1)
    case .pipelined:
      await session.waitForGetChannelsHold()
    }
    try await perform(mutation, secretB: secretB)
    switch path {
    case .serial, .retry:
      await session.releaseGetChannel(at: 1)
    case .pipelined:
      await session.releaseGetChannels()
    }
    _ = try await read.value
  }

  /// Parks the mutation on its radio command, then lets the read snapshot the older channel before that command finishes.
  func whileMutationHoldsTheGate(_ path: ReadPath, mutation: LaterMutation, secretB: Data) async throws {
    await session.holdNextSetChannel()
    switch path {
    case .serial, .retry:
      await session.holdGetChannel(at: 0)
    case .pipelined:
      await session.holdNextGetChannels()
    }
    let write = Task { try await self.perform(mutation, secretB: secretB) }
    await session.waitForSetChannelHold()
    let read = Task { try await self.runRead(path) }
    switch path {
    case .serial, .retry:
      let held = await session.waitForHeldGetChannel()
      #expect(held == 0)
    case .pipelined:
      await session.waitForGetChannelsHold()
    }
    await session.releaseSetChannelHold()
    switch path {
    case .serial, .retry:
      await session.releaseGetChannel(at: 0)
    case .pipelined:
      await session.releaseGetChannels()
    }
    try await write.value
    _ = try await read.value
  }

  func perform(_ mutation: LaterMutation, secretB: Data) async throws {
    switch mutation {
    case .setB:
      try await service.setChannelWithSecret(
        radioID: radioID, index: 0, name: "B", secret: secretB
      )
    case .clear:
      try await service.clearChannel(radioID: radioID, index: 0)
    }
  }

  func runRead(_ path: ReadPath) async throws -> ChannelSyncResult {
    switch path {
    case .serial:
      try await service.syncChannels(radioID: radioID, maxChannels: 2, usePipelinedRead: false)
    case .pipelined:
      try await service.syncChannels(radioID: radioID, maxChannels: 2, usePipelinedRead: true)
    case .retry:
      try await service.retryFailedChannels(radioID: radioID, indices: [0, 1])
    }
  }

  func expectSurvivor(name: String, secret: Data, message: String, label: String) async throws {
    let stored = try #require(try await store.fetchChannel(radioID: radioID, index: 0), "\(label) store")
    #expect(stored.name == name, "\(label)")
    #expect(stored.secret == secret, "\(label)")
    let channels = await session.stubbedChannels
    let radio = channels[0]
    #expect(radio?.name == name, "\(label)")
    #expect(radio?.secret == secret, "\(label)")
    #expect(try await messages(at: 0) == [message], "\(label)")
    #expect(await drafts.clearedSlot(0) == false, "\(label)")
  }

  func expectCleared(label: String) async throws {
    #expect(try await store.fetchChannel(radioID: radioID, index: 0) == nil, "\(label)")
    #expect(try await messages(at: 0).isEmpty, "\(label)")
    let channels = await session.stubbedChannels
    let radio = channels[0]
    #expect(radio?.name.isEmpty == true, "\(label)")
    #expect(radio?.secret == Data(repeating: 0, count: ProtocolLimits.channelSecretSize), "\(label)")
  }

  func messages(at index: UInt8) async throws -> [String] {
    try await store.fetchMessages(radioID: radioID, channelIndex: index).map(\.text)
  }

  func waitUntilWaiters(equal expected: Int) async throws {
    try await waitUntil("channel operation waiter \(expected)") {
      let count = await service.channelOperationWaiterCountForTesting()
      return count == expected
    }
  }
}

private actor DraftClearCapture {
  private(set) var cleared: [Set<UInt8>] = []

  func record(_ indices: Set<UInt8>) {
    cleared.append(indices)
  }

  func clearedSlot(_ index: UInt8) -> Bool {
    cleared.contains { $0.contains(index) }
  }
}
