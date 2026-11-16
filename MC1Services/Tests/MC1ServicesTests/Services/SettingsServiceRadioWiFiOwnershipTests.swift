import Foundation
@testable import MC1Services
import Testing

@Suite("SettingsService WiFi ownership")
@MainActor
struct SettingsServiceRadioWiFiOwnershipTests {
  @Test
  func `closing an editor invalidates its known baseline at the service boundary`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    await service.closeRadioWiFiEditor(editorID: editorID)
    let before = await mock.runCliCommandCalls.count

    await #expect(throws: CancellationError.self) {
      try await service.saveRadioWiFiCredentials(
        ssid: "editor", password: "", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
      )
    }

    #expect(await mock.runCliCommandCalls.count == before)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `raw mutation invalidates an editor baseline before Save admission`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    _ = try await service.runCliCommand("set wifi.ssid terminal")
    let before = await mock.runCliCommandCalls.count

    await #expect(throws: CancellationError.self) {
      try await service.saveRadioWiFiCredentials(
        ssid: "editor", password: "", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
      )
    }

    #expect(await mock.runCliCommandCalls.count == before)
    let currentRevision = try #require(service.radioWiFiState.stableRevision())
    let invented = baseline.updating(ssid: baseline.ssid, password: baseline.password, revision: currentRevision)
    await #expect(throws: CancellationError.self) {
      try await service.saveRadioWiFiCredentials(
        ssid: "editor", password: "", baseline: invented, editorID: editorID, ownerIsCurrent: { true }
      )
    }
    #expect(await mock.runCliCommandCalls.count == before)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `raw mutation during baseline read prevents issuing the password read`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    await mock.holdNextCLIReply()
    let read = Task { try await service.readRadioWiFiCredentials(editorID: UUID()) }
    do {
      try await waitUntil("SSID baseline read held") { await mock.heldCLICommand == "get wifi.ssid" }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await read.value
      throw error
    }

    do {
      _ = try await service.runCliCommand("set wifi.ssid terminal")
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await read.value
      throw error
    }
    await mock.releaseHeldCLIReply()

    #expect(try await read.value == nil)
    #expect(await mock.runCliCommandCalls.contains("get wifi.pwd") == false)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `cancelled baseline read never supplies password knowledge or sends the next read`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    await mock.holdNextCLIReply()
    let read = Task { try await service.readRadioWiFiCredentials(editorID: UUID()) }
    do {
      try await waitUntil("SSID baseline read held") { await mock.heldCLICommand == "get wifi.ssid" }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await read.value
      throw error
    }

    read.cancel()
    await mock.releaseHeldCLIReply()
    await #expect(throws: CancellationError.self) { try await read.value }
    #expect(await mock.runCliCommandCalls.contains("get wifi.pwd") == false)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `foreign mutation after SSID acceptance stops the remaining password command`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    await mock.holdNextCLIReply()
    let save = Task {
      try await service.saveRadioWiFiCredentials(
        ssid: "editor", password: "editor-key", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
      )
    }
    do {
      try await waitUntil("typed SSID write held") { await mock.heldCLICommand == "set wifi.ssid editor" }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await save.value
      throw error
    }

    do {
      _ = try await service.runCliCommand("set wifi.enabled 0")
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await save.value
      throw error
    }
    await mock.releaseHeldCLIReply()
    let receipt = try await save.value

    #expect(receipt.ssidAccepted)
    #expect(!receipt.passwordAccepted)
    #expect(receipt.revision == nil)
    #expect(await mock.runCliCommandCalls.filter { $0.hasPrefix("set wifi.pwd ") }.isEmpty)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `cancelled Tools waiter and retired event consumer cannot discard captured-radio evidence`() async throws {
    let stateA = RadioWiFiState(radioID: UUID())
    let stateB = RadioWiFiState(radioID: UUID())
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let first = SettingsService(session: mock, radioWiFiState: stateA)
    let stream = await first.events()
    let consumer = Task { for await _ in stream {} }
    await mock.holdNextCLIReply()
    let waiter = Task { try await first.runCliCommand("set wifi.ssid terminal") }
    do {
      try await waitUntil("Tools command held") { await mock.heldCLICommand == "set wifi.ssid terminal" }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await waiter.value
      consumer.cancel()
      await consumer.value
      throw error
    }

    waiter.cancel()
    consumer.cancel()
    await consumer.value
    let retirement = Task { await first.retireRadioWiFiOperations() }
    do {
      try await waitUntil("service closes mutation admission") { await first.radioWiFiOperationsRetired }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await waiter.value
      await retirement.value
      throw error
    }
    #expect(stateA.stableRevision() == nil)
    #expect(!stateA.rebootPending)
    #expect(!stateB.rebootPending)

    await mock.releaseHeldCLIReply()
    _ = try await waiter.value
    await retirement.value
    let replacement = SettingsService(session: mock, radioWiFiState: stateA)
    #expect(stateA.rebootPending)
    #expect(!stateB.rebootPending)
    guard case let .supported(probe) = try await replacement.probeRadioWiFiSettings() else {
      throw SettingsServiceError.invalidResponse
    }
    let observation = try #require(await replacement.readRadioWiFiObservation(probe: probe))
    #expect(observation.error == nil)
    #expect(observation.networkNameWasRead)
    #expect(observation.associated != nil)
    #expect(observation.ipAddressWasRead)
    #expect(await replacement.radioWiFiRebootPending)
    await replacement.retireRadioWiFiOperations()
  }

  @Test
  func `retirement joins the held mutation and forbids its second command`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    await mock.holdNextCLIReply()
    let save = Task {
      try await service.saveRadioWiFiCredentials(
        ssid: "editor", password: "editor-key", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
      )
    }
    do {
      try await waitUntil("SSID write held") { await mock.heldCLICommand == "set wifi.ssid editor" }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await save.value
      throw error
    }
    let retirement = Task { await service.retireRadioWiFiOperations() }
    do {
      try await waitUntil("retirement starts") { await service.radioWiFiOperationsRetired }
    } catch {
      await mock.releaseHeldCLIReply()
      _ = try? await save.value
      await retirement.value
      throw error
    }

    await mock.releaseHeldCLIReply()
    let receipt = try await save.value
    await retirement.value
    #expect(receipt.ssidAccepted)
    #expect(!receipt.passwordAccepted)
    #expect(await mock.runCliCommandCalls.filter { $0.hasPrefix("set wifi.pwd ") }.isEmpty)
    #expect(await service.radioWiFiWorkers.isEmpty)
  }
}
