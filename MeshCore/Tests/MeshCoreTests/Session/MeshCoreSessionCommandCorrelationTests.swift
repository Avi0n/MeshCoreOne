import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession command correlation")
struct MeshCoreSessionCommandCorrelationTests {
  @Test
  func `simple commands serialize concurrent OK/ERROR waits`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let first = Task {
      try await session.factoryReset()
    }
    let second = Task {
      try await session.sendAdvertisement(flood: true)
    }

    try await waitUntil("first command should be sent") {
      await transport.sentData.count == 2
    }

    try? await Task.sleep(for: .milliseconds(50))
    #expect(await transport.sentData.count == 2)

    await transport.simulateOK()

    try await waitUntil("second command should wait for the first command to complete") {
      await transport.sentData.count == 3
    }

    await transport.simulateOK()

    try await first.value
    try await second.value
    await session.stop()
  }

  @Test
  func `simple commands ignore OK responses with payloads`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let resetTask = Task {
      try await session.factoryReset()
    }

    try await waitUntil("factoryReset should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateOK(value: 7)

    let error = await #expect(throws: MeshCoreError.self) {
      try await resetTask.value
    }
    guard case .timeout? = error else {
      Issue.record("Expected timeout after unrelated OK payload, got \(String(describing: error))")
      await session.stop()
      return
    }

    await session.stop()
  }

  @Test
  func `simple commands still fail on device errors`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let commandTask = Task {
      try await session.setAutoAddConfig(AutoAddConfig(bitmask: 0x1E, maxHops: 2))
    }

    try await waitUntil("setAutoAddConfig should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 42)

    let error = await #expect(throws: MeshCoreError.self) {
      try await commandTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 42)

    await session.stop()
  }

  @Test
  func `session start ignores unrelated errors until selfInfo arrives`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    let startTask = Task {
      try await session.start()
    }

    try await waitUntil("transport should send appStart before session starts") {
      await transport.sentData.count == 1
    }

    await transport.simulateError(code: 99)
    await transport.simulateReceive(makeSelfInfoPacket())

    try await startTask.value
    #expect(await session.currentSelfInfo?.name == "Test")
    await session.stop()
  }

  @Test
  func `getBattery ignores unrelated errors while waiting for a battery response`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let batteryTask = Task {
      try await session.getBattery()
    }

    try await waitUntil("getBattery should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 10)
    await transport.simulateReceive(makeBatteryPacket(level: 4018))

    let battery = try await batteryTask.value
    #expect(battery.level == 4018)
    await session.stop()
  }

  @Test
  func `getSelfTelemetry ignores telemetry for other nodes`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let telemetryTask = Task {
      try await session.getSelfTelemetry()
    }

    try await waitUntil("getSelfTelemetry should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(
      makeTelemetryPacket(
        publicKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
        lppPayload: Data([0x01, 0x67, 0x00, 0xFA])
      )
    )
    await transport.simulateReceive(
      makeTelemetryPacket(
        publicKeyPrefix: Data(repeating: 0x01, count: 6),
        lppPayload: Data([0x01, 0x67, 0x00, 0xF0])
      )
    )

    let response = try await telemetryTask.value
    #expect(response.publicKeyPrefix == Data(repeating: 0x01, count: 6))
    await session.stop()
  }

  @Test
  func `getChannel ignores responses for other channel indexes`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let channelTask = Task {
      try await session.getChannel(index: 3)
    }

    try await waitUntil("getChannel should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(
      makeChannelInfoPacket(index: 9, name: "Wrong", secret: Data(repeating: 0xAA, count: 16))
    )
    await transport.simulateReceive(
      makeChannelInfoPacket(index: 3, name: "Right", secret: Data(repeating: 0xBB, count: 16))
    )

    let channel = try await channelTask.value
    #expect(channel.index == 3)
    #expect(channel.name == "Right")
    await session.stop()
  }

  @Test
  func `getContacts succeeds when slow stream keeps making progress`() async throws {
    let transport = MockTransport()
    // Inactivity timeout sits well above the 70ms send cadence so a slow
    // runner that overshoots a sleep can't trip it mid-stream.
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        contactStreamInactivityTimeout: 1.0,
        contactStreamHardTimeout: 10.0
      )
    )

    try await startSession(session, transport: transport)

    let contactsTask = Task {
      try await session.getContacts()
    }

    try await waitUntil("getContacts should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(makeContactsStartPacket(count: 3))
    for index in 0..<3 {
      try await Task.sleep(for: .milliseconds(70))
      await transport.simulateReceive(
        makeContactPacket(publicKey: Data(repeating: UInt8(index + 1), count: 32), name: "Node \(index)")
      )
    }
    try await Task.sleep(for: .milliseconds(70))
    await transport.simulateReceive(makeContactsEndPacket(lastModified: 1_704_067_200))

    let contacts = try await contactsTask.value
    #expect(contacts.count == 3)
    await session.stop()
  }

  @Test
  func `getContacts times out after inactivity before contactsEnd`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        contactStreamInactivityTimeout: 0.08,
        contactStreamHardTimeout: 1.0
      )
    )

    try await startSession(session, transport: transport)

    let contactsTask = Task {
      try await session.getContacts()
    }

    try await waitUntil("getContacts should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(makeContactsStartPacket(count: 1))
    try await Task.sleep(for: .milliseconds(140))

    let error = await #expect(throws: MeshCoreError.self) {
      try await contactsTask.value
    }
    guard case .timeout? = error else {
      Issue.record("Expected timeout after contact stream inactivity, got \(String(describing: error))")
      await session.stop()
      return
    }

    await session.stop()
  }

  @Test
  func `getContact ignores responses for other public keys`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let requestedKey = Data(repeating: 0x11, count: 32)
    let contactTask = Task {
      try await session.getContact(publicKey: requestedKey)
    }

    try await waitUntil("getContact should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(
      makeContactPacket(publicKey: Data(repeating: 0x22, count: 32), name: "Wrong")
    )
    await transport.simulateReceive(
      makeContactPacket(publicKey: requestedKey, name: "Right")
    )

    let contact = try #require(await contactTask.value)
    #expect(contact.publicKey == requestedKey)
    #expect(contact.advertisedName == "Right")
    await session.stop()
  }

  @Test
  func `exportContact ignores contact URIs for other public keys`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let requestedKey = Data(repeating: 0x11, count: 32)
    let otherKey = Data(repeating: 0x22, count: 32)

    let exportTask = Task {
      try await session.exportContact(publicKey: requestedKey)
    }

    try await waitUntil("exportContact should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(makeContactURIPacket(publicKey: otherKey))
    await transport.simulateReceive(makeContactURIPacket(publicKey: requestedKey))

    let uri = try await exportTask.value
    #expect(uri.contains(requestedKey.hexString), "exportContact must return the card for the requested key")
    #expect(!uri.contains(otherKey.hexString), "exportContact must not return another contact's card")
    await session.stop()
  }

  @Test
  func `importPrivateKey ignores OK responses with payloads`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let importTask = Task {
      try await session.importPrivateKey(Data(repeating: 0x33, count: 64))
    }

    try await waitUntil("importPrivateKey should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateOK(value: 7)

    let error = await #expect(throws: MeshCoreError.self) {
      try await importTask.value
    }
    guard case .timeout? = error else {
      Issue.record("Expected timeout after unrelated OK payload, got \(String(describing: error))")
      await session.stop()
      return
    }

    await session.stop()
  }

  @Test
  func `importPrivateKey refreshes cached self info after OK`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    let originalPublicKey = Data(repeating: 0x01, count: 32)
    let restoredPublicKey = Data(repeating: 0x44, count: 32)

    try await startSession(
      session,
      transport: transport,
      selfInfoPacket: makeSelfInfoPacket(publicKey: originalPublicKey, name: "Temp")
    )
    #expect(await session.currentSelfInfo?.publicKey == originalPublicKey)

    let importTask = Task {
      try await session.importPrivateKey(Data(repeating: 0x33, count: 64))
    }

    try await waitUntil("importPrivateKey should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateOK()

    try await waitUntil("appStart should be sent after importPrivateKey OK") {
      await transport.sentData.count == 3
    }

    await transport.simulateReceive(makeSelfInfoPacket(publicKey: restoredPublicKey, name: "Restored"))
    try await importTask.value

    let selfInfo = try #require(await session.currentSelfInfo)
    #expect(selfInfo.publicKey == restoredPublicKey)
    #expect(selfInfo.name == "Restored")
    await session.stop()
  }

  @Test
  func `importPrivateKey rejects a key that is not the expanded private-key length`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    let error = await #expect(throws: MeshCoreError.self) {
      try await session.importPrivateKey(Data(repeating: 0x33, count: PacketBuilder.publicKeySize))
    }
    guard case .invalidInput? = error else {
      Issue.record("Expected invalidInput for wrong-length private key, got \(String(describing: error))")
      return
    }
    let sentCount = await transport.sentData.count
    #expect(sentCount == 0, "Guard must fail before any frame is sent")
  }

  @Test
  func `exportPrivateKey throws featureDisabled on disabled response`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let exportTask = Task {
      try await session.exportPrivateKey()
    }

    try await waitUntil("exportPrivateKey should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(Data([ResponseCode.disabled.rawValue]))

    let error = await #expect(throws: MeshCoreError.self) {
      try await exportTask.value
    }
    guard case .featureDisabled? = error else {
      Issue.record("Expected featureDisabled, got \(String(describing: error))")
      await session.stop()
      return
    }

    await session.stop()
  }

  @Test
  func `disabled responses do not break unrelated requests`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let batteryTask = Task {
      try await session.getBattery()
    }

    try await waitUntil("getBattery should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(Data([ResponseCode.disabled.rawValue]))
    await transport.simulateReceive(makeBatteryPacket(level: 4018))

    let battery = try await batteryTask.value
    #expect(battery.level == 4018)
    await session.stop()
  }

  @Test
  func `requestStatus fails fast on device error before messageSent`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let statusTask = Task {
      try await session.requestStatus(from: target)
    }

    try await waitUntil("requestStatus should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 10)

    let error = await #expect(throws: MeshCoreError.self) {
      try await statusTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError for binary status request, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 10)
    await session.stop()
  }

  @Test
  func `requestStatus uses dedicated status command and room layout for typed room targets`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let expectedAck = Data([0xAA, 0xBB, 0xCC, 0xDD])

    let statusTask = Task {
      try await session.requestStatus(from: target, type: .room)
    }

    try await waitUntil("requestStatus should be sent") {
      await transport.sentData.count == 2
    }

    let sent = await transport.sentData[1]
    #expect(sent.first == CommandCode.sendStatusRequest.rawValue)

    await transport.simulateReceive(makeMessageSentPacket(expectedAck: expectedAck))
    // Dedicated STATUS_RESPONSE push; room counters packed where repeater rxAirtime sits.
    await transport.simulateReceive(
      makeStatusResponsePacket(
        publicKeyPrefix: Data(target.prefix(6)),
        battery: 1000,
        roomServerPostedCount: 17,
        roomServerPostPushCount: 9
      )
    )

    let status = try await statusTask.value
    #expect(status.battery == 1000)
    #expect(status.roomServerPostedCount == 17)
    #expect(status.roomServerPostPushCount == 9)
    #expect(status.rxAirtime == 0)
    await session.stop()
  }

  @Test
  func `requestStatus retransmits until a matching response arrives`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 2.0,
        binaryRequestRetransmitInterval: 0.05
      )
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let firstTag = Data([0x11, 0x22, 0x33, 0x44])
    let secondTag = Data([0x55, 0x66, 0x77, 0x88])

    let statusTask = Task {
      try await session.requestStatus(from: target)
    }

    try await waitUntil("first status request should be sent") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: firstTag, timeoutMs: 500))

    try await waitUntil("status request should retransmit") {
      await transport.sentData.count >= 3
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: secondTag, timeoutMs: 500))

    // Status is routed by public-key prefix, not tag.
    await transport.simulateReceive(
      makeStatusResponsePacket(publicKeyPrefix: Data(target.prefix(6)), battery: 2200)
    )

    let status = try await statusTask.value
    #expect(status.battery == 2200)
    #expect(await transport.sentData.filter { $0.first == CommandCode.sendStatusRequest.rawValue }.count >= 2)
    await session.stop()
  }

  @Test
  func `binary response matches only the latest retransmit tag`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 2.0,
        binaryRequestRetransmitInterval: 0.05
      )
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let firstTag = Data([0x11, 0x22, 0x33, 0x44])
    let secondTag = Data([0x55, 0x66, 0x77, 0x88])

    let telemetryTask = Task {
      try await session.requestTelemetry(from: target)
    }

    try await waitUntil("first telemetry request should be sent") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: firstTag, timeoutMs: 500))

    try await waitUntil("telemetry request should retransmit") {
      await transport.sentData.count >= 3
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: secondTag, timeoutMs: 500))

    // Stale first-tag reply after retransmit must not complete the wait.
    await transport.simulateReceive(makeBinaryTelemetryResponsePacket(tag: firstTag))
    try? await Task.sleep(for: .milliseconds(80))
    #expect(!telemetryTask.isCancelled)

    await transport.simulateReceive(makeBinaryTelemetryResponsePacket(tag: secondTag))

    let telemetry = try await telemetryTask.value
    #expect(telemetry.dataPoints.isEmpty)
    await session.stop()
  }

  @Test
  func `requestStatus times out after the overall budget without a reply`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        // Keep overall longer than retransmit spacing so a resend is observed.
        binaryRequestOverallTimeout: 0.5,
        binaryRequestRetransmitInterval: 0.05
      )
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let statusTask = Task {
      try await session.requestStatus(from: target)
    }

    try await waitUntil("requestStatus should be sent") {
      await transport.sentData.count == 2
    }
    // suggested 50ms × headroom 2 = 100ms between retransmits.
    await transport.simulateReceive(
      makeMessageSentPacket(expectedAck: Data([0x01, 0x02, 0x03, 0x04]), timeoutMs: 50)
    )

    let error = await #expect(throws: MeshCoreError.self) {
      try await statusTask.value
    }
    guard case .timeout? = error else {
      Issue.record("Expected timeout after overall budget, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(await transport.sentData.filter { $0.first == CommandCode.sendStatusRequest.rawValue }.count >= 2)
    await session.stop()
  }

  @Test
  func `requestTelemetry fails fast on device error before messageSent`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let target = Data(repeating: 0x31, count: 32)
    let telemetryTask = Task {
      try await session.requestTelemetry(from: target)
    }

    try await waitUntil("requestTelemetry should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 11)

    let error = await #expect(throws: MeshCoreError.self) {
      try await telemetryTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError for binary telemetry request, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 11)
    await session.stop()
  }

  @Test
  func `sendMessage fails fast on device error`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let messageTask = Task {
      try await session.sendMessage(
        to: Data(repeating: 0x11, count: 32),
        text: "hello"
      )
    }

    try await waitUntil("sendMessage should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 5)

    let error = await #expect(throws: MeshCoreError.self) {
      try await messageTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 5)
    await session.stop()
  }

  @Test
  func `sendKeepAlive fails fast on device error`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let keepAliveTask = Task {
      try await session.sendKeepAlive(
        to: Data(repeating: 0x22, count: 32),
        syncSince: 0
      )
    }

    try await waitUntil("sendKeepAlive should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 3)

    let error = await #expect(throws: MeshCoreError.self) {
      try await keepAliveTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 3)
    await session.stop()
  }

  @Test
  func `exportPrivateKey fails fast on device error`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let exportTask = Task {
      try await session.exportPrivateKey()
    }

    try await waitUntil("exportPrivateKey should be sent") {
      await transport.sentData.count == 2
    }

    await transport.simulateError(code: 8)

    let error = await #expect(throws: MeshCoreError.self) {
      try await exportTask.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected deviceError, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == 8)
    await session.stop()
  }

  @Test
  func `binary request serializes behind a concurrent text command`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    // Text command acquires the unified serializer first.
    let keepAliveTask = Task {
      try await session.sendKeepAlive(
        to: Data(repeating: 0x22, count: 32),
        syncSince: 0
      )
    }

    try await waitUntil("sendKeepAlive should be sent") {
      await transport.sentData.count == 2
    }

    // Binary request must wait behind the text command, not run concurrently.
    let target = Data(repeating: 0x31, count: 32)
    let statusTask = Task {
      try await session.requestStatus(from: target)
    }

    try? await Task.sleep(for: .milliseconds(50))
    #expect(await transport.sentData.count == 2, "binary request must not send while a text command is in flight")

    // The error belongs to the in-flight text command only.
    await transport.simulateError(code: 42)

    let keepAliveError = await #expect(throws: MeshCoreError.self) {
      try await keepAliveTask.value
    }
    guard case let .deviceError(keepAliveCode)? = keepAliveError else {
      Issue.record("Expected keepAlive deviceError, got \(String(describing: keepAliveError))")
      await session.stop()
      return
    }
    #expect(keepAliveCode == 42)

    // Only after the text command releases the serializer does the binary request send.
    try await waitUntil("requestStatus should send after the text command completes") {
      await transport.sentData.count == 3
    }

    await transport.simulateError(code: 43)

    let statusError = await #expect(throws: MeshCoreError.self) {
      try await statusTask.value
    }
    guard case let .deviceError(statusCode)? = statusError else {
      Issue.record("Expected status deviceError, got \(String(describing: statusError))")
      await session.stop()
      return
    }
    #expect(statusCode == 43, "binary request runs as its own exchange after the text command")

    await session.stop()
  }

  @Test
  func `binary request errors release the serializer for following requests`() async throws {
    let transport = MockTransport()
    // Disable in-exchange retransmit so this serialization test is not
    // sensitive to parallel-suite scheduling of retransmit sleeps.
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 2.0,
        binaryRequestRetransmitInterval: nil
      )
    )

    try await startSession(session, transport: transport)

    let firstTarget = Data(repeating: 0x31, count: 32)
    let secondTarget = Data(repeating: 0x42, count: 32)

    let statusTask = Task {
      try await session.requestStatus(from: firstTarget)
    }
    let telemetryTask = Task {
      try await session.requestTelemetry(from: secondTarget)
    }

    try await waitUntil("first binary request should be sent") {
      await transport.sentData.count == 2
    }

    try? await Task.sleep(for: .milliseconds(50))
    #expect(await transport.sentData.count == 2)

    await transport.simulateError(code: 12)

    let statusError = await #expect(throws: MeshCoreError.self) {
      try await statusTask.value
    }
    guard case let .deviceError(firstCode)? = statusError else {
      Issue.record("Expected first binary request to fail with deviceError, got \(String(describing: statusError))")
      await session.stop()
      return
    }
    #expect(firstCode == 12)

    try await waitUntil("second binary request should send after the first one fails") {
      await transport.sentData.count == 3
    }

    await transport.simulateError(code: 13)

    let telemetryError = await #expect(throws: MeshCoreError.self) {
      try await telemetryTask.value
    }
    guard case let .deviceError(secondCode)? = telemetryError else {
      Issue.record("Expected second binary request to fail with deviceError, got \(String(describing: telemetryError))")
      await session.stop()
      return
    }
    #expect(secondCode == 13)
    await session.stop()
  }

  @Test
  func `a response orphaned by a cancelled command is not delivered to the next command`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    // Command #1 is sent, then cancelled after its write has gone out — the radio
    // still owes a response. getBattery is a singleton matcher whose value we control,
    // so a stolen response surfaces as the wrong battery level on command #2.
    let orphanedLevel: UInt16 = 1111
    let correctLevel: UInt16 = 2222

    let firstBattery = Task { try await session.getBattery() }
    try await waitUntil("first getBattery should be sent") {
      await transport.sentData.count == 2
    }

    firstBattery.cancel()
    _ = try? await firstBattery.value

    // Command #2 issued after the cancellation.
    let secondBattery = Task { try await session.getBattery() }

    // Give the next command time to subscribe before the orphan lands.
    try? await Task.sleep(for: .milliseconds(50))

    // The radio's late response to the cancelled command #1.
    await transport.simulateReceive(makeBatteryPacket(level: orphanedLevel))

    try await waitUntil("second getBattery should be sent") {
      await transport.sentData.count == 3
    }

    // Command #2's own response.
    await transport.simulateReceive(makeBatteryPacket(level: correctLevel))

    let battery = try await secondBattery.value
    #expect(battery.level == correctLevel, "command #2 must not receive command #1's orphaned response")
    await session.stop()
  }

  @Test
  func `concurrent unicast send and binary request do not share one messageSent`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let statusTarget = Data(repeating: 0x31, count: 32)
    let messageTarget = Data(repeating: 0x11, count: 32)
    let statusAck = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let messageAck = Data([0x11, 0x22, 0x33, 0x44])

    // Binary request goes first and owns the in-flight exchange.
    let statusTask = Task { try await session.requestStatus(from: statusTarget, type: .room) }
    try await waitUntil("requestStatus should be sent") {
      await transport.sentData.count == 2
    }

    // Unicast send issued while the binary request is outstanding.
    let messageTask = Task { try await session.sendMessage(to: messageTarget, text: "hi") }

    // Give a non-serialized sender time to also subscribe before any messageSent lands.
    try? await Task.sleep(for: .milliseconds(50))

    // The status request's own messageSent + dedicated STATUS_RESPONSE.
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: statusAck))
    await transport.simulateReceive(
      makeStatusResponsePacket(
        publicKeyPrefix: Data(statusTarget.prefix(6)),
        battery: 1234,
        roomServerPostedCount: 5,
        roomServerPostPushCount: 2
      )
    )

    let status = try await statusTask.value
    #expect(status.battery == 1234)

    try await waitUntil("sendMessage should be sent") {
      await transport.sentData.count == 3
    }

    // The unicast send's own messageSent.
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: messageAck))

    let info = try await messageTask.value
    #expect(info.expectedAck == messageAck, "sendMessage must not consume the binary request's messageSent")
    await session.stop()
  }

  @Test
  func `a command cancelled while waiting on the serializer never writes`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    // Command #1 holds the serializer.
    let first = Task { try await session.factoryReset() }
    try await waitUntil("first command should be sent") {
      await transport.sentData.count == 2
    }

    // Command #2 parks in acquire() behind command #1.
    let second = Task { try await session.sendAdvertisement(flood: true) }
    try? await Task.sleep(for: .milliseconds(50))
    #expect(await transport.sentData.count == 2, "second command must wait behind the first")

    // Cancel #2 while it is still parked, then let #1 finish so #2 acquires.
    second.cancel()
    await transport.simulateOK()
    try await first.value

    let error = await #expect(throws: CancellationError.self) {
      try await second.value
    }
    #expect(error != nil)
    #expect(
      await transport.sentData.count == 2,
      "a command cancelled before acquiring the serializer must not commit a write"
    )
    await session.stop()
  }
}

