import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Synchronization
import Testing

@Suite("CLI correlation scope lifetime", .timeLimit(.minutes(1)))
@MainActor
struct CLIProvenanceScopeTests {
  private static let commandTimeout: TimeInterval = 10
  @Test
  func `exhaustion rejects typed command before wire and fresh owner works`() async throws {
    let scope = RadioCLICorrelationScope()
    var prefixes: Set<String> = []
    for _ in 0..<RadioCLICorrelationScope.capacity {
      try prefixes.insert(scope.allocate())
    }
    #expect(prefixes.count == RadioCLICorrelationScope.capacity)
    let exhausted = try await Harness.make(scope: scope)
    do {
      let before = await exhausted.transport.sentData.count
      await #expect {
        try await exhausted.session.runCorrelatedCliCommand("get wifi.pwd")
      } throws: { error in
        guard let error = error as? MeshCoreError, case .invalidInput = error else { return false }
        return true
      }
      #expect(await exhausted.transport.sentData.count == before)
      await exhausted.close()
    } catch {
      await exhausted.close()
      throw error
    }
    let fresh = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let before = await fresh.transport.sentData.count
      let command = fresh.start { try await fresh.session.runCorrelatedCliCommand("get wifi.enabled") }
      try await waitUntil { await fresh.transport.sentData.count == before + 1 }
      let prefix = try #require(await fresh.lastPrefix())
      await fresh.transport.simulateReceive(Self.reply(prefix + "> 1"))
      #expect(try await command.value == "> 1")
      await fresh.close()
    } catch {
      await fresh.close()
      throw error
    }
  }

  @Test
  func `shared scope rejects timed out retired session password`() async throws {
    let scope = RadioCLICorrelationScope()
    let first = try await Harness.make(scope: scope)
    let previousPrefix: String
    do {
      let before = await first.transport.sentData.count
      let password = first.start { try await first.session.runCorrelatedCliCommand("get wifi.pwd") }
      try await waitUntil { await first.transport.sentData.count == before + 1 }
      previousPrefix = try #require(await first.lastPrefix())
      try await first.expireParkedCommand()
      await #expect { try await password.value } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }
      await first.close()
    } catch {
      await first.close()
      throw error
    }
    let next = try await Harness.make(scope: scope)
    do {
      let before = await next.transport.sentData.count
      let ssid = next.start { try await next.session.runCorrelatedCliCommand("get wifi.ssid") }
      try await waitUntil { await next.transport.sentData.count == before + 1 }
      let prefix = try #require(await next.lastPrefix())
      #expect(prefix != previousPrefix)
      await next.transport.simulateReceive(Self.reply(previousPrefix + "> synthetic-private-value"))
      await next.transport.simulateReceive(Self.reply(prefix + "> configured"))
      let belongsToCurrentRequest = try await ssid.value == "> configured"
      #expect(belongsToCurrentRequest)
      let laterBefore = await next.transport.sentData.count
      let later = next.start { try await next.session.runCorrelatedCliCommand("get wifi.enabled") }
      try await waitUntil { await next.transport.sentData.count == laterBefore + 1 }
      let laterPrefix = try #require(await next.lastPrefix())
      await next.transport.simulateReceive(Self.reply(laterPrefix + "> 1"))
      #expect(try await later.value == "> 1")
      await next.close()
    } catch {
      await next.close()
      throw error
    }
  }

  @Test
  func `raw pipe value and multibyte prefix keep their bytes`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let before = await harness.transport.sentData.count
      let raw = harness.start { try await harness.session.runCliCommand("get wifi.ssid") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      await harness.transport.simulateReceive(Self.reply("> |network"))
      #expect(try await raw.value == "> |network")
      let original = "  é|get wifi.ssid"
      let tagged = harness.start { try await harness.session.runCliCommand(original) }
      try await waitUntil { await harness.transport.sentData.count == before + 2 }
      let actual = await harness.transport.sentData.last
      #expect(actual == PacketBuilder.runCliCommand(original))
      await harness.transport.simulateReceive(Self.reply("é|> configured"))
      #expect(try await tagged.value == "é|> configured")
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `unprefixed timeout blocks raw authority but typed read still works`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let sentBeforeTimeout = await harness.transport.sentData.count
      let timedOut = harness.start { try await harness.session.runCliCommand("get wifi.pwd") }
      try await waitUntil { await harness.transport.sentData.count == sentBeforeTimeout + 1 }
      try await harness.expireParkedCommand()
      await #expect { try await timedOut.value } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }
      let before = await harness.transport.sentData.count
      await #expect {
        try await harness.session.runCliCommand("get wifi.ssid")
      } throws: { error in
        guard let error = error as? MeshCoreError, case .invalidInput = error else { return false }
        return true
      }
      #expect(await harness.transport.sentData.count == before)
      let typed = harness.start { try await harness.session.runCorrelatedCliCommand("get wifi.ssid") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      let prefix = try #require(await harness.lastPrefix())
      await harness.transport.simulateReceive(Self.reply("> synthetic-private-value"))
      await harness.transport.simulateReceive(Self.reply(prefix + "> configured"))
      let currentValue = try await typed.value == "> configured"
      #expect(currentValue)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `typed null text is rejected before wire and valid typed read works`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let before = await harness.transport.sentData.count
      var rejected = false
      do {
        _ = try await harness.session.runCorrelatedCliCommand("set wifi.pwd synthetic\0truncated")
      } catch let error as MeshCoreError {
        if case .invalidInput = error { rejected = true } else { throw error }
      }
      #expect(rejected)
      #expect(await harness.transport.sentData.count == before)
      let typed = harness.start { try await harness.session.runCorrelatedCliCommand("get wifi.enabled") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      let prefix = try #require(await harness.lastPrefix())
      await harness.transport.simulateReceive(Self.reply(prefix + "> 1"))
      #expect(try await typed.value == "> 1")
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `canonically equivalent raw prefixes keep distinct wire identity`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    let prefixes = ["\u{0340}|", "\u{0300}|"]
    do {
      for prefix in prefixes {
        let command = prefix + "get wifi.ssid"
        let expected = prefix + "> configured"
        let before = await harness.transport.sentData.count
        let finished = Mutex(false)
        let raw = harness.start {
          defer { finished.withLock { $0 = true } }
          return try await harness.session.runCliCommand(command)
        }
        try await waitUntil {
          await harness.transport.sentData.count == before + 1 || finished.withLock { $0 }
        }
        let actualPacket = await harness.transport.sentData.last
        let submittedExactWireBytes = actualPacket == PacketBuilder.runCliCommand(command)
        #expect(submittedExactWireBytes)
        if submittedExactWireBytes {
          await harness.transport.simulateReceive(Self.reply(expected))
        } else if !finished.withLock({ $0 }) {
          try await harness.expireParkedCommand()
        }
        switch await raw.result {
        case let .success(reply):
          let reflectedExactPrefixBytes = reply.utf8.elementsEqual(expected.utf8)
          #expect(reflectedExactPrefixBytes)
        case .failure:
          Issue.record("Distinct wire prefix bytes must remain usable")
        }
      }
      for prefix in prefixes {
        let before = await harness.transport.sentData.count
        await #expect {
          try await harness.session.runCliCommand(prefix + "get wifi.enabled")
        } throws: { error in
          guard let error = error as? MeshCoreError, case .invalidInput = error else { return false }
          return true
        }
        #expect(await harness.transport.sentData.count == before)
      }
      let before = await harness.transport.sentData.count
      let later = harness.start { try await harness.session.runCorrelatedCliCommand("get wifi.enabled") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      let prefix = try #require(await harness.lastPrefix())
      await harness.transport.simulateReceive(Self.reply(prefix + "> 1"))
      #expect(try await later.value == "> 1")
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `fresh correlated read accepts an untagged reply`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let before = await harness.transport.sentData.count
      let command = harness.start { try await harness.session.runCorrelatedCliCommand("get tz.offset") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      await harness.transport.simulateReceive(Self.reply("> -8"))
      #expect(try await command.value == "> -8")
      let uncertain = await harness.session.requestResponseSerializer.responseAttributionIsUncertain()
      #expect(uncertain == false)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `correlated timeout still rejects a later untagged reply`() async throws {
    let harness = try await Harness.make(scope: RadioCLICorrelationScope())
    do {
      let sentBeforeTimeout = await harness.transport.sentData.count
      let timedOut = harness.start { try await harness.session.runCorrelatedCliCommand("get wifi.pwd") }
      try await waitUntil { await harness.transport.sentData.count == sentBeforeTimeout + 1 }
      try await harness.expireParkedCommand()
      await #expect { try await timedOut.value } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }
      let before = await harness.transport.sentData.count
      let typed = harness.start { try await harness.session.runCorrelatedCliCommand("get wifi.ssid") }
      try await waitUntil { await harness.transport.sentData.count == before + 1 }
      let prefix = try #require(await harness.lastPrefix())
      await harness.transport.simulateReceive(Self.reply("> synthetic-private-value"))
      await harness.transport.simulateReceive(Self.reply(prefix + "> configured"))
      #expect(try await typed.value == "> configured")
      let uncertain = await harness.session.requestResponseSerializer.responseAttributionIsUncertain()
      #expect(uncertain)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  private static func reply(_ text: String) -> Data {
    Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)
  }

  @MainActor
  private final class Harness {
    let transport: MockTransport
    let session: MeshCoreSession
    let clock: TestClock
    private var workers: [Task<String, any Error>] = []

    private init(transport: MockTransport, session: MeshCoreSession, clock: TestClock) {
      self.transport = transport
      self.session = session
      self.clock = clock
    }

    static func make(scope: RadioCLICorrelationScope) async throws -> Harness {
      let transport = MockTransport()
      let clock = TestClock()
      let session = MeshCoreSession(
        transport: transport,
        configuration: SessionConfiguration(defaultTimeout: commandTimeout, clientIdentifier: "MCTst"),
        clock: clock,
        radioCLICorrelationScope: scope
      )
      let start = Task { try await session.start() }
      do {
        try await waitUntil { await transport.sentData.count == 1 }
        var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
        packet.append(Data(repeating: 1, count: 32))
        packet.append(Data(repeating: 0, count: 12))
        for value: UInt32 in [915_000, 125_000] {
          packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
        }
        packet.append(contentsOf: [7, 5])
        await transport.simulateReceive(packet)
        try await start.value
        try #require(clock.sleeperCount == 0)
        return Harness(transport: transport, session: session, clock: clock)
      } catch {
        await session.stop()
        _ = await start.result
        await session.waitForQuiescence()
        throw error
      }
    }

    func expireParkedCommand() async throws {
      let clock = clock
      try await waitUntil("command timeout should park on the test clock") { clock.sleeperCount == 1 }
      clock.advance(by: .seconds(CLIProvenanceScopeTests.commandTimeout))
    }

    func start(_ body: @escaping @MainActor @Sendable () async throws -> String) -> Task<String, any Error> {
      let worker = Task { try await body() }
      workers.append(worker)
      return worker
    }

    func lastPrefix() async -> String? {
      guard let command = await transport.sentData.last else { return nil }
      return RadioCLICorrelationScope.commandPrefix(String(decoding: command.dropFirst(), as: UTF8.self))
    }

    func close() async {
      await session.stop()
      for worker in workers {
        _ = await worker.result
      }
      workers.removeAll()
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
      #expect(await transport.isConnected == false)
    }
  }
}
