import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Connection full sync consumption")
@MainActor
struct ConnectionManagerFullSyncConsumptionTests {
  private static let watermark: UInt32 = 2000

  @Test(.timeLimit(.minutes(1)), arguments: [TransportType.bluetooth, .wifi])
  func `same request recovery uses incremental sync after its forced first sync`(type: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let transport = type == .bluetooth ? env.ble : env.wifi
    do {
      let saved = try await configure(env, transport: transport)
      env.manager.lastCleanChannelSync = (saved.radioID, Date())
      _ = try await env.connect(type)
      let first = try #require(env.manager.currentConnection)
      #expect(await contactReads(transport) == [PacketBuilder.getContacts()],
              "The admitted override must force the first contact exchange")
      let initialChannels = await transport.sentData.filter { $0.first == CommandCode.getChannel.rawValue }
      #expect(initialChannels.count == 1, "A forced sync must bypass a recent clean channel stamp")
      #expect(env.manager.currentChannelSyncConfig(for: first.device.radioID, transportType: type).channelSyncSkipWindow > .zero)

      try await recover(env, type: type, previousGeneration: first.generation)

      let recovered = try #require(env.manager.currentConnection)
      #expect(recovered.requestID == first.requestID)
      #expect(recovered.device.radioID == first.device.radioID)
      #expect(await contactReads(transport) == [PacketBuilder.getContacts(), incrementalRequest()],
              "Recovery must use the saved watermark after the request's forced sync has begun")
      let recoveredChannels = await transport.sentData.filter { $0.first == CommandCode.getChannel.rawValue }
      #expect(recoveredChannels == initialChannels, "Recovery must honor the recent clean channel stamp")
      #expect(try await recovered.services.session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  enum InterruptedSetup {
    case handshakeFailure
    case retiredReadyCallback
  }

  struct InterruptedSample: Sendable {
    var type: TransportType
    var interruption: InterruptedSetup
  }

  /// Handshake retries differ by transport. Retirement during readiness uses the same callback on either transport.
  nonisolated static let interruptedSamples: [InterruptedSample] = [
    InterruptedSample(type: .bluetooth, interruption: .handshakeFailure),
    InterruptedSample(type: .wifi, interruption: .handshakeFailure),
    InterruptedSample(type: .bluetooth, interruption: .retiredReadyCallback),
  ]

  @Test(.timeLimit(.minutes(1)), arguments: interruptedSamples)
  func `setup interrupted before sync preserves its forced exchange`(_ sample: InterruptedSample) async throws {
    let type = sample.type
    let interruption = sample.interruption
    let sessionClock = interruption == .handshakeFailure ? TestClock() : nil
    let env = try ConnectionManagerRadioTestHarness(sessionClock: sessionClock)
    let transport = type == .bluetooth ? env.ble : env.wifi
    var retirement: ConnectionRetirement?
    do {
      _ = try await configure(env, transport: transport)
      if interruption == .handshakeFailure {
        let attempts = type == .bluetooth
          ? (env.manager.hasSystemPairingRegistry ? ConnectionManager.defaultConnectAttempts : ConnectionManager.unverifiedConnectAttempts)
          : 1
        await transport.rejectNext(.appStart, count: attempts)
      } else {
        env.manager.onConnectionReady = { _ in
          retirement = env.manager.beginOwnedRetirement(
            previousRequest: env.manager.desiredRequest, phase: .connecting, closePhysical: true, retainMembership: true
          )
        }
      }
      let first: Result<ConnectionResult, Error>
      do {
        first = try await .success(env.resultAdvancingClock { try await env.connect(type) })
      } catch {
        first = .failure(error)
      }
      if interruption == .handshakeFailure {
        if case .success = first { Issue.record("The rejected handshake must fail before sync") }
      } else {
        if case .failure = first { Issue.record("Retirement during readiness must return its handoff") }
        try #require(retirement != nil)
      }
      // The replacement session uses the production clock so the pipeline grace sleep can finish.
      env.manager.sessionClockForTesting = nil
      await retirement?.value
      let request = try #require(env.manager.desiredRequest)
      #expect(await contactReads(transport).isEmpty, "Neither interrupted setup may enter contact sync")
      env.manager.onConnectionReady = nil

      let next = try await env.manager.establishAttempt(request, preservePhysicalBLE: false)

      #expect(env.manager.isCurrent(next))
      #expect(env.manager.currentConnection?.requestID == request.id)
      #expect(env.manager.connectionState == .ready)
      #expect(await contactReads(transport) == [PacketBuilder.getContacts()],
              "The first sync that actually starts must still force the complete contact exchange")
      let session = try #require(env.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(.timeLimit(.minutes(1)), arguments: [TransportType.bluetooth, .wifi])
  func `first sync retries retain their captured full sync override`(type: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let transport = type == .bluetooth ? env.ble : env.wifi
    do {
      _ = try await configure(env, transport: transport)
      await transport.rejectNext(.getContacts)
      _ = try await env.connect(type)
      let first = try #require(env.manager.currentConnection)
      let retry = try #require(env.manager.resyncTask)
      await retry.value

      #expect(env.manager.connectionState == .ready)
      #expect(await contactReads(transport) == [PacketBuilder.getContacts(), PacketBuilder.getContacts()],
              "A failed first exchange must retry in full even after the request override is consumed")
      try await recover(env, type: type, previousGeneration: first.generation)

      let recovered = try #require(env.manager.currentConnection)
      #expect(recovered.requestID == first.requestID)
      #expect(await contactReads(transport) == [PacketBuilder.getContacts(), PacketBuilder.getContacts(), incrementalRequest()],
              "A later Link record must resume incremental sync after the forced retry finishes")
      #expect(try await recovered.services.session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  private func configure(_ env: ConnectionManagerRadioTestHarness, transport: ConnectionTestRadioTransport) async throws -> DeviceDTO {
    await env.manager.wireTransportHandlers()
    let saved = DeviceDTO.testDevice(id: env.deviceID, publicKey: env.ble.publicKey, lastContactSync: Self.watermark)
    try await env.manager.persistenceStore.saveDevice(saved)
    await transport.setResponsePackets(for: .getContacts, packets: contactResponses())
    let model = Data("T-Deck".utf8)
    let capabilities = Data([ResponseCode.deviceInfo.rawValue, 14, 50, 1])
      + Data(repeating: 0, count: 16) + model + Data(repeating: 0, count: 40 - model.count + 20)
    await transport.setResponsePackets(for: .deviceQuery, packets: [capabilities])
    return saved
  }

  private func recover(
    _ env: ConnectionManagerRadioTestHarness, type: TransportType, previousGeneration: UUID
  ) async throws {
    if type == .bluetooth {
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("Bluetooth loss should retire the first Link record") { env.manager.currentConnection == nil }
      await env.manager.retirementTask?.value
      await env.ble.simulateReconnected()
    } else {
      await env.wifi.simulateRemoteLoss()
    }
    try await waitUntil("same request recovery must finish a fresh setup") {
      env.manager.currentConnection?.generation != previousGeneration && env.manager.connectionState == .ready
    }
  }

  private func contactReads(_ transport: ConnectionTestRadioTransport) async -> [Data] {
    await transport.sentData.filter { $0.first == CommandCode.getContacts.rawValue }
  }

  private func incrementalRequest() -> Data {
    PacketBuilder.getContacts(since: Date(timeIntervalSince1970: TimeInterval(Self.watermark - 1)))
  }

  private func contactResponses() -> [Data] {
    let watermark = Self.watermark.littleEndian
    return [
      Data([ResponseCode.contactStart.rawValue, 0, 0, 0, 0]),
      Data([ResponseCode.contactEnd.rawValue]) + withUnsafeBytes(of: watermark) { Data($0) }
    ]
  }
}
