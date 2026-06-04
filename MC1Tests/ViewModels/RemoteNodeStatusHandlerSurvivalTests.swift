import Foundation
import MeshCore
import Testing

@testable import MC1
@testable import MC1Services

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

    private func makeAppState() throws -> (AppState, ServiceContainer) {
        let radioID = UUID()
        let services = ServiceContainer(
            session: MeshCoreSession(transport: MockTransport()),
            modelContainer: try PersistenceStore.createContainer(inMemory: true),
            radioID: radioID
        )
        let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true))
        appState.connectionManager.setTestState(services: services)
        return (appState, services)
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

    @Test("Repeater registerHandlers preserves a previously-set CLI handler")
    func repeaterRegisterHandlersKeepsCLIHandler() async throws {
        let (appState, services) = try makeAppState()
        let service = services.repeaterAdminService

        let flag = FlagBox()
        await service.setCLIHandler { _, _ in flag.set() }

        let viewModel = RepeaterStatusViewModel()
        await viewModel.registerHandlers(appState: appState)

        // The CLI handler set by the settings/CLI surface must still fire.
        await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

        #expect(flag.isSet, "CLI handler should survive the status view model registering its own handlers")
    }

    @Test("Room registerHandlers preserves a previously-set CLI handler")
    func roomRegisterHandlersKeepsCLIHandler() async throws {
        let (appState, services) = try makeAppState()
        let service = services.roomAdminService

        let flag = FlagBox()
        await service.setCLIHandler { _, _ in flag.set() }

        let viewModel = RoomStatusViewModel()
        await viewModel.registerHandlers(appState: appState)

        await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

        #expect(flag.isSet, "CLI handler should survive the status view model registering its own handlers")
    }

    @Test("Repeater cleanup clears the CLI handler on true teardown")
    func repeaterCleanupClearsCLIHandler() async throws {
        let (appState, services) = try makeAppState()
        let service = services.repeaterAdminService

        let flag = FlagBox()
        await service.setCLIHandler { _, _ in flag.set() }

        let viewModel = RepeaterStatusViewModel()
        viewModel.configure(appState: appState)
        await viewModel.cleanup(appState: appState)

        await service.invokeCLIHandler(makeContactMessage(), fromContact: makeContact())

        #expect(!flag.isSet, "cleanup should clear every handler slot including the CLI handler")
    }
}
