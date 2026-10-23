import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

extension ContactCleanupTests {
  struct Environment {
    let manager: ConnectionManager
    let services: ServiceContainer
    let session: MeshCoreSession
    let transport: MockTransport
    let radioID: UUID
    let contactIDs: [UUID]

    @MainActor
    func stop() {
      Task {
        await services.tearDown()
        await session.stop()
      }
    }
  }

  func makeEnvironment() async throws -> Environment {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(modelContainer: container, defaults: defaults)
    let radioID = UUID()
    let store = manager.persistenceStore
    try await store.saveDevice(DeviceDTO(from: Device(
      radioID: radioID, publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: "Test"
    )))
    let (session, transport) = try await makeSession()
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    var ids: [UUID] = []
    for byte: UInt8 in [2, 3, 4] {
      let saved = try await store.saveContact(radioID: radioID, from: ContactFrame(
        publicKey: Data(repeating: byte, count: ProtocolLimits.publicKeySize),
        type: .chat, flags: 0, outPathLength: 0, outPath: Data(),
        name: "Contact \(byte)", lastAdvertTimestamp: 0,
        latitude: 0, longitude: 0, lastModified: 0
      ))
      ids.append(saved.id)
      try await store.saveMessage(MessageDTO(from: Message(
        radioID: radioID, contactID: saved.id, text: "History", timestamp: 0
      )))
    }
    try await manager.setTestState(
      connectionState: .ready, services: services,
      connectedDevice: store.fetchDevice(radioID: radioID)
    )
    return Environment(
      manager: manager, services: services, session: session, transport: transport,
      radioID: radioID, contactIDs: ids
    )
  }

  func makeSession() async throws -> (MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let start = Task { try await session.start() }
    try await waitForCommands(1, transport: transport)
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: ProtocolLimits.publicKeySize))
    packet.append(Data(repeating: 0, count: 11))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    await transport.simulateReceive(packet)
    try await start.value
    await transport.clearSentData()
    return (session, transport)
  }

  func waitForCommands(_ count: Int, transport: MockTransport) async throws {
    let deadline = ContinuousClock.now + .seconds(3)
    while await transport.sentData.count < count {
      try #require(ContinuousClock.now < deadline, "Radio command did not arrive")
      try await Task.sleep(for: .milliseconds(5))
    }
  }
}
