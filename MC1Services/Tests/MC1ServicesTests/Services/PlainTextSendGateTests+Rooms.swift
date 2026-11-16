import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

extension PlainTextSendGateTests {
  @Test
  func `room posts keep the room budget and a channel send proceeds during a dm hold`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x51)
    let accepted = try await harness.service.sendDirectMessage(text: "holding", to: contact)
    #expect(accepted.status == .sent)
    let sendsWhileHeld = await harness.sendCount()

    let channel = Task {
      try await harness.service.sendChannelMessage(text: "channel", channelIndex: 0, radioID: harness.radioID)
    }
    try await waitUntil("a channel send proceeds while the dm lease is held") {
      await harness.channelCount() == 1
    }
    #expect(await harness.sendCount() == sendsWhileHeld)
    _ = try await channel.value

    let roomSession = RemoteNodeSessionDTO(
      radioID: harness.radioID,
      publicKey: Data(repeating: 0x61, count: 32),
      name: "Room",
      role: .roomServer,
      permissionLevel: .readWrite
    )
    try await harness.store.saveRemoteNodeSessionDTO(roomSession)
    _ = try await harness.roomService.postMessage(sessionID: roomSession.id, text: "post")
    try await waitUntil("the room post waits for the dm lease") {
      await harness.gate.waiterCountForTesting == 1
    }
    #expect(await harness.sendCount() == sendsWhileHeld)

    let code = try #require(harness.script.codes().first)
    await harness.service.startEventMonitoring()
    await harness.service.waitForSubscriberCount(1)
    await harness.session.dispatchForTesting(.acknowledgement(code: code, tripTime: 9))
    try await waitUntil("the room post sends after the dm ack") {
      await harness.sendCount() > sendsWhileHeld
    }

    let limits = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { limits.stop() }
    let limitSession = RemoteNodeSessionDTO(
      radioID: limits.radioID,
      publicKey: Data(repeating: 0x62, count: 32),
      name: "Limits",
      role: .roomServer,
      permissionLevel: .admin
    )
    try await limits.store.saveRemoteNodeSessionDTO(limitSession)
    _ = try await limits.roomService.postMessage(sessionID: limitSession.id, text: "limits")
    try await waitUntil("the room post finishes its own budget") {
      let messages = try await limits.store.fetchRoomMessages(sessionID: limitSession.id)
      return messages.contains { $0.status == .sent || $0.status == .delivered }
    }
    let roomPackets = await limits.sendPackets()
    #expect(roomPackets.count == 5)
    let resetAt = await limits.resetIndex()
    #expect(resetAt == 4)

    let failed = RoomMessageDTO(
      sessionID: limitSession.id,
      authorKeyPrefix: Data(repeating: 0, count: 4),
      text: "retry",
      timestamp: 10,
      isFromSelf: true,
      status: .failed
    )
    try await limits.store.saveRoomMessage(failed)
    _ = try await limits.roomService.retryMessage(id: failed.id)
    let afterRetry = await limits.sendPackets().count
    #expect(afterRetry == 10)
  }

  @Test
  func `room posts queued together have distinct timestamps and keep them across attempts`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let room = RemoteNodeSessionDTO(
      radioID: harness.radioID,
      publicKey: Data(repeating: 0x63, count: 32),
      name: "Room",
      role: .roomServer,
      permissionLevel: .readWrite
    )
    try await harness.store.saveRemoteNodeSessionDTO(room)

    let owner = try await harness.gate.acquire()
    let first = try await harness.roomService.postMessage(sessionID: room.id, text: "First")
    try await waitUntil("the first post is queued") {
      await harness.gate.waiterCountForTesting == 1
    }
    let second = try await harness.roomService.postMessage(sessionID: room.id, text: "Second")
    try await waitUntil("the second post is queued") {
      await harness.gate.waiterCountForTesting == 2
    }

    // Give both sends a full wall-clock second in which to reproduce a timestamp collision.
    let now = Date().timeIntervalSince1970
    try await Task.sleep(for: .seconds(now.rounded(.down) + 1 - now))
    await harness.gate.stopIssuing(owner)
    try await waitUntil("both posts finish sending") {
      let messages = try await harness.store.fetchRoomMessages(sessionID: room.id)
      return messages.count { $0.status == .sent } == 2
    }

    let timestamps = await harness.sendPackets().map(wireTimestamp)
    #expect(timestamps.count == 10)
    let firstTimestamp = try #require(timestamps.first)
    let secondTimestamp = try #require(timestamps.last)
    #expect(firstTimestamp < secondTimestamp)
    #expect(timestamps.prefix(5).allSatisfy { $0 == firstTimestamp })
    #expect(timestamps.suffix(5).allSatisfy { $0 == secondTimestamp })
    #expect(try await harness.store.fetchRoomMessage(id: first.id)?.timestamp == first.timestamp)
    #expect(try await harness.store.fetchRoomMessage(id: second.id)?.timestamp == second.timestamp)
  }

  @Test(arguments: [false, true])
  func `room send queued before logout fails without sending`(isRetry: Bool) async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let room = RemoteNodeSessionDTO(
      radioID: harness.radioID,
      publicKey: Data(repeating: 0x64, count: 32),
      name: "Room",
      role: .roomServer,
      isConnected: true,
      permissionLevel: .readWrite
    )
    try await harness.store.saveRemoteNodeSessionDTO(room)

    let owner = try await harness.gate.acquire()
    let message: RoomMessageDTO
    let retry: Task<RoomMessageDTO, any Error>?
    if isRetry {
      message = RoomMessageDTO(
        sessionID: room.id,
        authorKeyPrefix: senderKey.prefix(4),
        text: "Retry",
        timestamp: 10,
        isFromSelf: true,
        status: .failed
      )
      try await harness.store.saveRoomMessage(message)
      retry = Task { [id = message.id] in try await harness.roomService.retryMessage(id: id) }
    } else {
      message = try await harness.roomService.postMessage(sessionID: room.id, text: "Post")
      retry = nil
    }
    try await waitUntil("the room send is parked") {
      await harness.gate.waiterCountForTesting == 1
    }
    try await harness.remoteNodeService.logout(sessionID: room.id)
    await harness.gate.stopIssuing(owner)

    if let retry {
      #expect(try await retry.value.status == .failed)
    } else {
      try await waitUntil("the queued post finishes") {
        try await harness.store.fetchRoomMessage(id: message.id)?.status != .pending
      }
      #expect(try await harness.store.fetchRoomMessage(id: message.id)?.status == .failed)
    }
    #expect(await harness.sendCount() == 0)
  }
}
