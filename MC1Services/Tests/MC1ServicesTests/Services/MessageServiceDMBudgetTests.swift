import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("DM send budget")
@MainActor
struct MessageServiceDMBudgetTests {
  private let senderKey = Data(repeating: 0xAB, count: 32)

  @Test(arguments: DMSendBudget.directRange, DMSendBudget.floodRange)
  func `each budget sends its attempts and resets only before fallback`(
    directAttempts: Int,
    floodAttempts: Int
  ) async throws {
    let observed = try await exhaust(budget: DMSendBudget(directAttempts: directAttempts, floodAttempts: floodAttempts))
    let totalAttempts = directAttempts + floodAttempts
    #expect(observed.attempts == Array(0..<UInt8(totalAttempts)))
    #expect(observed.resetIndexes == (floodAttempts > 0 ? [directAttempts] : []))
    #expect(observed.retryMaxAttempts == Array(repeating: totalAttempts - 1, count: totalAttempts - 1))
  }

  @Test
  func `a rejected path reset still uses the remaining attempts without another reset`() async throws {
    let observed = try await exhaust(
      budget: DMSendBudget(directAttempts: 2, floodAttempts: 2),
      resetPathFails: true
    )
    #expect(observed.attempts == [0, 1, 2, 3])
    #expect(observed.resetIndexes == [2])
  }

  @Test
  func `the retry loop reads the budget provider once`() async throws {
    let calls = OSAllocatedUnfairLock(initialState: 0)
    let observed = try await exhaust(budgetProvider: {
      calls.withLock { count in
        count += 1
        return count == 1
          ? .singleAttempt
          : .default
      }
    })
    #expect(calls.withLock { $0 } == 1)
    #expect(observed.attempts == [0])
    #expect(observed.resetIndexes.isEmpty)
  }

  private struct Observation {
    var attempts: [UInt8]
    var resetIndexes: [Int]
    var retryMaxAttempts: [Int]
  }

  private func exhaust(
    budget: DMSendBudget,
    resetPathFails: Bool = false
  ) async throws -> Observation {
    try await exhaust(budgetProvider: { budget }, resetPathFails: resetPathFails)
  }

  private func exhaust(
    budgetProvider: @escaping @Sendable () -> DMSendBudget,
    resetPathFails: Bool = false
  ) async throws -> Observation {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: 10))
    let start = Task { try await session.start() }
    try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket(publicKey: senderKey))
    try await start.value
    defer { Task { await session.stop() } }

    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let service = MessageService(
      session: session,
      dataStore: store,
      contactService: nil,
      config: MessageServiceConfig(minTimeout: 0),
      dmSendBudget: budgetProvider
    )
    let events = service.statusEvents()
    let contact = ContactDTO.testContact(radioID: UUID(), publicKey: Data(repeating: 0x42, count: 32))
    let responder = Task { await answerCommands(on: transport, after: 1, resetPathFails: resetPathFails) }
    defer { responder.cancel() }

    let sent = try await service.sendMessageWithRetry(text: "budget", to: contact)
    #expect(sent.status == .sent)
    responder.cancel()

    let packets = await transport.sentData
    var attempts: [UInt8] = []
    var resetIndexes: [Int] = []
    var directBeforeReset = 0
    for packet in packets {
      switch packet.first {
      case CommandCode.sendMessage.rawValue:
        attempts.append(packet.count > 2 ? packet[2] : 255)
        if resetIndexes.isEmpty { directBeforeReset += 1 }
      case CommandCode.resetPath.rawValue:
        resetIndexes.append(directBeforeReset)
      default:
        break
      }
    }
    let retries = await service.drainStatusEvents(events).retryUpdates.map(\.maxAttempts)
    return Observation(attempts: attempts, resetIndexes: resetIndexes, retryMaxAttempts: retries)
  }

  private func answerCommands(on transport: MockTransport, after answered: Int, resetPathFails: Bool) async {
    var responded = answered
    while !Task.isCancelled {
      let packets = await transport.sentData
      guard packets.count > responded else {
        try? await Task.sleep(for: .milliseconds(2))
        continue
      }
      for packet in packets[responded...] {
        switch packet.first {
        case CommandCode.resetPath.rawValue:
          if resetPathFails {
            await transport.simulateReceive(Data([ResponseCode.error.rawValue]))
          } else {
            await transport.simulateOK()
          }
        case CommandCode.getContactByKey.rawValue:
          await transport.simulateReceive(Data([ResponseCode.error.rawValue]))
        default:
          var frame = Data([ResponseCode.messageSent.rawValue, 0])
          frame.append(Data([0xA1, 0xB2, 0xC3, 0xD4]))
          frame.append(contentsOf: withUnsafeBytes(of: UInt32(10).littleEndian) { Array($0) })
          await transport.simulateReceive(frame)
        }
      }
      responded = packets.count
    }
  }

  private func selfInfoPacket(publicKey: Data) -> Data {
    var payload = Data([1, 22, 22])
    payload.append(publicKey)
    payload.append(Data(repeating: 0, count: 8))
    payload.append(contentsOf: [0, 0, 0])
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    payload.append(contentsOf: [7, 5])
    payload.append(contentsOf: "Test".utf8)
    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }
}
