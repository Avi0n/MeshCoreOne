import Foundation
@testable import MeshCore
import Testing

@Suite("CLI request provenance")
struct CLIProvenanceSessionTests {
  @Test
  func `bare error cannot reject tagged command`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport, configuration: SessionConfiguration(defaultTimeout: 1)
    )
    let start = Task { try await session.start() }
    do {
      try await waitUntil("appStart was physically sent") { await transport.sentData.count == 1 }
      await transport.simulateReceive(Self.selfInfoPacket())
      try await start.value
      let command = Task { try await session.runCliCommand("AD|set wifi.enabled 0") }
      do {
        try await waitUntil("tagged setter is physically submitted") { await transport.sentData.count == 2 }
        await transport.simulateReceive(Data([ResponseCode.error.rawValue, 1]))
        await transport.simulateReceive(Self.reply("AD|> wifi.enabled updated (reboot to apply)"))
        let result = await command.result
        switch result {
        case let .success(reply):
          #expect(reply == "AD|> wifi.enabled updated (reboot to apply)")
        case .failure:
          Issue.record("A bare device error cannot identify a tagged command as rejected")
        }
        let later = Task { try await session.runCliCommand("AE|get wifi.enabled") }
        do {
          try await waitUntil("later command physically submits") { await transport.sentData.count == 3 }
          await transport.simulateReceive(Self.reply("AE|> 0"))
          #expect(try await later.value == "AE|> 0")
        } catch {
          await session.stop()
          _ = await later.result
          throw error
        }
      } catch {
        await session.stop()
        _ = await command.result
        throw error
      }
      await session.stop()
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
    } catch {
      await session.stop()
      _ = await start.result
      await session.waitForQuiescence()
      throw error
    }
  }

  @Test(arguments: [false, true])
  func `explicit prefix cannot be reused`(_ firstTimedOut: Bool) async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport, configuration: SessionConfiguration(defaultTimeout: 0.1)
    )
    let start = Task { try await session.start() }
    do {
      try await waitUntil("appStart was physically sent") { await transport.sentData.count == 1 }
      await transport.simulateReceive(Self.selfInfoPacket())
      try await start.value
      let first = Task { try await session.runCliCommand("AF|get wifi.enabled") }
      do {
        try await waitUntil("first use owns the prefix") { await transport.sentData.count == 2 }
        if !firstTimedOut { await transport.simulateReceive(Self.reply("AF|> 1")) }
        let result = await first.result
        if !firstTimedOut, case .failure = result { Issue.record("First prefix use should be usable") }
        if firstTimedOut, case .success = result { Issue.record("Missing first reply should time out") }
        let before = await transport.sentData.count

        await #expect {
          try await session.runCliCommand("AF|get wifi.ssid")
        } throws: { error in
          guard let error = error as? MeshCoreError, case .invalidInput = error else { return false }
          return true
        }

        #expect(await transport.sentData.count == before)
        let afterReuse = await transport.sentData.count
        let later = Task { try await session.runCliCommand("AG|get wifi.enabled") }
        do {
          try await waitUntil("a fresh prefix remains usable") { await transport.sentData.count == afterReuse + 1 }
          await transport.simulateReceive(Self.reply("AG|> 1"))
          #expect(try await later.value == "AG|> 1")
        } catch {
          await session.stop()
          _ = await later.result
          throw error
        }
      } catch {
        await session.stop()
        _ = await first.result
        throw error
      }
      await session.stop()
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
    } catch {
      await session.stop()
      _ = await start.result
      await session.waitForQuiescence()
      throw error
    }
  }

  @Test(arguments: [false, true])
  func `timed out reply cannot certify next request`(_ nextIsPrefixed: Bool) async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport, configuration: SessionConfiguration(defaultTimeout: 0.1)
    )
    let start = Task { try await session.start() }
    do {
      try await waitUntil("appStart was physically sent") { await transport.sentData.count == 1 }
      await transport.simulateReceive(Self.selfInfoPacket())
      try await start.value
      await #expect {
        try await session.runCliCommand("AA|get wifi.pwd")
      } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }
      let command = nextIsPrefixed ? "AB|get wifi.ssid" : "get wifi.enabled"
      let expected = nextIsPrefixed ? "AB|> configured" : "> 1"
      let next = Task { try await session.runCliCommand(command) }
      do {
        try await waitUntil("next CLI request owns its wire exchange") { await transport.sentData.count == 3 }
        await transport.simulateReceive(Self.reply("AA|> synthetic-private-value"))
        await transport.simulateReceive(Self.reply(expected))
        let actual = try await next.value
        let belongsToCurrentRequest = actual == expected
        #expect(belongsToCurrentRequest)
        let later = Task { try await session.runCliCommand("AC|get wifi.enabled") }
        do {
          try await waitUntil("a later actual command is submitted") { await transport.sentData.count == 4 }
          await transport.simulateReceive(Self.reply("AC|> 1"))
          #expect(try await later.value == "AC|> 1")
        } catch {
          await session.stop()
          _ = await later.result
          throw error
        }
      } catch {
        await session.stop()
        _ = await next.result
        throw error
      }
      await session.stop()
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
      #expect(await transport.isConnected == false)
    } catch {
      await session.stop()
      _ = await start.result
      await session.waitForQuiescence()
      throw error
    }
  }

  private static func reply(_ text: String) -> Data {
    Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)
  }

  private static func selfInfoPacket() -> Data {
    var reply = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    reply.append(Data(repeating: 1, count: 32))
    reply.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      reply.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    reply.append(contentsOf: [7, 5])
    return reply
  }
}
