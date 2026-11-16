import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("WiFi pre-publication loss authority controls")
@MainActor
struct ConnectionManagerWiFiEstablishmentControlTests {
  @Test(arguments: [false, true])
  func `actual caller cancellation retains cancellation after TCP EOF`(launch: Bool) async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness { harness in
      harness.holdFirstSelection()
      let connecting = harness.track(Task<ConnectionResult?, Error> {
        if launch {
          await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
          return nil
        }
        return try await harness.manager.connectViaWiFi(host: harness.host, port: harness.port)
      })
      let candidate = try await harness.selectedCandidate()
      let worker = try #require(candidate.request.worker)
      try await harness.endReceive(candidate.attempt)
      connecting.cancel()
      try await waitUntil("caller cancellation must cancel the actual request worker before selection resumes") {
        candidate.request.worker?.isCancelled == true
      }
      harness.releaseSelection()
      _ = await connecting.result
      let outcome = await worker.result
      if case .success(.cancelled) = outcome {} else { Issue.record("An actually cancelled request must retain cancellation") }
      #expect(harness.manager.desiredRequest === candidate.request)
      #expect(harness.manager.currentConnection == nil)
      #expect(await harness.bluetooth.connectCount == 0)
      #expect(!harness.manager.isReconnectionWatchdogRunning)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [false, true])
  func `explicit disconnect and matching removal stay terminal after TCP EOF`(removal: Bool) async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness { harness in
      harness.holdFirstSelection()
      let launch = harness.track(Task {
        await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
      })
      let candidate = try await harness.selectedCandidate()
      let worker = try #require(candidate.request.worker)
      try await harness.endReceive(candidate.attempt)
      let terminal = harness.track(Task {
        if removal { try await harness.manager.deleteDevice(id: harness.saved.id) } else { _ = await harness.manager.disconnect(reason: .userInitiated) }
      })
      try await waitUntil("terminal action must revoke this request before selection resumes") {
        harness.manager.desiredRequest !== candidate.request
      }
      harness.releaseSelection()
      await launch.value
      try await terminal.value
      let outcome = await worker.result
      if case .success(.superseded) = outcome {} else { Issue.record("A terminal action must supersede the old request") }
      #expect(!harness.manager.connectionIntent.wantsConnection)
      #expect(harness.manager.currentConnection == nil)
      #expect(await harness.bluetooth.connectCount == 0)
      #expect(!harness.manager.isReconnectionWatchdogRunning)
      if removal {
        let ghost = try #require(try await harness.manager.persistenceStore.fetchDevice(publicKey: harness.saved.publicKey))
        #expect(ghost.radioID == harness.saved.radioID)
        #expect(ghost.connectionMethods.isEmpty)
      }
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [false, true])
  func `a new same-radio or different-radio request supersedes the lost WiFi candidate`(sameRadio: Bool) async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness { harness in
      harness.holdFirstSelection()
      let connecting = harness.track(Task {
        try await harness.manager.connectViaWiFi(host: harness.host, port: harness.port)
      })
      let candidate = try await harness.selectedCandidate()
      try await harness.endReceive(candidate.attempt)
      let target = sameRadio ? harness.saved.id : UUID()
      if !sameRadio {
        await harness.bluetooth.setPublicKey(Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize), for: target)
      }
      let replacement = harness.track(Task { try await harness.manager.connect(to: target, forceReconnect: true) })
      try await waitUntil("replacement must acquire user authority before old selection resumes") {
        harness.manager.desiredRequest !== candidate.request
      }
      #expect(await harness.bluetooth.connectCount == 0)
      harness.releaseSelection()
      let oldOutcome = try await connecting.value
      if case .superseded = oldOutcome {} else { Issue.record("A replaced request must retain supersession") }
      let current = try await replacement.value
      #expect(harness.manager.isCurrent(current))
      #expect(harness.manager.connectedDevice?.id == target)
      #expect(harness.manager.desiredRequest?.binding?.isActive == true)
      #expect(await harness.bluetooth.isConnected)
      let session = try #require(harness.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
      try await harness.proveLaterIO()
    }
  }

  @Test
  func `pre-publication Bluetooth OS handoff does not advance the launch WiFi fallback`() async throws {
    try await ConnectionWiFiEstablishmentHarness.withHarness(wifiLast: false) { harness in
      await harness.manager.wireTransportHandlers()
      harness.holdFirstSelection()
      let launch = harness.track(Task {
        await harness.manager.reconnectLastUsedTransport(device: harness.saved, lastDeviceID: harness.saved.id)
      })
      let candidate = try await harness.selectedCandidate()
      let worker = try #require(candidate.request.worker)
      var lossHandled = false
      var rebuildHandled = false
      harness.manager.testBLELifecycleEventHandled = { event in
        switch event {
        case .autoReconnecting: lossHandled = true
        case .reconnected: rebuildHandled = true
        default: break
        }
      }
      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.saved.id)
      try await waitUntil("production BLE lifecycle handling must finish retirement of the unpublished candidate") { lossHandled }
      try #require(candidate.request.handedOff)
      try #require(!candidate.attempt.binding.isActive)
      harness.releaseSelection()
      await launch.value
      let outcome = await worker.result
      if case let .success(.handoff(requestID)) = outcome { #expect(requestID == candidate.request.id) } else { Issue.record("An OS-owned recovery must retain handoff") }
      #expect(candidate.request.targetIndex == 0)
      #expect(await harness.peer?.acceptedCount == 0)
      #expect(!harness.manager.isReconnectionWatchdogRunning)
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("the same request's OS rebuild must finish and become ready") {
        rebuildHandled && harness.manager.connectionState == .ready
      }
      #expect(harness.manager.connectionRequestID == candidate.request.id)
      #expect(harness.manager.desiredRequest?.binding?.isActive == true)
      #expect(await harness.bluetooth.connectCount == 1)
      let session = try #require(harness.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
      try await harness.proveLaterIO()
    }
  }
}