private func startSession(
  _ session: MeshCoreSession,
  transport: MockTransport,
  selfInfoPacket: Data = makeSelfInfoPacket()
) async throws {
  let startTask = Task {
    try await session.start()
  }

  try await waitUntil("transport should send appStart before session starts") {
    await transport.sentData.count == 1
  }

  await transport.simulateReceive(selfInfoPacket)
  try await startTask.value
}

private func makeSelfInfoPacket(
  publicKey: Data = Data(repeating: 0x01, count: 32),
  name: String = "Test"
) -> Data {
  var payload = Data()
  payload.append(1)
  payload.append(UInt8(bitPattern: 22))
  payload.append(UInt8(bitPattern: 22))
  payload.append(publicKey)
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  payload.append(7)
  payload.append(5)
  payload.append(contentsOf: name.utf8)

  var packet = Data([ResponseCode.selfInfo.rawValue])
  packet.append(payload)
  return packet
}

private func makeBatteryPacket(level: UInt16) -> Data {
  var packet = Data([ResponseCode.battery.rawValue])
  packet.append(contentsOf: withUnsafeBytes(of: level.littleEndian) { Array($0) })
  return packet
}

private func makeMessageSentPacket(
  type: UInt8 = 0,
  expectedAck: Data,
  timeoutMs: UInt32 = 5000
) -> Data {
  var packet = Data([ResponseCode.messageSent.rawValue])
  packet.append(type)
  packet.append(expectedAck)
  packet.append(contentsOf: withUnsafeBytes(of: timeoutMs.littleEndian) { Array($0) })
  return packet
}

