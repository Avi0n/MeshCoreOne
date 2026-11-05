import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Prepared queue retirement ownership")
@MainActor
struct ConnectionManagerPreparedQueuePortTests {
  @Test(.timeLimit(.minutes(1)))
  func `unpublished candidate cannot advance persisted sends when its successor becomes ready`() async throws {
    let env = try ConnectionManagerRadioTestHarness(sameRadio: false)
    let selection = Gate()
    let radioID = UUID()
    let device = DeviceDTO.testDevice(id: env.deviceID, radioID: radioID, publicKey: env.ble.publicKey)
    try await env.manager.persistenceStore.saveDevice(device)
    let contact = ContactDTO.testContact(radioID: radioID)
    try await env.manager.persistenceStore.saveContact(contact)
    let message = MessageDTO(from: Message(
      radioID: radioID, contactID: contact.id, text: "pending before replacement", timestamp: 1_700_000_000
    ))
    try await env.manager.persistenceStore.saveMessage(message)
    try await env.manager.persistenceStore.upsertPendingSend(PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id), radioID: radioID
    ))
    let original = try #require(try await env.manager.persistenceStore.fetchPendingSendsForMessage(messageID: message.id).first)
    var firstSelection = true
    env.manager.testConnectionSelectionHandler = { _ in
      if firstSelection {
        firstSelection = false
        await selection.wait()
      }
    }
    let first = Task { try await env.connect(.bluetooth) }
    var replacement: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("old preparation must reach metadata selection before publication") { selection.isWaiting }
      let request = try #require(env.manager.desiredRequest)
      let attempt = try #require(request.attempt)
      let prepared = try #require(attempt.preparedServices)
      #expect(env.manager.currentConnection == nil)
      replacement = Task { try await env.connect(.wifi) }
      try await waitUntil("replacement must synchronously revoke its predecessor") {
        env.manager.connectionRequestID != request.id
      }
      #expect(!attempt.binding.isActive)
      #expect(await env.wifi.connectCount == 0)
      let held = try #require(try await env.manager.persistenceStore.fetchPendingSendsForMessage(messageID: message.id).first)
      #expect(held.attemptCount == original.attemptCount)
      selection.open()
      _ = await first.result
      let result = try await #require(replacement).value
      #expect(env.manager.isCurrent(result))
      let current = try #require(env.manager.currentConnection)
      #expect(env.manager.connectionState == .ready)
      #expect(current.device.radioID != radioID)
      #expect(!prepared.isAcceptingRuntimeWork)
      #expect(prepared.chatSendQueueService.isRetiredForTesting)
      let after = try #require(try await env.manager.persistenceStore.fetchPendingSendsForMessage(messageID: message.id).first)
      #expect(after.attemptCount == original.attemptCount)
      #expect(await env.ble.sentData.allSatisfy { $0.first != CommandCode.sendMessage.rawValue })
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      #expect(await prepared.session.subscriberCountForTest == 0)
    } catch {
      selection.open()
      first.cancel()
      replacement?.cancel()
      env.manager.testConnectionSelectionHandler = nil
      await env.ble.releaseAllGates()
      await env.wifi.releaseAllGates()
      _ = await first.result
      _ = await replacement?.result
      await env.close()
      throw error
    }
    env.manager.testConnectionSelectionHandler = nil
    await env.close()
  }

  @MainActor
  private final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    var isWaiting: Bool {
      continuation != nil
    }

    func wait() async {
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
