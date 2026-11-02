import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Owned connection completion")
@MainActor
struct ConnectionManagerPromotionTests {
  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `production connection publishes readiness before its best effort clock tail`(type: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let radio = type == .bluetooth ? env.ble : env.wifi
    await radio.holdReplies(for: .getTime)
    var readyEdges = 0
    var synced = 0
    env.manager.onConnectionBecameUsable = { snapshot in
      #expect(env.manager.isCurrent(snapshot))
      #expect(synced == 0)
      readyEdges += 1
    }
    env.manager.onDeviceSynced = { snapshot, hasCleanRecipientData in
      #expect(env.manager.isCurrent(snapshot))
      #expect(hasCleanRecipientData)
      synced += 1
    }
    let connect = Task { try await env.connect(type) }
    do {
      try await waitUntil("the production sync should make the connection usable") { env.manager.connectionState == .ready }
      #expect(readyEdges == 1)
      #expect(synced == 0)
      await radio.releaseReplies(for: .getTime)
      let result = try await connect.value
      #expect(env.manager.isCurrent(result))
      #expect(synced == 1)
      #expect(await radio.timeReads == 1)
      #expect(await radio.cliCommands.filter { ConnectionTestRadioTransport.splitCLICommand($0).command == "get tz.offset" }.count == 1)
    } catch {
      await radio.releaseAllGates()
      connect.cancel()
      _ = await connect.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `failed initial contacts recover through the same production completion once`(type: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let radio = type == .bluetooth ? env.ble : env.wifi
    await radio.rejectNext(.getContacts)
    var synced = 0
    var readyEdges = 0
    env.manager.onDeviceSynced = { _, _ in synced += 1 }
    env.manager.onConnectionBecameUsable = { _ in readyEdges += 1 }
    do {
      _ = try await env.connect(type)
      #expect(env.manager.connectionState == .syncing)
      if type == .bluetooth { #expect(await radio.timeReads == 0) }
      if type == .wifi { #expect(await radio.timeReads == 1) }
      try await waitUntil(timeout: .seconds(12), "production resync should recover contacts") { synced == 1 }
      #expect(env.manager.connectionState == .ready)
      #expect(readyEdges == 1)
      #expect(await radio.timeReads == 1)
      #expect(await radio.cliCommands.filter { ConnectionTestRadioTransport.splitCLICommand($0).command == "get tz.offset" }.count == 1)
    } catch { await env.close(); throw error }
    await env.close()
  }

  enum CompletionOutcome: CaseIterable {
    case partialChannels
    case failedMessages
    case missingResult

    var syncResult: FullSyncResult? {
      switch self {
      case .partialChannels: FullSyncResult(contacts: .clean, channels: .partial, messages: .clean)
      case .failedMessages: FullSyncResult(contacts: .clean, channels: .clean, messages: .failed("catch-up"))
      case .missingResult: nil
      }
    }

    var expectedEligibility: Bool? {
      switch self {
      case .partialChannels: false
      case .failedMessages: true
      case .missingResult: nil
      }
    }
  }

  @Test(arguments: CompletionOutcome.allCases)
  func `owned completion forwards recipient eligibility from its sync result`(outcome: CompletionOutcome) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    await env.ble.rejectNext(.getContacts)
    var eligibility: Bool?
    env.manager.onDeviceSynced = { snapshot, hasCleanRecipientData in
      #expect(env.manager.isCurrent(snapshot))
      eligibility = hasCleanRecipientData
    }
    do {
      _ = try await env.connect(.bluetooth)
      #expect(env.manager.connectionState == .syncing)
      #expect(eligibility == nil)
      env.manager.cancelResyncLoop()
      let link = try #require(env.manager.installedLink)
      let completed = await env.manager.completeSync(link, syncResult: outcome.syncResult)
      #expect(completed)
      #expect(env.manager.connectionState == (outcome.syncResult == nil ? .syncing : .ready))
      #expect(eligibility == outcome.expectedEligibility)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `same radio transport handoff preserves partition and replaces the Link generation`(first: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(first)
      let before = try #require(env.manager.currentConnection)
      _ = try await env.connect(first == .bluetooth ? .wifi : .bluetooth)
      let after = try #require(env.manager.currentConnection)
      #expect(after.device.radioID == before.device.radioID)
      #expect(after.generation != before.generation)
      #expect(!env.manager.isCurrent(before))
      let active = try await env.manager.persistenceStore.fetchActiveDevice()
      #expect(active?.id == after.device.id)
      #expect(active?.lastUsedConnectionMethod?.isWiFi == (after.transportType == .wifi))
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test
  func `a loss receipt cannot become current again after a newer stack retires`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      let request = try #require(env.manager.desiredRequest)
      let old = env.manager.beginOwnedRetirement(previousRequest: request, phase: .connecting, closePhysical: true, retainMembership: true)
      await old.value
      #expect(env.manager.isCurrent(old.receipt))
      _ = try await env.manager.establishAttempt(request, preservePhysicalBLE: false)
      let newer = env.manager.beginOwnedRetirement(previousRequest: request, phase: .disconnected, closePhysical: true, retainMembership: true)
      await newer.value
      #expect(env.manager.isCurrent(newer.receipt))
      #expect(!env.manager.isCurrent(old.receipt))
    } catch { await env.close(); throw error }
    await env.close()
  }
}
