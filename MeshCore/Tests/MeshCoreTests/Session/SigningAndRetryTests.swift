import CryptoKit
import Foundation
@testable import MeshCore
import Testing

@Suite("Signing and retry workflows")
struct SigningAndRetryTests {
  @Test(arguments: [1, 2])
  func `retry observes an acknowledgement received before send completes`(attempts: Int) async throws {
    let (session, transport) = try await startedSession()
    let observed = await session.events(filter: .anyAcknowledgement)
    let ackObserved = Task { await observed.first { _ in true } }
    await transport.holdNextSend()
    let sending = Task {
      try await session.sendMessageWithRetry(
        to: Data(repeating: 2, count: 32), text: "Message", maxAttempts: attempts, timeout: attempts == 1 ? 1 : 0.05
      )
    }
    try await waitUntil { await transport.sendIsWaiting }
    await transport.simulateReceive(Data([0x06, 0, 1, 2, 3, 4, 50, 0, 0, 0]))
    await transport.simulateReceive(Data([0x82, attempts == 1 ? 1 : 9, 2, 3, 4, 50, 0, 0, 0]))
    #expect(await ackObserved.value != nil)
    await transport.releaseHeldSend()
    if attempts == 2 {
      try await waitUntil { await transport.sentData.count == 2 }
      await transport.simulateReceive(Data([0x82, 1, 2, 3, 4, 50, 0, 0, 0]))
      await transport.simulateReceive(Data([0x06, 0, 5, 6, 7, 8, 50, 0, 0, 0]))
      await transport.simulateReceive(Data([0x82, 5, 6, 7, 8, 50, 0, 0, 0]))
    }
    let result = try await sending.value
    #expect(result?.expectedAck == (attempts == 1 ? Data([1, 2, 3, 4]) : Data([5, 6, 7, 8])))
    #expect(await transport.sentData.count == attempts)
    await session.stop()
  }

  @Test(arguments: [true, false])
  func `cancelling retry releases its acknowledgement subscription`(duringSend: Bool) async throws {
    let (session, transport) = try await startedSession()
    if duringSend { await transport.holdNextSend() }
    let sending = Task {
      try await session.sendMessageWithRetry(
        to: Data(repeating: 2, count: 32), text: "Message", maxAttempts: 1, timeout: 1
      )
    }
    try await waitUntil { await transport.sentData.count == 1 }
    if duringSend {
      try await waitUntil { await transport.sendIsWaiting }
    } else {
      await transport.simulateReceive(Data([0x06, 0, 1, 2, 3, 4, 50, 0, 0, 0]))
      try await waitUntil { await session.dispatcher.subscriberCountForTest == 1 }
    }
    sending.cancel()
    await #expect(throws: CancellationError.self) { _ = try await sending.value }
    if duringSend {
      await transport.simulateReceive(Data([0x06, 0, 1, 2, 3, 4, 50, 0, 0, 0]))
      await transport.releaseHeldSend()
    }
    try await waitUntil { await session.dispatcher.subscriberCountForTest == 0 }
    #expect(await transport.sentData.count == 1)
    await session.stop()
  }

  @Test(arguments: [false, true])
  func `concurrent signing workflows each sign their own input`(cancelFirst: Bool) async throws {
    let (session, transport) = try await startedSession()
    let firstData = Data("AAAA".utf8)
    let secondData = Data("BBBB".utf8)
    let first = Task { try await session.sign(firstData, chunkSize: 2, timeout: 1) }
    try await waitUntil { await transport.sentData.count == 1 }
    let second = Task { try await session.sign(secondData, chunkSize: 2, timeout: 1) }
    await Task.yield()
    if cancelFirst { first.cancel() }

    let key = Curve25519.Signing.PrivateKey()
    var buffer: Data?
    var signedInputs: [Data] = []
    for index in 0..<8 {
      try await waitUntil { await transport.sentData.count > index }
      let frame = await transport.sentData[index]
      switch frame.first {
      case CommandCode.signStart.rawValue:
        buffer = Data()
        await session.dispatchForTesting(.signStart(maxLength: 8192))
      case CommandCode.signData.rawValue:
        buffer?.append(contentsOf: frame.dropFirst())
        await transport.simulateOK()
      case CommandCode.signFinish.rawValue:
        if let input = buffer {
          signedInputs.append(input)
          buffer = nil
          try await session.dispatchForTesting(.signature(key.signature(for: input)))
        } else {
          await transport.simulateError(code: ErrorCode.badState.rawValue)
        }
      default:
        Issue.record("Unexpected signing command")
      }
    }
    #expect(signedInputs == [firstData, secondData])
    if cancelFirst {
      await #expect(throws: CancellationError.self) { _ = try await first.value }
    } else if let signature = try? await first.value {
      #expect(key.publicKey.isValidSignature(signature, for: firstData))
    } else {
      Issue.record("First signing workflow failed")
    }
    if let signature = try? await second.value {
      #expect(key.publicKey.isValidSignature(signature, for: secondData))
    } else {
      Issue.record("Second signing workflow failed")
    }
    await session.stop()
  }

  private func startedSession() async throws -> (MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: 1))
    let start = Task { try await session.start() }
    try await waitUntil { await transport.sentData.count == 1 }
    var selfInfo = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    selfInfo.append(Data(repeating: 1, count: 32))
    selfInfo.append(Data(repeating: 0, count: 12))
    selfInfo.append(contentsOf: [0x38, 0xF6, 0x0D, 0, 0x48, 0xE8, 1, 0, 7, 5])
    await transport.simulateReceive(selfInfo)
    try await start.value
    await transport.clearSentData()
    return (session, transport)
  }
}
