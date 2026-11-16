import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("WiFi pre-publication loss outcomes")
@MainActor
struct ConnectionManagerWiFiEstablishmentLossTests {
  @Test
  func `actual TCP EOF during launch selection advances to the saved Bluetooth target`() async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness { harness in
      harness.holdFirstSelection()
      let launch = harness.track(Task {
        await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
      })
      let candidate = try await harness.selectedCandidate()
      try #require(candidate.request.targets == [
        .wifi(host: harness.host, port: harness.port), .bluetooth(harness.saved.id)
      ])
      try await harness.endReceive(candidate.attempt)
      try #require(harness.manager.desiredRequest === candidate.request)
      harness.releaseSelection()
      await launch.value

      #expect(await harness.bluetooth.connectCount == 1)
      #expect(harness.manager.currentTransportType == .bluetooth)
      #expect(harness.manager.connectedDevice?.radioID == harness.saved.radioID)
      #expect(harness.manager.connectionState == .ready)
      #expect(!harness.manager.isReconnectionWatchdogRunning)
      #expect(harness.manager.session != nil)
      if let session = harness.manager.session { #expect(try await session.getTime().timeIntervalSince1970 > 0) }
      try await harness.proveLaterIO()
    }
  }

  @Test
  func `actual TCP EOF during explicit WiFi selection throws a current request failure`() async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness { harness in
      harness.holdFirstSelection()
      let connecting = harness.track(Task {
        try await harness.manager.connectViaWiFi(host: harness.host, port: harness.port)
      })
      let candidate = try await harness.selectedCandidate()
      try await harness.endReceive(candidate.attempt)
      try #require(harness.manager.desiredRequest === candidate.request)
      harness.releaseSelection()
      await assertConnectionLoss(connecting.result, requestID: candidate.request.id, manager: harness.manager)
      #expect(await harness.bluetooth.connectCount == 0)
      #expect(!harness.manager.isReconnectionWatchdogRunning)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [false, true])
  func `modeled receive errors follow launch fallback and explicit failure paths`(launch: Bool) async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness(realSocket: false) { harness in
      harness.holdFirstSelection()
      let connecting = harness.track(Task<ConnectionResult?, Error> {
        if launch {
          await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
          return nil
        }
        return try await harness.manager.connectViaWiFi(host: harness.host, port: harness.port)
      })
      let candidate = try await harness.selectedCandidate()
      try await harness.endReceive(candidate.attempt, error: WiFiTransportError.connectionFailed("Modeled receive failure"))
      harness.releaseSelection()
      let outcome = await connecting.result
      if launch {
        #expect(await harness.bluetooth.connectCount == 1)
        #expect(harness.manager.currentTransportType == .bluetooth)
        #expect(harness.manager.connectionState == .ready)
      } else {
        assertConnectionLoss(outcome, requestID: candidate.request.id, manager: harness.manager)
        #expect(await harness.bluetooth.connectCount == 0)
      }
      try await harness.proveLaterIO()
    }
  }

  @Test
  func `ordinary failure of both launch targets still arms the Bluetooth watchdog`() async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness(realSocket: false) { harness in
      await harness.modeledWiFi.setConnectError(WiFiTransportError.notConnected)
      await harness.bluetooth.setConnectError(BLEError.authenticationFailed)
      await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
      #expect(await harness.modeledWiFi.connectCount == 1)
      #expect(await harness.bluetooth.connectCount == 1)
      #expect(harness.manager.currentConnection == nil)
      #expect(harness.manager.isReconnectionWatchdogRunning)
      await harness.modeledWiFi.setConnectError(nil)
      await harness.bluetooth.setConnectError(nil)
      try await harness.proveLaterIO()
    }
  }

  private func assertConnectionLoss(
    _ result: Result<some Any, Error>, requestID: UUID, manager: ConnectionManager
  ) {
    guard case let .failure(error) = result, let failure = error as? ConnectionRequestFailure else {
      Issue.record("An owned receive loss must throw ConnectionRequestFailure, including explicit caller feedback")
      return
    }
    #expect(failure.requestID == requestID)
    #expect(manager.isCurrent(failure))
    #expect(!(failure.underlying is CancellationError))
    let connectionLoss = if case ConnectionError.notConnected = failure.underlying { true } else if case WiFiTransportError.notConnected = failure.underlying { true } else { false }
    #expect(connectionLoss)
    #expect(manager.currentConnection == nil)
    #expect(manager.connectionIntent.wantsConnection)
  }
}
