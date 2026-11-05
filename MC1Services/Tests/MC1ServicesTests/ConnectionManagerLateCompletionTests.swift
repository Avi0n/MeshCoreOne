import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Connection late completions")
@MainActor
struct ConnectionManagerLateCompletionTests {
  @Test(arguments: [false, true])
  func `accepted selection settles before replacement or terminal metadata`(disconnect: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = BLEReconnectionTestHarness.Gate()
    var acceptedRequest: UUID?
    env.manager.testConnectionSelectionHandler = { requestID in
      guard acceptedRequest == nil else { return }
      acceptedRequest = requestID
      await gate.wait()
    }
    let first = Task { try await env.connect(.bluetooth) }
    var next: Task<ConnectionResult, Error>?
    var terminal: Task<ConnectionLossReceipt, Never>?
    do {
      try await waitUntil("first connection must reach accepted selection") { acceptedRequest != nil }
      if disconnect {
        terminal = Task { await env.manager.disconnect() }
      } else {
        next = Task { try await env.connect(.wifi) }
      }
      try await waitUntil("successor must be admitted before old metadata finishes") {
        env.manager.connectionRequestID != acceptedRequest
      }
      #expect(await env.wifi.connectCount == 0)
      #expect(env.manager.currentConnection == nil)
      gate.open()
      #expect(try await !env.manager.isCurrent(first.value))
      if let terminal {
        _ = await terminal.value
        #expect(env.manager.currentConnection == nil)
        #expect(try await env.manager.persistenceStore.fetchActiveDevice() == nil)
        #expect(env.manager.lastConnectedDeviceID == nil)
      }
      if let next {
        #expect(try await env.manager.isCurrent(next.value))
        let current = try #require(env.manager.currentConnection)
        let saved = try #require(try await env.manager.persistenceStore.fetchActiveDevice())
        #expect(saved.id == current.device.id)
        #expect(saved.radioID == current.device.radioID)
        #expect(saved.lastUsedConnectionMethod?.isWiFi == true)
        #expect(env.manager.lastConnectedDeviceID == current.device.id)
      }
    } catch {
      gate.open()
      first.cancel()
      next?.cancel()
      _ = await first.result
      _ = await next?.result
      _ = await terminal?.value
      await env.close()
      throw error
    }
    env.manager.testConnectionSelectionHandler = nil
    await env.close()
  }

  @Test
  func `an accepted Device update merges fresh metadata before the successor dials`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = BLEReconnectionTestHarness.Gate()
    var successor: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let old = try #require(env.manager.currentConnection)
      let blocker = env.manager.enqueueMetadata { _ in await gate.wait() }
      env.manager.updateDefaultFloodScopeName("accepted scope")
      try await env.manager.persistenceStore.addDeviceKnownRegion(radioID: old.device.radioID, region: "local region")
      successor = Task { try await env.connect(.wifi) }
      try await waitUntil("successor must replace the old request") {
        env.manager.connectionRequestID != old.requestID
      }
      #expect(await env.wifi.connectCount == 0)
      gate.open()
      _ = await blocker.result
      #expect(try await env.manager.isCurrent(#require(successor).value))
      let saved = try #require(try await env.manager.persistenceStore.fetchActiveDevice())
      #expect(saved.defaultFloodScopeName == "accepted scope")
      #expect(saved.knownRegions == ["local region"])
      #expect(saved.lastUsedConnectionMethod?.isWiFi == true)
    } catch {
      gate.open()
      successor?.cancel()
      _ = await successor?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `replacement between old clock read and reply sends no old followup`() async throws {
    let env = try ConnectionManagerRadioTestHarness(sameRadio: false)
    await env.ble.setRadioTime(Date(timeIntervalSince1970: 1000))
    await env.ble.holdReplies(for: .getTime)
    let first = Task { try await env.connect(.bluetooth) }
    var successor: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("first connection must reach its clock tail") { await env.ble.timeReads == 1 }
      let requestID = env.manager.connectionRequestID
      successor = Task { try await env.connect(.wifi) }
      try await waitUntil("successor must retire the old clock owner") { env.manager.connectionRequestID != requestID }
      #expect(await env.wifi.connectCount == 0)
      await env.ble.releaseReplies(for: .getTime)
      _ = try await first.value
      #expect(try await env.manager.isCurrent(#require(successor).value))
      #expect(await env.ble.timeWrites == 0)
      let noObsoleteTimezoneWrite = await !env.ble.cliCommands.contains {
        ConnectionTestRadioTransport.splitCLICommand($0).command == "get tz.offset"
      }
      #expect(noObsoleteTimezoneWrite)
      #expect(await env.wifi.timeReads == 1)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.ble.releaseAllGates()
      first.cancel()
      successor?.cancel()
      _ = await first.result
      _ = await successor?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `retired clean sync callbacks cannot stamp replacement freshness`(sameRadio: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness(sameRadio: sameRadio)
    do {
      _ = try await env.connect(.bluetooth)
      let old = try #require(env.manager.currentConnection)
      let clean = await old.services.syncCoordinator.onCleanChannelSync
      let attempted = await old.services.syncCoordinator.onChannelSyncAttempted
      _ = try await env.connect(.wifi)
      let beforeClean = env.manager.lastCleanChannelSync
      let beforeAttempt = env.manager.lastAttemptedChannelSync
      await clean?(old.device.radioID)
      await attempted?(old.device.radioID)
      #expect(env.manager.lastCleanChannelSync?.radioID == beforeClean?.radioID)
      #expect(env.manager.lastCleanChannelSync?.completedAt == beforeClean?.completedAt)
      #expect(env.manager.lastAttemptedChannelSync?.radioID == beforeAttempt?.radioID)
      #expect(env.manager.lastAttemptedChannelSync?.attemptedAt == beforeAttempt?.attemptedAt)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch { await env.close(); throw error }
    await env.close()
  }
}
