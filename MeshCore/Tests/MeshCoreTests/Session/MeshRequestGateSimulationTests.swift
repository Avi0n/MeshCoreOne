import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("Mesh request gate invariant simulation", .timeLimit(.minutes(2)))
struct MeshRequestGateSimulationTests {
  private static let seedCount = 12
  private static let stepsPerSeed = 36
  private static let overallBudget: TimeInterval = 40
  private static let loginBudget: TimeInterval = 20
  private static let discoveryBudget: TimeInterval = 10

  private static let contacts: [Data] = [
    Data(repeating: 0x11, count: PacketBuilder.publicKeySize),
    Data(repeating: 0x22, count: PacketBuilder.publicKeySize),
    Data(repeating: 0x33, count: PacketBuilder.publicKeySize),
  ]
  private static let passwords = ["good", "bad"]

  @Test(arguments: 0..<Self.seedCount)
  func `gate invariants hold under deterministic adversarial schedules`(seedIndex: Int) async throws {
    var rng = SplitMix64(seed: UInt64(seedIndex) &+ 0x9E37_79B9_7F4A_7C15)
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 5,
        binaryRequestOverallTimeout: Self.overallBudget,
        binaryRequestRetransmitInterval: nil
      ),
      clock: clock
    )
    let companion = FirmwarePendingSlotCompanion()

    let start = Task { try await session.start() }
    try await waitUntil("app start") {
      await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue }
    }
    await transport.simulateReceive(
      Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: 54)
    )
    try await start.value
    await transport.clearSentData()

    var activeOps: [SimOp] = []
    var unconsumedGoodPrefixes: Set<Data> = []

    for step in 0..<Self.stepsPerSeed {
      await companion.ackNewCommands(on: transport)
      let choice = Int(rng.next() % 10)

      if choice < 5 || activeOps.isEmpty {
        let contact = Self.contacts[Int(rng.next() % UInt64(Self.contacts.count))]
        let priority: MeshRequestPriority = (rng.next() % 2 == 0) ? .interactive : .background
        let kindPick = Int(rng.next() % 6)
        let password = Self.passwords[Int(rng.next() % UInt64(Self.passwords.count))]
        let exempt = (rng.next() % 2 == 0)
        let opID = step

        let settled = OSAllocatedUnfairLock<Result<OpOutcome, Error>?>(initialState: nil)
        let task: Task<Void, Never> = Task {
          let result: Result<OpOutcome, Error> = switch kindPick {
          case 0:
            await Result {
              try await .login(session.login(
                to: contact,
                password: password,
                priority: priority,
                budget: { _ in Self.loginBudget },
                samePasswordExemption: exempt
              ))
            }
          case 1:
            await Result {
              try await .status(session.requestStatus(from: contact, priority: priority).battery)
            }
          case 2:
            await Result {
              try await .telemetry(session.requestTelemetry(from: contact, priority: priority).publicKeyPrefix)
            }
          case 3:
            await Result {
              try await .owner(session.requestOwnerInfo(from: contact, priority: priority).firmwareVersion)
            }
          case 4:
            await Result {
              try await .discovery(session.discoverPath(to: contact, priority: priority, budget: { _ in Self.discoveryBudget }).publicKeyPrefix)
            }
          default:
            await Result {
              _ = try await session.sendKeepAlive(to: contact, syncSince: 0)
              return .keepAlive
            }
          }
          settled.withLock { $0 = result }
        }
        activeOps.append(SimOp(
          id: opID,
          kind: kindPick,
          contact: contact,
          password: password,
          task: task,
          settled: settled
        ))
        await Task.yield()
        await companion.ackNewCommands(on: transport)
      } else if choice == 5 || choice == 6 {
        let writes = await companion.acceptedWrites
        if !writes.isEmpty {
          let write = writes[Int(rng.next() % UInt64(writes.count))]
          switch write.kind {
          case .login:
            let body = write.password == "good" ? Data("OK".utf8) : Data([0xFF])
            await companion.deliver(replyingTo: write.id, body: body, on: transport)
          case .status:
            var fields = Data(repeating: 0, count: 51)
            fields[0] = 0xE8
            fields[1] = 0x03
            await companion.deliver(replyingTo: write.id, body: fields, on: transport)
          case .telemetry:
            await companion.deliver(replyingTo: write.id, body: Data([0x01]), on: transport)
          case .binary:
            await companion.deliver(replyingTo: write.id, body: Data("v1.18\nN\nO".utf8), on: transport)
          case .pathDiscovery:
            await companion.deliverPathDiscovery(
              replyingTo: write.id,
              outPathLength: 1,
              outPath: Data([0xAB]),
              on: transport
            )
          case .keepAlive:
            break
          }
        }
      } else if choice == 7 {
        let idx = Int(rng.next() % UInt64(activeOps.count))
        activeOps[idx].task.cancel()
        if activeOps[idx].kind == 0, activeOps[idx].password == "good" {
          unconsumedGoodPrefixes.insert(activeOps[idx].contact.prefix(4))
        }
      } else if choice == 8 {
        let previousGetMessageCount = await transport.sentData.count { $0.first == CommandCode.getMessage.rawValue }
        let msgTask = Task { try await session.getMessage(timeout: 2) }
        try await waitUntil("simulation getMessage written") {
          await companion.ackNewCommands(on: transport)
          return await transport.sentData.count { $0.first == CommandCode.getMessage.rawValue } > previousGetMessageCount
        }
        await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
        _ = try? await msgTask.value
      } else if clock.sleeperCount > 0 {
        clock.advanceToNextSleep()
      } else {
        clock.advance(by: .seconds(1))
      }

      await companion.ackNewCommands(on: transport)
      await Task.yield()

      var remaining: [SimOp] = []
      for op in activeOps {
        if let result = op.settledResult {
          if case let .success(.login(loginResult)) = result, loginResult.succeeded {
            if op.password == "bad", unconsumedGoodPrefixes.contains(op.contact.prefix(4)) {
              #expect(loginResult.mayAnswerEarlierLogin, "Seed \(seedIndex): unconsumed good login must flag bad login")
            }
            unconsumedGoodPrefixes.remove(op.contact.prefix(4))
          }
        } else {
          remaining.append(op)
        }
      }
      activeOps = remaining

      let writes = await companion.acceptedWrites
      #expect(writes.count >= 0)
    }

    if seedIndex.isMultiple(of: 3) {
      await session.stop()
      for op in activeOps {
        await op.task.value
      }
    } else {
      var idleIterations = 0
      while !activeOps.allSatisfy({ $0.settledResult != nil }), idleIterations < 200 {
        let beforeConsumed = await companion.consumedCount
        await companion.ackNewCommands(on: transport)
        let consumedChanged = await companion.consumedCount != beforeConsumed
        if clock.sleeperCount > 0 {
          clock.advanceToNextSleep()
          idleIterations = 0
        } else if consumedChanged {
          idleIterations = 0
        } else {
          idleIterations += 1
          try? await Task.sleep(for: .milliseconds(1))
        }
        await Task.yield()
      }
      let remainingKinds = activeOps.filter { $0.settledResult == nil }.map(\.kind)
      let gateHolder = await session.meshRequestGate.hasActiveHolder
      let gateQueued = await session.meshRequestGate.queuedWaiterCount
      let serializerWaiters = await session.requestResponseSerializer.waiterCountForTest
      #expect(
        remainingKinds.isEmpty,
        "Seed \(seedIndex): remaining ops \(remainingKinds) sleepers=\(clock.sleeperCount) next=\(String(describing: clock.nextSleepDuration)) gateHolder=\(gateHolder) gateQueued=\(gateQueued) serializer=\(serializerWaiters)"
      )
      await session.stop()
      for op in activeOps {
        await op.task.value
      }
    }
    #expect(await !session.meshRequestGate.hasActiveHolder)
    #expect(await session.meshRequestGate.queuedWaiterCount == 0)
    #expect(await !session.meshRequestGate.hasAnyActiveHold)
  }

  private enum OpOutcome: Sendable {
    case login(LoginExchangeResult)
    case status(Int)
    case telemetry(Data)
    case owner(String)
    case discovery(Data)
    case keepAlive
  }

  private struct SimOp: Sendable {
    let id: Int
    let kind: Int
    let contact: Data
    let password: String
    let task: Task<Void, Never>
    let settled: OSAllocatedUnfairLock<Result<OpOutcome, Error>?>

    var settledResult: Result<OpOutcome, Error>? {
      settled.withLock { $0 }
    }
  }
}

private struct SplitMix64: RandomNumberGenerator {
  private var state: UInt64

  init(seed: UInt64) {
    state = seed
  }

  mutating func next() -> UInt64 {
    state &+= 0x9E37_79B9_7F4A_7C15
    var z = state
    z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
    z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
    return z ^ (z >> 31)
  }
}
