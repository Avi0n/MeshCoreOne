import Foundation
@testable import MC1Services
import Testing

@Suite("NodeConfig V-contact omission")
struct NodeConfigVContactTests {
  private let radioSelfKey = Data(repeating: 0x01, count: 32)
  private let fileSelfKey = Data(repeating: 0xAB, count: 32)
  private let normalKey = Data(repeating: 0x22, count: 32)

  @Test
  @MainActor
  func `Contacts export omits the live V-contact and keeps the radio public key`() async throws {
    let vKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: radioSelfKey))
    let (service, _) = try await makeService(
      selfPublicKey: radioSelfKey,
      deviceContacts: [
        meshContact(name: "Ada", publicKey: normalKey),
        meshContact(name: "V", publicKey: vKey),
      ],
      exportedPrivateKey: Data(repeating: 0x44, count: 64)
    )

    let config = try await service.exportConfig(sections: ConfigSections(nodeIdentity: true, contacts: true))

    #expect(config.publicKey == radioSelfKey.hexString)
    let contacts = try #require(config.contacts)
    #expect(contacts.map(\.publicKey) == [normalKey.hexString])
    #expect(contacts.map(\.name) == ["Ada"])
  }

  @Test
  @MainActor
  func `Contacts import skips the live V-contact and writes a changed contact`() async throws {
    let vKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: radioSelfKey))
    let (service, mesh) = try await makeService(
      selfPublicKey: radioSelfKey,
      deviceContacts: [
        meshContact(name: "Old", publicKey: normalKey),
        meshContact(name: "V", publicKey: vKey),
      ]
    )
    let config = MeshCoreNodeConfig(
      publicKey: radioSelfKey.hexString,
      contacts: [
        fileContact(name: "New", publicKey: normalKey.hexString, lastModified: 10),
        fileContact(name: "V", publicKey: vKey.hexString.uppercased()),
      ]
    )

    try await service.importConfig(config, sections: ConfigSections(contacts: true), radioID: UUID())

    let added = await mesh.addContactInvocations.map(\.contact)
    #expect(added.map(\.publicKey) == [normalKey])
    #expect(added.map(\.advertisedName) == ["New"])
  }

  @Test
  @MainActor
  func `Identity import skips the file public key's V-contact and writes the other contact`() async throws {
    let fileVKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: fileSelfKey))
    let liveVKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: radioSelfKey))
    let (service, mesh) = try await makeService(
      selfPublicKey: radioSelfKey,
      deviceContacts: [meshContact(name: "V", publicKey: liveVKey)]
    )
    let config = MeshCoreNodeConfig(
      publicKey: fileSelfKey.hexString.uppercased(),
      privateKey: String(repeating: "ab", count: 64),
      contacts: [
        fileContact(name: "V", publicKey: fileVKey.hexString.uppercased()),
        fileContact(name: "Kept", publicKey: normalKey.hexString, lastModified: 10),
      ]
    )

    try await service.importConfig(
      config,
      sections: ConfigSections(nodeIdentity: true, contacts: true),
      radioID: UUID()
    )

    let added = await mesh.addContactInvocations.map(\.contact)
    #expect(added.map(\.publicKey) == [normalKey])
    #expect(added.map(\.advertisedName) == ["Kept"])
  }

  private func makeService(
    selfPublicKey: Data,
    deviceContacts: [MeshContact],
    exportedPrivateKey: Data? = nil
  ) async throws -> (NodeConfigService, MockMeshCoreSession) {
    let configuration = MockConfigurationSession(publicKey: selfPublicKey)
    await configuration.setStubbedExportedPrivateKey(exportedPrivateKey)
    let settings = SettingsService(session: configuration)
    let mesh = MockMeshCoreSession()
    await mesh.setStubbedContacts(deviceContacts)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let channels = ChannelService(session: mesh, dataStore: store, rxLogService: nil)
    let service = NodeConfigService(
      session: mesh, settingsService: settings,
      channelService: channels, dataStore: store, syncCoordinator: nil
    )
    return (service, mesh)
  }

  private func meshContact(name: String, publicKey: Data) -> MeshContact {
    MeshContact(
      id: publicKey.hexString,
      publicKey: publicKey,
      type: .chat,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0xFF,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: Date(timeIntervalSince1970: 0)
    )
  }

  private func fileContact(name: String, publicKey: String, lastModified: UInt32 = 0) -> MeshCoreNodeConfig.ContactConfig {
    MeshCoreNodeConfig.ContactConfig(
      type: 1, name: name, publicKey: publicKey, flags: 0,
      latitude: "0", longitude: "0", lastAdvert: 0, lastModified: lastModified
    )
  }
}
