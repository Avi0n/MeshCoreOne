import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Native BLE to TCP ownership integration")
@MainActor
struct ConnectionNativeTCPPortTests {
  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `TCP successor waits for native candidate retirement and survives late BLE loss`(sameRadio: Bool) async throws {
    let run = try await NativeConnectionPortHarness()
    let peer = try await ConnectionLoopbackRadioPeer.make(publicKey: Data(repeating: sameRadio ? 1 : 2, count: 32))
    let gate = Gate()
    var firstSelection = true
    run.manager.testConnectionSelectionHandler = { _ in
      guard firstSelection else { return }
      firstSelection = false
      await gate.wait()
    }
    let first = Task { try await run.manager.connectViaBluetooth(peripheralID: run.native.deviceID) }
    var replacement: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("native candidate must reach metadata selection before publication") { gate.arrived }
      let oldRequest = try #require(run.manager.desiredRequest)
      let attempt = try #require(oldRequest.attempt)
      let resource = try #require(attempt.resource)
      let prepared = try #require(attempt.preparedServices)
      let capturedOrigin = try #require(oldRequest.physicalOrigin)
      let port = try #require(await peer.port)
      #expect(run.manager.installedLink == nil)
      replacement = Task { try await run.manager.connectViaWiFi(host: "127.0.0.1", port: port) }
      try await waitUntil("TCP admission must revoke the old candidate immediately") {
        run.manager.connectionRequestID != oldRequest.id
      }
      #expect(!attempt.binding.isActive)
      #expect(run.manager.installedLink == nil)
      #expect(await peer.acceptedCount == 0)
      gate.open()
      let oldResult = try await first.value
      #expect(!run.manager.isCurrent(oldResult))
      let result = try await #require(replacement).value
      #expect(run.manager.isCurrent(result))
      let current = try #require(run.manager.installedLink)
      run.remember(current)
      #expect(run.manager.connectionState == .ready)
      #expect(current.target.transportType == .wifi)
      #expect(current.resource !== resource)
      #expect(current.resource.binding.isActive)
      #expect(current.services !== prepared)
      #expect(current.device.publicKey == Data(repeating: sameRadio ? 1 : 2, count: 32))
      #expect((current.device.id == run.native.deviceID) == sameRadio)
      #expect((current.device.radioID == prepared.chatSendQueueService.radioID) == sameRadio)
      #expect(current.device.radioID == current.services.chatSendQueueService.radioID)
      #expect(!prepared.isAcceptingRuntimeWork)
      #expect(await resource.session.subscriberCountForTest == 0)
      #expect(await run.machine.currentAppSessionLiveDeviceID == nil)
      #expect(await peer.acceptedCount == 1)
      let closes = run.native.cancellationCount
      #expect(closes == 1)
      let requestID = run.manager.connectionRequestID
      await run.manager.handleBLELifecycleEvent(.disconnected(capturedOrigin, error: .authenticationFailed))
      #expect(run.manager.connectionRequestID == requestID)
      #expect(run.manager.installedLink === current)
      #expect(current.resource.binding.isActive)
      #expect(run.native.cancellationCount == closes)
      #expect(try await current.resource.session.getBattery().level == 3700)
      #expect(try await current.resource.session.getTime().timeIntervalSince1970 > 0)
      #expect(await peer.acceptedCount == 1)
    } catch {
      gate.open()
      first.cancel()
      replacement?.cancel()
      _ = await first.result
      _ = await replacement?.result
      await run.close()
      await peer.close()
      throw error
    }
    run.manager.testConnectionSelectionHandler = nil
    await run.close()
    await peer.close()
    #expect(await peer.stopped)
  }

  @MainActor
  private final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    private(set) var arrived = false

    func wait() async {
      arrived = true
      guard !isOpen else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      isOpen = true
      continuation?.resume()
      continuation = nil
    }
  }
}
