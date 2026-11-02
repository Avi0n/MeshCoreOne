import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Remote login contact recovery")
struct RemoteNodeLoginContactRecoveryTests {
  @Test(arguments: [true, false])
  @MainActor
  func `real login heals only a contact present locally after readiness`(hasLocalContact: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let link = try #require(harness.manager.installedLink)
      _ = await link.completionWorker?.value
      try #require(link.didCompleteSync)
      let session = link.resource.session
      let store = harness.manager.persistenceStore
      let publicKey = Data(repeating: 0xC4, count: ProtocolLimits.publicKeySize)
      let contact = ContactDTO.testContact(
        radioID: harness.deviceA.radioID, publicKey: publicKey, typeRawValue: ContactType.room.rawValue,
        outPathLength: 2, outPath: Data([0x12, 0x34])
      )
      if hasLocalContact { try await store.saveContact(contact) }
      let membership = RemoteNodeSessionDTO.testSession(
        radioID: harness.deviceA.radioID, publicKey: publicKey, isConnected: false
      )
      try await store.saveRemoteNodeSessionDTO(membership)
      let local = try await store.fetchContact(radioID: harness.deviceA.radioID, publicKey: publicKey)
      try #require((local != nil) == hasLocalContact)
      if let local { try #require(!local.isFloodRouted) }
      let radioContacts = try await session.getContacts()
      let contactIsAbsentOnRadio = !radioContacts.contains { $0.publicKey == publicKey }
      try #require(contactIsAbsentOnRadio)
      try #require(try await !harness.isConnected(membership))
      let frameCount = await harness.bluetooth.sentData.count
      let login = harness.track(Task {
        try await link.services.remoteNodeService.login(sessionID: membership.id, password: "synthetic-fixture")
      })
      if hasLocalContact {
        #expect(try await login.value.success)
      } else {
        let error = await #expect(throws: RemoteNodeError.self) { _ = try await login.value }
        if let error {
          let isContactNotFound = if case .contactNotFound = error { true } else { false }
          #expect(isContactNotFound)
        }
      }
      let commands = await harness.bluetooth.sentData.dropFirst(frameCount).compactMap { data -> CommandCode? in
        guard let raw = data.first, let command = CommandCode(rawValue: raw),
              command == .sendLogin || command == .updateContact else { return nil }
        return command
      }
      let expectedCommands: [CommandCode] = hasLocalContact ? [.sendLogin, .updateContact, .sendLogin] : [.sendLogin]
      #expect(commands == expectedCommands)
      #expect(try await harness.isConnected(membership) == hasLocalContact)
      let healed = try await store.fetchContact(radioID: harness.deviceA.radioID, publicKey: publicKey)
      #expect(healed?.isFloodRouted == (hasLocalContact ? true : nil))
      let updatedContacts = try await session.getContacts()
      let contactIsNowOnRadio = updatedContacts.contains { $0.publicKey == publicKey }
      #expect(contactIsNowOnRadio == hasLocalContact)
      let timeReads = await harness.bluetooth.timeReads
      _ = try await session.getTime()
      #expect(await harness.bluetooth.timeReads == timeReads + 1)
    }
  }
}
