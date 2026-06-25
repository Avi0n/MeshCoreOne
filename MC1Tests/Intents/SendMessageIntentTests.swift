import Foundation
import MeshCore
import Testing
@testable import MC1
@testable import MC1Services

/// `SendMessageIntent` is the safety-critical send: a message reported as sent
/// that wasn't is a safety lie. These tests pin the routing decision, the
/// durable-queue write, and the fail-safe paths where services are nil. A
/// successful send returns no spoken result, so the only honesty surface left is
/// the durable row and its real status. The send's pure classification seam and
/// the `performSend` durable-write seam are exercised with real assertions; the
/// framework-driven confirmation and foreground handoff are verified on device.
@MainActor
struct SendMessageIntentTests {

    private static let radioID = UUID(uuidString: "44444444-4444-4444-4444-444444444444")!

    // MARK: - Fixtures

    private static func makeContact(
        radioID: UUID = radioID,
        type: ContactType = .chat,
        name: String = "Alice"
    ) -> ContactDTO {
        ContactDTO(
            id: UUID(), radioID: radioID, publicKey: Data(repeating: 0xC1, count: 32), name: name,
            typeRawValue: type.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
            lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0,
            nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
            lastMessageDate: nil, unreadCount: 0
        )
    }

    private static func makeChannel(radioID: UUID = radioID, name: String = "Ops") -> ChannelDTO {
        ChannelDTO(
            id: UUID(), radioID: radioID, index: 0, name: name,
            secret: Data(repeating: 0x5A, count: 16), isEnabled: true,
            lastMessageDate: nil, unreadCount: 0
        )
    }

    private static func makeDevice(radioID: UUID = radioID, name: String = "Base Camp") -> DeviceDTO {
        DeviceDTO(
            id: UUID(), radioID: radioID, publicKey: Data(repeating: 0x01, count: 32),
            nodeName: name, firmwareVersion: 8, firmwareVersionString: "1.10",
            manufacturerName: "Test", buildDate: "", maxContacts: 100, maxChannels: 16,
            frequency: 0, bandwidth: 0, spreadingFactor: 0, codingRate: 0, txPower: 0,
            maxTxPower: 0, latitude: 0, longitude: 0, blePin: 0, clientRepeat: false,
            pathHashMode: 0, manualAddContacts: false, autoAddConfig: 0, autoAddMaxHops: 0,
            multiAcks: 0, telemetryModeBase: 0, telemetryModeLoc: 0, telemetryModeEnv: 0,
            advertLocationPolicy: 0, lastConnected: Date(), lastContactSync: 0, isActive: true,
            ocvPreset: nil, customOCVArrayString: nil, connectionMethods: []
        )
    }

    /// Seeds an `AppState` with a real `ServiceContainer` over an in-memory store
    /// scoped to `radioID`, at the given rung. Returns the live store so a test
    /// can assert the durable `PendingSend` write.
    private func seedReady(
        _ appState: AppState,
        state: DeviceConnectionState
    ) throws -> ServiceContainer {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let services = ServiceContainer(
            session: MeshCoreSession(transport: MockTransport()),
            modelContainer: container,
            radioID: Self.radioID
        )
        appState.connectionManager.setTestState(
            connectionState: state,
            services: services,
            connectedDevice: Self.makeDevice()
        )
        return services
    }

    // MARK: - Routing (pure)

    @Test func readyRoutesToHeadlessQueue() {
        #expect(SendMessageIntent.route(for: .ready) == .headlessQueue)
    }

    @Test func syncingRoutesToQueueAfterSync() {
        #expect(SendMessageIntent.route(for: .syncing) == .queueAfterSync)
    }

    @Test(arguments: [DeviceConnectionState.connected, .connecting])
    func transientRoutesToForegroundEscalation(_ state: DeviceConnectionState) {
        #expect(SendMessageIntent.route(for: state) == .foregroundEscalate)
    }

    @Test func disconnectedRoutesToNotConnected() {
        #expect(SendMessageIntent.route(for: .disconnected) == .notConnected)
    }

    @Test func restorableRadioForegroundEscalatesNeverThrows() {
        // A last-connected radio is restorable, so the send hands off to the
        // foregrounded app rather than failing.
        #expect(SendMessageIntent.disconnectedRoute(hasRestorableRadio: true) == .foregroundEscalate)
    }

    @Test func neverConnectedSurfacesNotConnected() {
        // No prior radio: retrying alone cannot help, so the send throws.
        #expect(SendMessageIntent.disconnectedRoute(hasRestorableRadio: false) == .notConnected)
    }

    // MARK: - Validation

