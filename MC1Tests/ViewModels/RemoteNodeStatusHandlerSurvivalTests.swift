import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// Verifies that a status view model's `registerHandlers` only sets the slots it
/// owns and leaves a previously-registered CLI handler intact. The repeater and
/// room admin services are shared across the status and settings view models, so
/// clobbering the CLI handler here would silently break late CLI-response delivery.
@Suite("Remote Node Status Handler Survival")
@MainActor
struct RemoteNodeStatusHandlerSurvivalTests {
  /// Thread-safe flag the CLI handler flips.
  private final class FlagBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    func set() {
      lock.lock(); defer { lock.unlock() }
      value = true
    }

    var isSet: Bool {
      lock.lock(); defer { lock.unlock() }
      return value
    }
  }

  private static let contactPublicKey = Data(repeating: 0x7A, count: 32)
  private static let messageText = "cli response"

  private func makeServices() throws -> ServiceContainer {
    try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      radioID: UUID()
    )
  }

  private func makeContactMessage() -> ContactMessage {
    ContactMessage(
      senderPublicKeyPrefix: Self.contactPublicKey.prefix(6),
      pathLength: 0,
      textType: 0,
      senderTimestamp: Date(timeIntervalSince1970: 0),
      signature: nil,
      text: Self.messageText,
      snr: nil
    )
  }

  private func makeContact() -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Self.contactPublicKey,
      name: "Test Node",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  @Test
  func `Repeater registerHandlers preserves a previously-set CLI handler`() async throws {
    let services = try makeServices()
    let service = services.repeaterAdminService

    let flag = FlagBox()
    await service.setCLIHandler { _, _ in flag.set() }

    let viewModel = RepeaterStatusViewModel()
    viewModel.configure(
      repeaterAdminService: { service },
      contactService: { nil },
      nodeSnapshotService: { nil }
    )
    await viewModel.registerHandlers()

    // The CLI handler set by the settings/CLI surface must still fire.
    await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

    #expect(flag.isSet, "CLI handler should survive the status view model registering its own handlers")
  }

  @Test
  func `Room registerHandlers preserves a previously-set CLI handler`() async throws {
    let services = try makeServices()
    let service = services.roomAdminService

    let flag = FlagBox()
    await service.setCLIHandler { _, _ in flag.set() }

    let viewModel = RoomStatusViewModel()
    viewModel.configure(
      roomAdminService: { service },
      contactService: { nil },
      nodeSnapshotService: { nil }
    )
    await viewModel.registerHandlers()

    await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

    #expect(flag.isSet, "CLI handler should survive the status view model registering its own handlers")
  }

  private func makeStatusResponse() -> StatusResponse {
    StatusResponse(
      publicKeyPrefix: Self.contactPublicKey.prefix(6),
      battery: 0,
      txQueueLength: 0,
      noiseFloor: 0,
      lastRSSI: 0,
      packetsReceived: 0,
      packetsSent: 0,
      airtime: 0,
      uptime: 0,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 0,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 0
    )
  }

  @Test
  func `Repeater clearStatusHandlers leaves the CLI handler firing`() async throws {
    let services = try makeServices()
    let service = services.repeaterAdminService

    let cliFlag = FlagBox()
    let statusFlag = FlagBox()
    await service.setCLIHandler { _, _ in cliFlag.set() }

    let viewModel = RepeaterStatusViewModel()
    viewModel.configure(
      repeaterAdminService: { service },
      contactService: { services.contactService },
      nodeSnapshotService: { services.nodeSnapshotService }
    )
    await viewModel.registerHandlers()
    await service.setStatusHandler { _ in statusFlag.set() }

    await viewModel.clearStatusHandlers()

    await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())
    await service.invokeStatusHandler(makeStatusResponse())

    #expect(cliFlag.isSet, "CLI handler should survive clearing the status-surface handlers")
    #expect(!statusFlag.isSet, "status handler should no longer fire after clearStatusHandlers")
  }

  @Test
  func `Room clearStatusHandlers leaves the CLI handler firing`() async throws {
    let services = try makeServices()
    let service = services.roomAdminService

    let cliFlag = FlagBox()
    let statusFlag = FlagBox()
    await service.setCLIHandler { _, _ in cliFlag.set() }

    let viewModel = RoomStatusViewModel()
    viewModel.configure(
      roomAdminService: { service },
      contactService: { services.contactService },
      nodeSnapshotService: { services.nodeSnapshotService }
    )
    await viewModel.registerHandlers()
    await service.setStatusHandler { _ in statusFlag.set() }

    await viewModel.clearStatusHandlers()

    await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())
    await service.invokeStatusHandler(makeStatusResponse())

    #expect(cliFlag.isSet, "CLI handler should survive clearing the status-surface handlers")
    #expect(!statusFlag.isSet, "status handler should no longer fire after clearStatusHandlers")
  }

  @Test
  func `Repeater cleanup clears the CLI handler on true teardown`() async throws {
    let services = try makeServices()
    let service = services.repeaterAdminService

    let flag = FlagBox()
    await service.setCLIHandler { _, _ in flag.set() }

    let viewModel = RepeaterStatusViewModel()
    viewModel.configure(
      repeaterAdminService: { service },
      contactService: { services.contactService },
      nodeSnapshotService: { services.nodeSnapshotService }
    )
    await viewModel.cleanup()

    await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

    #expect(!flag.isSet, "cleanup should clear every handler slot including the CLI handler")
  }
}
