import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Mesh request gate scenarios", .timeLimit(.minutes(1)))
struct MeshRequestGateScenarioTests {
  private let repeater = Data(repeating: 0x11, count: PacketBuilder.publicKeySize)
  private let other = Data(repeating: 0x22, count: PacketBuilder.publicKeySize)
  private let overallTimeout: TimeInterval = 40
  private let defaultHold = Duration.seconds(1)
  private let loginOK = Data("OK".utf8)
  private let fetchTimeout: TimeInterval = 5

  @Test
  func `status queues behind login until login resolves`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()

    let loginTask = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin)

    let statusTask = Task {
      try await harness.session.requestStatus(from: other, priority: .interactive)
    }
    try await waitUntil("status queues behind login") {
      await harness.session.meshRequestGate.queuedWaiterCount == 1
    }
    #expect(await harness.commands(.sendStatusRequest).isEmpty)

    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    let loginResult = try await loginTask.value
    #expect(loginResult.succeeded)

    try await harness.ack(companion, code: .sendStatusRequest)
    await companion.deliver(from: other, body: statusFields(battery: 3900), on: harness.transport)
    let status = try await statusTask.value
    #expect(status.battery == 3900)
    await harness.session.stop()
  }

  @Test
  func `getMessage and DM send complete while binary waits on RF`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()

    let statusTask = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .sendStatusRequest)
    try await harness.waitUntilParked(for: .seconds(overallTimeout))

    let getMsgTask = Task { try await harness.session.getMessage(timeout: fetchTimeout) }
    try await waitUntil("getMessage is written") {
      await !harness.commands(.getMessage).isEmpty
    }
    await harness.transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
    let msgResult = try await getMsgTask.value
    guard case .noMoreMessages = msgResult else {
      Issue.record("Expected noMoreMessages")
      await harness.session.stop()
      return
    }

    let dmTask = Task { try await harness.session.sendMessage(to: other, text: "hello") }
    try await waitUntil("DM send is written") {
      await !harness.commands(.sendMessage).isEmpty
    }
    var dmSent = Data([ResponseCode.messageSent.rawValue, 0, 0xAA, 0xBB, 0xCC, 0xDD])
    dmSent.appendLittleEndian(UInt32(1000))
    await harness.transport.simulateReceive(dmSent)
    let sentInfo = try await dmTask.value
    #expect(sentInfo.expectedAck == Data([0xAA, 0xBB, 0xCC, 0xDD]))

    await companion.deliver(from: repeater, body: statusFields(battery: 4000), on: harness.transport)
    let status = try await statusTask.value
    #expect(status.battery == 4000)
    await harness.session.stop()
  }

  @Test
  func `cancelling a waiting binary request writes no resend and admits next waiter`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()

    let first = Task {
      try await harness.session.requestOwnerInfo(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest)
    try await harness.waitUntilParked(for: defaultHold)

    let second = Task {
      try await harness.session.requestTelemetry(from: other, priority: .interactive)
    }
    try await waitUntil("second request waits at gate") {
      await harness.session.meshRequestGate.queuedWaiterCount == 1
    }

    first.cancel()
    await #expect(throws: CancellationError.self) { try await first.value }
    #expect(await harness.commands(.binaryRequest).count == 1)

    try await harness.ack(companion, code: .getSelfTelemetry)
    await companion.deliver(from: other, body: Data([0x01]), on: harness.transport)
    let telemetry = try await second.value
    #expect(telemetry.publicKeyPrefix == other.prefix(6))
    await harness.session.stop()
  }

  @Test
  func `preemption reports afterHandOff accurately and never preempts login discovery or keepAlive`() async throws {
    do {
      let harness = try await ScenarioHarness.make()
      let companion = FirmwarePendingSlotCompanion()
      let background = Task {
        try await harness.session.requestOwnerInfo(from: repeater, priority: .background)
      }
      try await harness.ack(companion, code: .binaryRequest)
      try await harness.waitUntilParked(for: defaultHold)

      let interactive = Task {
        try await harness.session.requestTelemetry(from: other, priority: .interactive)
      }
      let bgError = await #expect(throws: MeshCoreError.self) { try await background.value }
      #expect(bgError.isPreempted(afterHandOff: true))

      try await harness.ack(companion, code: .getSelfTelemetry)
      await companion.deliver(from: other, body: Data([0x02]), on: harness.transport)
      _ = try await interactive.value
      await harness.session.stop()
    }

    do {
      let harness = try await ScenarioHarness.make()
      let companion = FirmwarePendingSlotCompanion()
      let writesBefore = await harness.commands(.binaryRequest).count
      let blocker = Task { try await harness.session.getMessage(timeout: fetchTimeout) }
      try await waitUntil("getMessage holds host slot") {
        await !harness.commands(.getMessage).isEmpty
      }

      let background = Task {
        try await harness.session.requestOwnerInfo(from: repeater, priority: .background)
      }
      try await waitUntil("background holds gate and waits on host slot") {
        let active = await harness.session.meshRequestGate.hasActiveHolder
        let waiting = await harness.session.requestResponseSerializer.waiterCountForTest == 1
        return active && waiting
      }

      let interactive = Task {
        try await harness.session.requestTelemetry(from: other, priority: .interactive)
      }
      try await waitUntil("interactive waits at gate") {
        await harness.session.meshRequestGate.queuedWaiterCount == 1
      }

      await harness.transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
      _ = try await blocker.value

      let bgError = await #expect(throws: MeshCoreError.self) { try await background.value }
      #expect(bgError.isPreempted(afterHandOff: false))
      #expect(await harness.commands(.binaryRequest).count == writesBefore)

      try await harness.ack(companion, code: .getSelfTelemetry)
      await companion.deliver(from: other, body: Data([0x03]), on: harness.transport)
      _ = try await interactive.value
      await harness.session.stop()
    }

    do {
      let harness = try await ScenarioHarness.make(configuration: .init(
        binaryRequestOverallTimeout: overallTimeout,
        binaryRequestRetransmitInterval: nil
      ))
      let companion = FirmwarePendingSlotCompanion()

      let bgLogin = Task {
        try await harness.session.login(to: repeater, password: "pw", priority: .background, budget: { _ in 20 })
      }
      try await harness.ack(companion, code: .sendLogin)
      let interactiveAfterLogin = Task {
        try await harness.session.requestTelemetry(from: other, priority: .interactive)
      }
      try await waitUntil("interactive queues behind non-preemptible background login") {
        await harness.session.meshRequestGate.queuedWaiterCount == 1
      }
      await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
      #expect(try await bgLogin.value.succeeded)
      try await harness.ack(companion, code: .getSelfTelemetry)
      await companion.deliver(from: other, body: Data([0x04]), on: harness.transport)
      _ = try await interactiveAfterLogin.value

      let bgDiscovery = Task {
        try await harness.session.discoverPath(to: repeater, priority: .background, budget: { _ in 10 })
      }
      try await harness.ack(companion, code: .pathDiscovery)
      let interactiveAfterDiscovery = Task {
        try await harness.session.requestTelemetry(from: other, priority: .interactive)
      }
      try await waitUntil("interactive queues behind non-preemptible background discovery") {
        await harness.session.meshRequestGate.queuedWaiterCount == 1
      }
      let discoveryRecord = try #require(await companion.acceptedWrites.last)
      await companion.deliverPathDiscovery(
        replyingTo: discoveryRecord.id,
        outPathLength: 1,
        outPath: Data([0xAA]),
        on: harness.transport
      )
      _ = try await bgDiscovery.value
      try await harness.ack(companion, code: .getSelfTelemetry, count: 2)
      await companion.deliver(from: other, body: Data([0x05]), on: harness.transport)
      _ = try await interactiveAfterDiscovery.value

      await harness.transport.holdNextSend()
      let bgKeepAlive = Task {
        try await harness.session.sendKeepAlive(to: repeater, syncSince: 0)
      }
      try await waitUntil("keep-alive parks in transport") {
        await harness.transport.sendIsWaiting
      }
      let interactiveAfterKA = Task {
        try await harness.session.requestTelemetry(from: other, priority: .interactive)
      }
      try await waitUntil("interactive queues behind non-preemptible keep-alive") {
        await harness.session.meshRequestGate.queuedWaiterCount == 1
      }
      await harness.transport.releaseHeldSend()
      try await harness.ack(companion, code: .binaryRequest)
      _ = try await bgKeepAlive.value
      try await harness.ack(companion, code: .getSelfTelemetry, count: 3)
      await companion.deliver(from: other, body: Data([0x06]), on: harness.transport)
      _ = try await interactiveAfterKA.value
      await harness.session.stop()
    }
  }

  @Test
  func `direct binary keeps 2x est spacing across multiple resends`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(other, route: 0, suggestedTimeoutMs: 1500)

    let directTask = Task {
      try await harness.session.requestOwnerInfo(from: other, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest, count: 1)
    try await harness.waitUntilParked(for: .seconds(3))
    harness.clock.advance(by: .seconds(3))
    try await harness.ack(companion, code: .binaryRequest, count: 2)
    try await harness.waitUntilParked(for: .seconds(3))
    harness.clock.advance(by: .seconds(3))
    try await harness.ack(companion, code: .binaryRequest, count: 3)
    let directRecord = try #require(await companion.acceptedWrites.last)
    await companion.deliver(replyingTo: directRecord.id, body: Data("v1\nn\no".utf8), on: harness.transport)
    _ = try await directTask.value
    await harness.session.stop()
  }

  @Test
  func `DM messageSent during binary RF wait does not enter tag set`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()

    let ownerTask = Task {
      try await harness.session.requestOwnerInfo(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest)
    try await harness.waitUntilParked(for: .seconds(overallTimeout))

    let dmTask = Task { try await harness.session.sendMessage(to: other, text: "hi") }
    try await waitUntil("DM written") {
      await !harness.commands(.sendMessage).isEmpty
    }
    let dmTag = Data([0xDE, 0xAD, 0xBE, 0xEF])
    var dmSent = Data([ResponseCode.messageSent.rawValue, 0]) + dmTag
    dmSent.appendLittleEndian(UInt32(1000))
    await harness.transport.simulateReceive(dmSent)
    _ = try await dmTask.value

    await harness.transport.simulateReceive(
      Data([ResponseCode.binaryResponse.rawValue, 0]) + dmTag + Data("bad\nbad\nbad".utf8)
    )
    let record = try #require(await companion.acceptedWrites.first)
    await companion.deliver(replyingTo: record.id, body: Data("v1.18\nGood\nOwner".utf8), on: harness.transport)
    let info = try await ownerTask.value
    #expect(info.nodeName == "Good")
    await harness.session.stop()
  }

  @Test
  func `multi-write login and pre-ack handoff cancellation set hold without truncation by shorter exchange`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 2000)

    let login = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .milliseconds(2500))
    harness.clock.advance(by: .milliseconds(2500))
    try await harness.ack(companion, code: .sendLogin, count: 2)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    _ = try await login.value
    #expect(await harness.session.meshRequestGate.hasActiveHold(for: repeater.prefix(4)))

    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 0)
    let telemetry = Task {
      try await harness.session.requestTelemetry(from: repeater, priority: .background)
    }
    try await harness.ack(companion, code: .getSelfTelemetry, count: 1)
    try await harness.waitUntilParked(for: defaultHold)
    telemetry.cancel()
    await #expect(throws: CancellationError.self) { try await telemetry.value }

    let status = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await waitUntil("status parks on 4s hold") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == .seconds(4)
    }
    harness.clock.advance(by: .seconds(4))
    try await harness.ack(companion, code: .sendStatusRequest)
    await companion.deliver(from: repeater, body: statusFields(battery: 3500), on: harness.transport)
    #expect(try await status.value.battery == 3500)

    await harness.transport.holdNextSend()
    let owner = Task {
      try await harness.session.requestOwnerInfo(from: other, priority: .interactive)
    }
    try await waitUntil("owner info handed to transport") {
      await harness.transport.sendIsWaiting
    }
    owner.cancel()
    await harness.transport.releaseHeldSend()
    try await harness.ack(companion, code: .binaryRequest, count: 1)
    await #expect(throws: CancellationError.self) { try await owner.value }
    #expect(await harness.session.meshRequestGate.hasActiveHold(for: other.prefix(4)))
    await harness.session.stop()
  }

  @Test
  func `same-password exemption bypasses direct-login hold while status or different password waits`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 2500)

    let directLogin = Task {
      try await harness.session.login(to: repeater, password: "secret", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .seconds(20))
    harness.clock.advance(by: .seconds(20))
    await #expect(throws: MeshCoreError.self) { try await directLogin.value }

    let wrongPw = Task {
      try await harness.session.login(
        to: repeater,
        password: "other",
        priority: .interactive,
        budget: { _ in 20 },
        samePasswordExemption: true
      )
    }
    try await waitUntil("wrong password login parks on 5s hold") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == .seconds(5)
    }
    wrongPw.cancel()
    await #expect(throws: CancellationError.self) { try await wrongPw.value }

    let floodLogin = Task {
      try await harness.session.login(
        to: repeater,
        password: "secret",
        priority: .interactive,
        budget: { _ in 20 },
        samePasswordExemption: true
      )
    }
    try await harness.ack(companion, code: .sendLogin, count: 2)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    #expect(try await floodLogin.value.succeeded)
    await harness.session.stop()
  }

  @Test
  func `flood exchange trims after final send only when suggestedTimeoutMs is nonzero`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()

    await companion.configureContact(repeater, route: MessageSentInfo.floodRoute, suggestedTimeoutMs: 1500)
    let trimmed = Task {
      try await harness.session.requestOwnerInfo(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest, count: 1)
    try await harness.waitUntilParked(for: .seconds(3))
    harness.clock.advance(by: .seconds(3))
    try await harness.ack(companion, code: .binaryRequest, count: 2)
    try await harness.waitUntilParked(for: .seconds(3))
    harness.clock.advance(by: .seconds(3))
    await #expect(throws: MeshCoreError.self) { try await trimmed.value }

    await companion.configureContact(other, route: MessageSentInfo.floodRoute, suggestedTimeoutMs: 0)
    let full = Task {
      try await harness.session.requestOwnerInfo(from: other, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest, count: 3)
    try await harness.waitUntilParked(for: .seconds(1))
    harness.clock.advance(by: .seconds(1))
    try await harness.ack(companion, code: .binaryRequest, count: 4)
    try await harness.waitUntilParked(for: .seconds(39))
    harness.clock.advance(by: .seconds(39))
    await #expect(throws: MeshCoreError.self) { try await full.value }
    await harness.session.stop()
  }

  @Test
  func `path discovery respects 10s vs 60s budget, sends once, and sets hold on timeout`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: MessageSentInfo.floodRoute, suggestedTimeoutMs: 2500)

    let short = Task {
      try await harness.session.discoverPath(to: repeater, priority: .interactive, budget: { _ in 10 })
    }
    try await harness.ack(companion, code: .pathDiscovery, count: 1)
    try await harness.waitUntilParked(for: .seconds(10))
    harness.clock.advance(by: .seconds(10))
    await #expect(throws: MeshCoreError.self) { try await short.value }
    #expect(await harness.commands(.pathDiscovery).count == 1)
    #expect(await harness.session.meshRequestGate.hasActiveHold(for: repeater.prefix(4)))

    let status = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await waitUntil("status waits out discovery hold") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == .seconds(5)
    }
    harness.clock.advance(by: .seconds(5))
    try await harness.ack(companion, code: .sendStatusRequest)
    await companion.deliver(from: repeater, body: statusFields(battery: 4100), on: harness.transport)
    #expect(try await status.value.battery == 4100)

    let long = Task {
      try await harness.session.discoverPath(to: other, priority: .interactive, budget: { _ in 60 })
    }
    try await harness.ack(companion, code: .pathDiscovery, count: 2)
    try await harness.waitUntilParked(for: .seconds(60))
    let record = try #require(await companion.acceptedWrites.last)
    await companion.deliverPathDiscovery(
      replyingTo: record.id,
      outPathLength: 1,
      outPath: Data([0xAA]),
      on: harness.transport
    )
    let pathInfo = try await long.value
    #expect(pathInfo.outPath == Data([0xAA]))
    await harness.session.stop()
  }

  @Test
  func `session stop wakes hold waiters with CancellationError and installs no post-stop hold`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 5000)

    let first = Task {
      try await harness.session.requestOwnerInfo(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .binaryRequest)
    try await harness.waitUntilParked(for: .seconds(10))
    first.cancel()
    await #expect(throws: CancellationError.self) { try await first.value }

    let parkedLogin = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 20 })
    }
    try await waitUntil("login parked on 10s hold") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == .seconds(10)
    }

    await harness.session.stop()
    await #expect(throws: CancellationError.self) { try await parkedLogin.value }
    #expect(await !harness.session.meshRequestGate.hasAnyActiveHold)
  }

  @Test
  func `350-contact sync holding host slot leaves droppedEventCount zero for admitted exchange`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()

    let statusTask = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await harness.ack(companion, code: .sendStatusRequest)
    try await harness.waitUntilParked(for: .seconds(overallTimeout))

    let contactsTask = Task { try await harness.session.getContacts(since: nil) }
    try await waitUntil("getContacts sent") {
      await !harness.commands(.getContacts).isEmpty
    }
    var startFrame = Data([ResponseCode.contactStart.rawValue])
    startFrame.appendLittleEndian(UInt32(350))
    await harness.transport.simulateReceive(startFrame)

    for index in 0..<350 {
      var contactFrame = Data([ResponseCode.contact.rawValue])
      var key = Data(repeating: 0x40, count: PacketBuilder.publicKeySize)
      key[0] = UInt8(index & 0xFF)
      key[1] = UInt8((index >> 8) & 0xFF)
      contactFrame.append(key)
      contactFrame.append(ContactType.chat.rawValue)
      contactFrame.append(0)
      contactFrame.append(PacketBuilder.floodPathSentinel)
      contactFrame.append(Data(repeating: 0, count: 64))
      contactFrame.append(Data(repeating: 0, count: 32))
      contactFrame.appendLittleEndian(UInt32(0))
      contactFrame.appendLittleEndian(Int32(0))
      contactFrame.appendLittleEndian(Int32(0))
      contactFrame.appendLittleEndian(UInt32(0))
      await harness.transport.simulateReceive(contactFrame)
    }
    var endFrame = Data([ResponseCode.contactEnd.rawValue])
    endFrame.appendLittleEndian(UInt32(100))
    await harness.transport.simulateReceive(endFrame)

    let contacts = try await contactsTask.value
    #expect(contacts.count == 350)
    #expect(await harness.session.dispatcher.droppedEventCount == 0)

    await companion.deliver(from: repeater, body: statusFields(battery: 4200), on: harness.transport)
    #expect(try await statusTask.value.battery == 4200)
    await harness.session.stop()
  }

  @Test
  func `unconsumed-login guard flags different password after cancelled login and clears on same password`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()

    let first = Task {
      try await harness.session.login(to: repeater, password: "good", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .seconds(20))
    first.cancel()
    await #expect(throws: CancellationError.self) { try await first.value }

    harness.clock.advance(by: defaultHold)

    let second = Task {
      try await harness.session.login(to: repeater, password: "bad", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 2)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    let secondResult = try await second.value
    #expect(secondResult.succeeded)
    #expect(secondResult.mayAnswerEarlierLogin)

    let third = Task {
      try await harness.session.login(to: other, password: "good", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 3)
    try await harness.waitUntilParked(for: .seconds(20))
    third.cancel()
    await #expect(throws: CancellationError.self) { try await third.value }

    let fourth = Task {
      try await harness.session.login(
        to: other,
        password: "good",
        priority: .interactive,
        budget: { _ in 20 },
        samePasswordExemption: true
      )
    }
    try await harness.ack(companion, code: .sendLogin, count: 4)
    await companion.deliver(from: other, body: loginOK, on: harness.transport)
    let fourthResult = try await fourth.value
    #expect(fourthResult.succeeded)
    #expect(!fourthResult.mayAnswerEarlierLogin)
    await harness.session.stop()
  }

  @Test
  func `multi-write login success keeps unconsumed digest until consumed after hold`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 2000)

    let goodLogin = Task {
      try await harness.session.login(to: repeater, password: "good", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    let replyOne = try #require(await companion.acceptedWrites.last).id
    try await harness.waitUntilParked(for: .milliseconds(2500))
    harness.clock.advance(by: .milliseconds(2500))
    try await harness.ack(companion, code: .sendLogin, count: 2)
    let replyTwo = try #require(await companion.acceptedWrites.last).id

    await companion.deliver(replyingTo: replyOne, body: loginOK, on: harness.transport)
    let firstResult = try await goodLogin.value
    #expect(firstResult.succeeded)

    let badLogin = Task {
      try await harness.session.login(to: repeater, password: "bad", priority: .interactive, budget: { _ in 20 })
    }
    try await waitUntil("bad login waits on 4s hold") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == .seconds(4)
    }
    harness.clock.advance(by: .seconds(4))
    try await harness.ack(companion, code: .sendLogin, count: 3)
    await companion.deliver(replyingTo: replyTwo, body: loginOK, on: harness.transport)
    let badResult = try await badLogin.value
    #expect(badResult.succeeded)
    #expect(badResult.mayAnswerEarlierLogin)
    await harness.session.stop()
  }

  @Test
  func `queued exchange ignores messageSent and stray routed response dispatched before submission`() async throws {
    let harness = try await ScenarioHarness.make(configuration: .init(
      binaryRequestOverallTimeout: overallTimeout,
      binaryRequestRetransmitInterval: nil
    ))
    let companion = FirmwarePendingSlotCompanion()
    let realBattery: UInt16 = 3810
    let strayBattery: UInt16 = 1234

    let dm = Task {
      try await harness.session.sendMessage(to: other, text: "hi")
    }
    try await waitUntil("DM written") {
      await harness.commands(.sendMessage).count == 1
    }

    await harness.transport.holdNextSendBeforeSubmission()
    let status = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await waitUntil("status acquired gate") {
      await harness.session.meshRequestGate.hasActiveHolder
    }

    let dmSentFrame = Data([ResponseCode.messageSent.rawValue, 0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0xE8, 0x03, 0x00, 0x00])
    await harness.transport.simulateReceive(dmSentFrame)
    _ = try await dm.value
    try await waitUntil("status paused before submission") {
      await harness.transport.submissionIsWaiting
    }

    // The receive loop dispatches injected frames asynchronously. Release the write only after the
    // dispatcher has run every filter on the stray frames, or they would race the submission.
    let dispatchProbe = await harness.session.events()
    await harness.transport.simulateReceive(dmSentFrame)
    let strayStatusFrame = Data([ResponseCode.statusResponse.rawValue, 0])
      + Data(repeater.prefix(6))
      + statusFields(battery: strayBattery)
    await harness.transport.simulateReceive(strayStatusFrame)
    for await event in dispatchProbe {
      if case .statusResponse = event { break }
    }

    await harness.transport.releaseHeldSubmission()
    try await harness.ack(companion, code: .sendStatusRequest)
    await companion.deliver(from: repeater, body: statusFields(battery: realBattery), on: harness.transport)

    let response = try await status.value
    #expect(response.battery == realBattery)
    await harness.session.stop()
  }

  @Test
  func `direct login resends once at 1.25x estimate and stops at two sends`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: 0, suggestedTimeoutMs: 6000)

    let login = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 16 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .milliseconds(7500))
    harness.clock.advance(by: .milliseconds(7500))
    try await harness.ack(companion, code: .sendLogin, count: 2)
    try await harness.waitUntilParked(for: .milliseconds(8500))
    harness.clock.advance(by: .milliseconds(8500))
    await #expect(throws: MeshCoreError.self) { try await login.value }
    #expect(await harness.commands(.sendLogin).count == 2)

    await companion.configureContact(other, route: 0, suggestedTimeoutMs: 0)
    let zeroHint = Task {
      try await harness.session.login(to: other, password: "pw", priority: .interactive, budget: { _ in 5 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 3)
    try await harness.waitUntilParked(for: .seconds(1))
    harness.clock.advance(by: .seconds(1))
    try await harness.ack(companion, code: .sendLogin, count: 4)
    try await harness.waitUntilParked(for: .seconds(4))
    harness.clock.advance(by: .seconds(4))
    await #expect(throws: MeshCoreError.self) { try await zeroHint.value }
    #expect(await harness.commands(.sendLogin).count == 4)
    await harness.session.stop()
  }

  @Test
  func `flood login enforces ten second resend floor`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: MessageSentInfo.floodRoute, suggestedTimeoutMs: 4000)

    let login = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 20 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .seconds(10))
    harness.clock.advance(by: .seconds(10))
    try await harness.ack(companion, code: .sendLogin, count: 2)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    #expect(try await login.value.succeeded)
    await harness.session.stop()
  }

  @Test
  func `login tail guard skips late resend without trimming final wait`() async throws {
    let harness = try await ScenarioHarness.make()
    let companion = FirmwarePendingSlotCompanion()
    await companion.configureContact(repeater, route: MessageSentInfo.floodRoute, suggestedTimeoutMs: 8000)

    let login = Task {
      try await harness.session.login(to: repeater, password: "pw", priority: .interactive, budget: { _ in 14 })
    }
    try await harness.ack(companion, code: .sendLogin, count: 1)
    try await harness.waitUntilParked(for: .seconds(14))
    harness.clock.advance(by: .seconds(10))
    #expect(await harness.commands(.sendLogin).count == 1)
    harness.clock.advance(by: .seconds(4))
    await #expect(throws: MeshCoreError.self) { try await login.value }
    #expect(await harness.commands(.sendLogin).count == 1)
    await harness.session.stop()
  }

  @Test
  func `failed login preserves unconsumed digest for delayed reply`() async throws {
    do {
      let harness = try await ScenarioHarness.make()
      let companion = FirmwarePendingSlotCompanion()

      let goodLogin = Task {
        try await harness.session.login(to: repeater, password: "good", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.ack(companion, code: .sendLogin, count: 1)
      let goodRecord = try #require(await companion.acceptedWrites.last)
      try await harness.waitUntilParked(for: .seconds(1))
      goodLogin.cancel()
      await #expect(throws: CancellationError.self) { _ = try await goodLogin.value }

      let badLogin = Task {
        try await harness.session.login(to: repeater, password: "bad", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.waitUntilParked(for: defaultHold)
      harness.clock.advance(by: defaultHold)
      try await harness.ack(companion, code: .sendLogin, count: 2)
      await companion.deliver(from: repeater, body: Data([0x01]), on: harness.transport)
      let badResult = try await badLogin.value
      #expect(!badResult.succeeded)

      let wrongNewLogin = Task {
        try await harness.session.login(to: repeater, password: "wrong-new", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.ack(companion, code: .sendLogin, count: 3)
      await companion.deliver(replyingTo: goodRecord.id, body: loginOK, on: harness.transport)
      let wrongNewResult = try await wrongNewLogin.value
      #expect(wrongNewResult.succeeded)
      #expect(wrongNewResult.mayAnswerEarlierLogin)
      await harness.session.stop()
    }

    do {
      let harness = try await ScenarioHarness.make()
      let companion = FirmwarePendingSlotCompanion()

      let wrongLogin = Task {
        try await harness.session.login(to: other, password: "wrong", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.ack(companion, code: .sendLogin, count: 1)
      try await harness.waitUntilParked(for: .seconds(1))
      wrongLogin.cancel()
      await #expect(throws: CancellationError.self) { _ = try await wrongLogin.value }

      let goodLogin = Task {
        try await harness.session.login(to: other, password: "good", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.waitUntilParked(for: defaultHold)
      harness.clock.advance(by: defaultHold)
      try await harness.ack(companion, code: .sendLogin, count: 2)
      let goodRecord = try #require(await companion.acceptedWrites.last)
      await companion.deliver(from: other, body: Data([0x01]), on: harness.transport)
      let goodFailResult = try await goodLogin.value
      #expect(!goodFailResult.succeeded)

      let wrongNewLogin = Task {
        try await harness.session.login(to: other, password: "wrong-new", priority: .interactive, budget: { _ in 20 })
      }
      try await harness.ack(companion, code: .sendLogin, count: 3)
      await companion.deliver(replyingTo: goodRecord.id, body: loginOK, on: harness.transport)
      let wrongNewResult = try await wrongNewLogin.value
      #expect(wrongNewResult.succeeded)
      #expect(wrongNewResult.mayAnswerEarlierLogin)
      await harness.session.stop()
    }
  }

  private func statusFields(battery: UInt16) -> Data {
    var fields = Data(repeating: 0, count: 51)
    fields[0] = UInt8(battery & 0x00FF)
    fields[1] = UInt8(battery >> 8)
    return fields
  }
}

private extension MeshCoreError? {
  func isPreempted(afterHandOff expected: Bool) -> Bool {
    guard case let .preempted(afterHandOff)? = self else { return false }
    return afterHandOff == expected
  }
}

private struct ScenarioHarness {
  let session: MeshCoreSession
  let transport: MockTransport
  let clock: TestClock

  static func make(configuration: SessionConfiguration = .init()) async throws -> ScenarioHarness {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(transport: transport, configuration: configuration, clock: clock)
    let start = Task { try await session.start() }
    try await waitUntil("app start is written") {
      await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue }
    }
    await transport.simulateReceive(
      Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: 54)
    )
    try await start.value
    await transport.clearSentData()
    return ScenarioHarness(session: session, transport: transport, clock: clock)
  }

  func commands(_ code: CommandCode) async -> [Data] {
    await transport.sentData.filter { $0.first == code.rawValue }
  }

  func ack(_ companion: FirmwarePendingSlotCompanion, code: CommandCode, count: Int = 1) async throws {
    try await waitUntil("command \(code) is written (\(count))") {
      await commands(code).count >= count
    }
    await companion.ackNewCommands(on: transport)
  }

  func waitUntilParked(for duration: Duration) async throws {
    try await waitUntil("clock parked for \(duration)") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == duration
    }
  }
}