    @Test func repeaterDMRejected() throws {
        let repeater = Self.makeContact(type: .repeater)
        let thrown = #expect(throws: IntentError.self) {
            try SendMessageIntent.validate(message: "hi", for: .contact(repeater), nodeNameByteCount: 0)
        }
        #expect(isCase(try #require(thrown), .invalidRecipient))
    }

    @Test func roomDMRejected() throws {
        let room = Self.makeContact(type: .room)
        let thrown = #expect(throws: IntentError.self) {
            try SendMessageIntent.validate(message: "hi", for: .contact(room), nodeNameByteCount: 0)
        }
        #expect(isCase(try #require(thrown), .invalidRecipient))
    }

    @Test func chatDMAccepted() throws {
        try SendMessageIntent.validate(message: "hi", for: .contact(Self.makeContact()), nodeNameByteCount: 0)
    }

    @Test func overlongChannelMessageRejected() throws {
        let maxBytes = ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 0)
        let tooLong = String(repeating: "x", count: maxBytes + 1)
        let thrown = #expect(throws: IntentError.self) {
            try SendMessageIntent.validate(message: tooLong, for: .channel(Self.makeChannel()), nodeNameByteCount: 0)
        }
        #expect(isCase(try #require(thrown), .messageTooLong))
    }

    @Test func channelMessageAtLimitAccepted() throws {
        let maxBytes = ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 0)
        let atLimit = String(repeating: "x", count: maxBytes)
        try SendMessageIntent.validate(message: atLimit, for: .channel(Self.makeChannel()), nodeNameByteCount: 0)
    }

    @Test func channelMessageFittingTotalButExceedingNodeNameBudgetRejected() throws {
        // The firmware prepends "<NodeName>: " to channel broadcasts, so the
        // usable text is the total length minus the node name and separator. A
        // message that fits the 147-byte total but not the adjusted budget would
        // be silently truncated on the air, so the intent must reject it.
        let nodeName = "Base Camp"
        let nodeNameByteCount = nodeName.utf8.count
        let adjustedMax = ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: nodeNameByteCount)
        let message = String(repeating: "x", count: adjustedMax + 1)
        #expect(message.utf8.count <= ProtocolLimits.maxChannelMessageTotalLength)

        let thrown = #expect(throws: IntentError.self) {
            try SendMessageIntent.validate(
                message: message, for: .channel(Self.makeChannel()), nodeNameByteCount: nodeNameByteCount
            )
        }
        #expect(isCase(try #require(thrown), .messageTooLong))
    }

    // MARK: - Error rewrap

    /// `IntentError` carries a non-Equatable `MeshCoreError`, so cases are matched
    /// structurally; the surfaced `errorDescription` (what Siri speaks) pins the
    /// localized mapping.
    private func isCase(_ error: IntentError, _ expected: IntentError) -> Bool {
        switch (error, expected) {
        case (.notConnected, .notConnected),
             (.invalidRecipient, .invalidRecipient), (.messageTooLong, .messageTooLong),
             (.sendFailed, .sendFailed), (.advertFailed, .advertFailed),
             (.sessionError, .sessionError):
            return true
        default:
            return false
        }
    }

    @Test func serviceErrorsRewrapToLocalizedIntentError() {
        #expect(isCase(SendMessageIntent.mapToIntentError(MessageServiceError.notConnected), .notConnected))
        #expect(isCase(SendMessageIntent.mapToIntentError(MessageServiceError.messageTooLong), .messageTooLong))
        #expect(isCase(SendMessageIntent.mapToIntentError(MessageServiceError.invalidRecipient), .invalidRecipient))
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.channelNotFound), .invalidRecipient))

        // A wrapped session error preserves its underlying MeshCoreError, and the
        // surfaced description routes through L10n (never a raw service string).
        let wrapped = SendMessageIntent.mapToIntentError(MessageServiceError.sessionError(.timeout))
        #expect(isCase(wrapped, .sessionError(.timeout)))
        #expect(wrapped.errorDescription == L10n.Localizable.Error.MeshCore.timeout)
    }

    /// Pins the full `ChannelServiceError` bucketing in `mapToIntentError`: a
    /// bad index reads as an invalid recipient, every local-failure case reads as
    /// a send failure (never a disconnect), and a wrapped session error preserves
    /// its underlying `MeshCoreError`.
    @Test func channelServiceErrorsBucketIntoIntentErrors() {
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.notConnected), .notConnected))
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.invalidChannelIndex), .invalidRecipient))

        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.secretHashingFailed), .sendFailed))
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.sendFailed("io")), .sendFailed))
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.syncAlreadyInProgress), .sendFailed))
        #expect(
            isCase(
                SendMessageIntent.mapToIntentError(ChannelServiceError.circuitBreakerOpen(consecutiveFailures: 3)),
                .sendFailed
            )
        )

        let wrapped = SendMessageIntent.mapToIntentError(ChannelServiceError.sessionError(.timeout))
        #expect(isCase(wrapped, .sessionError(.timeout)))
        #expect(wrapped.errorDescription == L10n.Localizable.Error.MeshCore.timeout)
    }

    /// A failure to persist or enqueue a connected send must speak as a send
    /// failure, never "not connected"; the radio is up, the local write failed.
    @Test func sendWriteFailuresMapToSendFailedNotNotConnected() {
        let queuePersistFailed = ChatSendQueueServiceError.persistFailed(underlying: MeshCoreError.timeout)
        #expect(isCase(SendMessageIntent.mapToIntentError(queuePersistFailed), .sendFailed))
        #expect(isCase(SendMessageIntent.mapToIntentError(MessageServiceError.sendFailed("io")), .sendFailed))
        #expect(isCase(SendMessageIntent.mapToIntentError(ChannelServiceError.saveFailed("io")), .sendFailed))

        // The spoken line routes through L10n, not a raw error string.
        #expect(
            SendMessageIntent.mapToIntentError(queuePersistFailed).errorDescription
                == L10n.Localizable.Error.Intent.sendFailed
        )

        // A genuine not-connected from the queue still reads as not connected.
        #expect(isCase(SendMessageIntent.mapToIntentError(ChatSendQueueServiceError.notConnected), .notConnected))

        // An unrecognized error during a send is a send failure, not a disconnect.
        let unknown = NSError(domain: "test", code: 1)
        #expect(isCase(SendMessageIntent.mapToIntentError(unknown), .sendFailed))
    }

    // MARK: - .ready DM enqueue (durable write)

    @Test func readyDMEnqueuesPendingSend() async throws {
        let appState = AppState()
        let services = try seedReady(appState, state: .ready)
        let contact = Self.makeContact(name: "Alice")
        try await services.dataStore.saveContact(contact)

        let outcome = try await SendMessageIntent.performSend(
            message: "on my way", recipient: .contact(contact), in: appState
        )
        #expect(outcome == .queued)

        // The durable PendingSend row is what survives a drop and drains at .ready.
        let pending = try await services.dataStore.fetchPendingSends(radioID: Self.radioID)
        #expect(pending.count == 1)
    }

    // MARK: - .ready channel enqueue (durable write)

    @Test func readyChannelEnqueuesPendingSend() async throws {
        let appState = AppState()
        let services = try seedReady(appState, state: .ready)
        let channel = Self.makeChannel(name: "Ops")
        try await services.dataStore.saveChannel(channel)

        let outcome = try await SendMessageIntent.performSend(
            message: "radio check", recipient: .channel(channel), in: appState
        )
        #expect(outcome == .queued)

        let pending = try await services.dataStore.fetchPendingSends(radioID: Self.radioID)
        #expect(pending.count == 1)
    }

    // MARK: - channel length re-validated against the live node name at send time

    @Test func channelMessageRevalidatedAgainstLiveNodeNameAtSendTime() async throws {
        // A message sized to the zero-node-name budget clears the pre-confirmation
        // validate, but the live radio ("Base Camp") shrinks the on-air budget by
        // the prepended "<NodeName>: ". performSend must re-validate and reject it
        // rather than enqueue a message the firmware would silently truncate.
        let appState = AppState()
        let services = try seedReady(appState, state: .ready)
        let channel = Self.makeChannel(name: "Ops")
        try await services.dataStore.saveChannel(channel)

        let overBudget = String(repeating: "x", count: ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 0))

        let thrown = await #expect(throws: IntentError.self) {
            try await SendMessageIntent.performSend(
                message: overBudget, recipient: .channel(channel), in: appState
            )
        }
        #expect(isCase(try #require(thrown), .messageTooLong))

        // The reject precedes the durable write, so nothing is enqueued.
        let pending = try await services.dataStore.fetchPendingSends(radioID: Self.radioID)
        #expect(pending.isEmpty)
    }

    // MARK: - enqueue-failure orphan recovery primitive

    /// `performSend`'s catch marks a persisted-but-unenqueued row `.failed` via
    /// `updateMessageStatusUnlessDelivered` so an enqueue-write failure surfaces a
    /// retry instead of hanging `.pending`. Forcing the enqueue itself to throw is
    /// not reachable through any existing test seam (the queue persists through the
    /// concrete in-memory `PersistenceStore`, which does not fail), so this pins
    /// the recovery primitive on a real row built by production `createPendingMessage`:
    /// a `.pending` row flips to `.failed`, while a `.delivered` row is left intact
    /// so the recovery can never downgrade a delivered send.
    @Test func updateMessageStatusUnlessDeliveredFailsPendingButSparesDelivered() async throws {
        let appState = AppState()
        let services = try seedReady(appState, state: .ready)
        let contact = Self.makeContact()
        try await services.dataStore.saveContact(contact)

        let pending = try await services.messageService.createPendingMessage(text: "queued", to: contact)
        #expect(try await services.dataStore.fetchMessage(id: pending.id)?.status == .pending)

        let flipped = try await services.dataStore.updateMessageStatusUnlessDelivered(id: pending.id, status: .failed)
        #expect(flipped)
        #expect(try await services.dataStore.fetchMessage(id: pending.id)?.status == .failed)

        let delivered = try await services.messageService.createPendingMessage(text: "acked", to: contact)
        try await services.dataStore.updateMessageAck(id: delivered.id, ackCode: 0x1234_5678, status: .delivered)

        let spared = try await services.dataStore.updateMessageStatusUnlessDelivered(id: delivered.id, status: .failed)
        #expect(!spared)
        #expect(try await services.dataStore.fetchMessage(id: delivered.id)?.status == .delivered)
    }

    // MARK: - .syncing takes the queue

    @Test func syncingEnqueuesPendingSend() async throws {
        let appState = AppState()
        let services = try seedReady(appState, state: .syncing)
        let contact = Self.makeContact(name: "Bravo")
        try await services.dataStore.saveContact(contact)

        // The .syncing rung classifies as .queueAfterSync, which shares the queue
        // branch with .ready: the durable row is written either way.
        #expect(SendMessageIntent.route(for: .syncing) == .queueAfterSync)
        let outcome = try await SendMessageIntent.performSend(
            message: "staging", recipient: .contact(contact), in: appState
        )
        #expect(outcome == .queued)

        let pending = try await services.dataStore.fetchPendingSends(radioID: Self.radioID)
        #expect(pending.count == 1)
    }

    // MARK: - services-nil during confirmation re-routes to foreground (no fabricated queued)

    @Test func servicesNilAfterClassifyRoutesToForegroundNotASilentEnqueue() async throws {
        let appState = AppState()
        // Classified .ready with a live service, then the radio drops during the
        // confirmation await: services go nil. performSend must report
        // .mustForeground, never a "queued" for a send that never enqueued.
        _ = try seedReady(appState, state: .ready)
        let contact = Self.makeContact()
        appState.connectionManager.setTestState(services: .some(nil))

        let outcome = try await SendMessageIntent.performSend(
            message: "dropped", recipient: .contact(contact), in: appState
        )
        #expect(outcome == .mustForeground)
    }

    // MARK: - radio switch during confirmation re-routes to foreground (no cross-radio enqueue)

    @Test func recipientFromAnotherRadioRoutesToForegroundNotACrossRadioEnqueue() async throws {
        let appState = AppState()
        // Live radio is B (the seeded service and connected device). The recipient
        // was resolved against radio A before the confirmation await, so enqueuing
        // would scope the message row to A and the PendingSend to B, mis-routing
        // the send. performSend must report .mustForeground and write nothing.
        let services = try seedReady(appState, state: .ready)
        let otherRadioID = UUID(uuidString: "55555555-5555-5555-5555-555555555555")!
        let contact = Self.makeContact(radioID: otherRadioID)

        let outcome = try await SendMessageIntent.performSend(
            message: "switched radios", recipient: .contact(contact), in: appState
        )
        #expect(outcome == .mustForeground)

        let pending = try await services.dataStore.fetchPendingSends(radioID: Self.radioID)
        #expect(pending.isEmpty)
        let otherPending = try await services.dataStore.fetchPendingSends(radioID: otherRadioID)
        #expect(otherPending.isEmpty)
    }

    // MARK: - bare .connected with no services never silently enqueues

    @Test func connectedWithoutServicesNeverEnqueues() async throws {
        let appState = AppState()
        // Bare .connected: the rung is set before services are built, so services
        // can be nil. The route classifies .foregroundEscalate (never the queue),
        // and even if performSend were reached it returns .mustForeground.
        appState.connectionManager.setTestState(
            connectionState: .connected, services: .some(nil), connectedDevice: Self.makeDevice()
        )
        #expect(SendMessageIntent.route(for: .connected) == .foregroundEscalate)

        let outcome = try await SendMessageIntent.performSend(
            message: "nope", recipient: .contact(Self.makeContact()), in: appState
        )
        #expect(outcome == .mustForeground)
    }
}