private func makeContactURIPacket(publicKey: Data) -> Data {
  var packet = Data([ResponseCode.contactURI.rawValue])
  packet.append(publicKey)
  // Trailing card bytes (timestamp/signature/app data) follow the public key; their
  // contents are irrelevant to the requested-key echo guard.
  packet.append(Data(repeating: 0xCD, count: 8))
  return packet
}

private func makeTelemetryPacket(publicKeyPrefix: Data, lppPayload: Data) -> Data {
  var packet = Data([ResponseCode.telemetryResponse.rawValue])
  packet.append(0x00)
  packet.append(publicKeyPrefix)
  packet.append(lppPayload)
  return packet
}

private func makeStatusResponsePacket(
  publicKeyPrefix: Data,
  battery: UInt16,
  roomServerPostedCount: UInt16 = 0,
  roomServerPostPushCount: UInt16 = 0
) -> Data {
  var packet = Data([ResponseCode.statusResponse.rawValue, 0x00])
  packet.append(publicKeyPrefix)
  packet.append(contentsOf: withUnsafeBytes(of: battery.littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(-110).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(-85).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(100).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(50).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(25).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(3600).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(5).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(10).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(15).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(20).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  // Trailing 4 bytes: repeater rxAirtime, or packed room posted/postPush when requested.
  var trailing = UInt32(roomServerPostedCount)
  trailing |= UInt32(roomServerPostPushCount) << 16
  packet.append(contentsOf: withUnsafeBytes(of: trailing.littleEndian) { Array($0) })
  return packet
}

private func makeBinaryStatusResponsePacket(
  tag: Data,
  battery: UInt16,
  roomServerPostedCount: UInt16,
  roomServerPostPushCount: UInt16
) -> Data {
  var packet = Data([ResponseCode.binaryResponse.rawValue])
  packet.append(0x00)
  packet.append(tag)

  var payload = Data(repeating: 0, count: 52)
  payload.replaceSubrange(0..<2, with: withUnsafeBytes(of: battery.littleEndian) { Array($0) })
  payload.replaceSubrange(48..<50, with: withUnsafeBytes(of: roomServerPostedCount.littleEndian) { Array($0) })
  payload.replaceSubrange(50..<52, with: withUnsafeBytes(of: roomServerPostPushCount.littleEndian) { Array($0) })

  packet.append(payload)
  return packet
}

/// Wire: [0x8C][requestType:1][tag:4][LPP payload...]
private func makeBinaryTelemetryResponsePacket(tag: Data, lppPayload: Data = Data()) -> Data {
  var packet = Data([ResponseCode.binaryResponse.rawValue])
  packet.append(0x00)
  packet.append(tag)
  packet.append(lppPayload)
  return packet
}

private func makeChannelInfoPacket(index: UInt8, name: String, secret: Data) -> Data {
  var packet = Data([ResponseCode.channelInfo.rawValue, index])
  let nameBytes = Array(name.utf8.prefix(31))
  packet.append(contentsOf: nameBytes)
  packet.append(0)
  if nameBytes.count < 31 {
    packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
  }
  packet.append(secret)
  return packet
}

private func makeContactsStartPacket(count: UInt32) -> Data {
  var packet = Data([ResponseCode.contactStart.rawValue])
  packet.append(contentsOf: withUnsafeBytes(of: count.littleEndian) { Array($0) })
  return packet
}

private func makeContactsEndPacket(lastModified: UInt32) -> Data {
  var packet = Data([ResponseCode.contactEnd.rawValue])
  packet.append(contentsOf: withUnsafeBytes(of: lastModified.littleEndian) { Array($0) })
  return packet
}

private func makeContactPacket(publicKey: Data, name: String) -> Data {
  var packet = Data([ResponseCode.contact.rawValue])
  packet.append(publicKey)
  packet.append(ContactType.chat.rawValue)
  packet.append(ContactFlags().rawValue)
  packet.append(0xFF)
  packet.append(Data(repeating: 0, count: 64))

  let nameBytes = Array(name.utf8.prefix(31))
  packet.append(contentsOf: nameBytes)
  packet.append(0)
  if nameBytes.count < 31 {
    packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
  }

  packet.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Array($0) })
  return packet
}
