import Foundation
@testable import MC1Services
import Testing

@Suite("Explicit admission during retirement")
@MainActor
struct ConnectionManagerAdmissionPortTests {
  enum Entry: CaseIterable {
    case connect
    case bluetooth
    case wifi
    case simulator
  }

  @Test(.timeLimit(.minutes(1)), arguments: Entry.allCases)
  func `an older explicit entry waiting for retirement cannot replace a newer choice`(entry: Entry) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let retirement = Gate()
    var older: Task<ConnectionResult, Error>?
    var newer: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let initial = try #require(env.manager.currentConnection)
      env.manager.testConnectionTeardownHandler = { await retirement.wait() }
      older = Task {
        switch entry {
        case .connect:
          try await env.manager.connect(to: env.deviceID, forceFullSync: true, forceReconnect: true)
        case .bluetooth:
          try await env.manager.connectViaBluetooth(peripheralID: env.deviceID, forceFullSync: true)
        case .wifi:
          try await env.manager.connectViaWiFi(host: "older.test", port: 5123, forceFullSync: true)
        case .simulator:
          try await env.manager.simulatorConnect()
        }
      }
      try await waitUntil("the older choice must claim retirement before the newer choice arrives") { retirement.isWaiting }
      let oldRequest = try #require(env.manager.desiredRequest)
      #expect(oldRequest.id != initial.requestID)
      let nextID = UUID()
      newer = Task { try await env.manager.connect(to: nextID, forceFullSync: true, forceReconnect: true) }
      try await waitUntil("the newer choice must be admitted while predecessor retirement is held") {
        env.manager.desiredRequest?.target == .bluetooth(nextID)
      }
      let nextRequestID = env.manager.connectionRequestID
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(await env.ble.connectCount == 1)
      #expect(await env.wifi.connectCount == 0)
      retirement.open()
      let oldResult = try await #require(older).value
      #expect(!env.manager.isCurrent(oldResult))
      let result = try await #require(newer).value
      #expect(env.manager.isCurrent(result))
      #expect(env.manager.connectionRequestID == nextRequestID)
      #expect(env.manager.connectedDevice?.id == nextID)
      #expect(env.manager.lastConnectedDeviceID == nextID)
      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.connectionTeardownCount == 0)
      #expect(await env.ble.connectCount == 2)
      #expect(await env.wifi.connectCount == 0)
      let current = try #require(env.manager.currentConnection)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
    } catch {
      retirement.open()
      older?.cancel()
      newer?.cancel()
      env.manager.testConnectionTeardownHandler = nil
      await env.ble.releaseAllGates()
      await env.wifi.releaseAllGates()
      _ = await older?.result
      _ = await newer?.result
      await env.close()
      throw error
    }
    env.manager.testConnectionTeardownHandler = nil
    await env.close()
  }

  @MainActor
  private final class Gate {
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false
    var isWaiting: Bool {
      !continuations.isEmpty
    }

    func wait() async {
      guard !isOpen else { return }
      await withCheckedContinuation { continuations.append($0) }
    }

    func open() {
      isOpen = true
      let waiting = continuations
      continuations.removeAll()
      for continuation in waiting {
        continuation.resume()
      }
    }
  }
}
