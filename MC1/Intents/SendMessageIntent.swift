import AppIntents
import MC1Services

/// Voice/Shortcuts intent to send text to a message target, either a contact
/// (DM) or a channel (broadcast), chosen from one recipient picker. The send is
/// honest about delivery: it routes through the durable persisted queue and only
/// ever reports the message as queued at the radio, never as sent or delivered,
/// because the enqueue returns before the radio ACK and a channel broadcast has
/// no ACK at all. When the radio is not ready the intent escalates to the
/// foreground or throws a localized error rather than silently dropping a
/// message the user believes was sent.
struct SendMessageIntent: AppIntent {
    static let title = LocalizedStringResource("intent.send.title", table: "Tools")
    static let description = IntentDescription(
        LocalizedStringResource("intent.send.description", table: "Tools")
    )
    static let openAppWhenRun = false

    /// A send broadcasts under the user's identity, so it must not run from a
    /// locked device; this gates running the send, not entity-picker resolution.
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: LocalizedStringResource("intent.send.param.target", table: "Tools"))
    var target: MessageTargetEntity

    @Parameter(title: LocalizedStringResource("intent.send.param.message", table: "Tools"))
    var message: String

    @Dependency var bridge: IntentBridge

    static var parameterSummary: some ParameterSummary {
        Summary("Send \(\.$message) to \(\.$target)", table: "Tools")
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        try await executeSend()
        return .result()
    }

    /// Drives the readiness matrix to a confirmation-gated send, a foreground
    /// handoff, or a thrown localized `IntentError`. A successful queue returns
    /// no spoken result: the confirmation prompt is the only interaction, and the
    /// message's true status surfaces in the chat rather than as a second popup.
    @MainActor
    private func executeSend() async throws {
        guard let appState = bridge.appState else {
            try await continueInForeground()
            return
        }

        // Classify the route before resolving a recipient: only the queue paths
        // need one, and resolution depends on a live radio scope, so resolving it
        // up front would surface `invalidRecipient` on a disconnected radio where
        // the route is the true diagnosis. A drop during the later confirmation
        // await is re-checked inside `performSend`.
        let route = Self.route(for: appState.connectionManager.connectionState)
        switch route {
        case .headlessQueue, .queueAfterSync:
            let recipient = try await resolveRecipient()
            let nodeNameByteCount = appState.connectedDevice?.nodeName.utf8.count ?? 0
            try Self.validate(message: message, for: recipient, nodeNameByteCount: nodeNameByteCount)
            try await requestConfirmation(dialog: IntentDialog(stringLiteral: Self.confirmText(for: recipient)))
            switch try await Self.performSend(message: message, recipient: recipient, in: appState) {
            case .queued:
                return
            case .mustForeground:
                try await continueInForeground()
            }
        case .foregroundEscalate:
            try await continueInForeground()
        case .notConnected:
            let hasRestorableRadio = appState.connectionManager.lastConnectedRadioID != nil
            switch Self.disconnectedRoute(hasRestorableRadio: hasRestorableRadio) {
            case .foregroundEscalate:
                try await continueInForeground()
            default:
                throw IntentError.notConnected
            }
        }
    }

    // MARK: - Recipient resolution

    /// Re-resolves the chosen target id against the currently connected radio,
    /// carrying only a Sendable DTO across the actor boundary. Routing keys on the
    /// kind parsed from the id, never the entity's in-memory `kind`, and re-fetches
    /// (rather than trusting the saved-shortcut entity) so a stale or other-radio
    /// id can't orphan the message on a radio it doesn't belong to. An
    /// unparseable id fails safe to `invalidRecipient`.
    @MainActor
    private func resolveRecipient() async throws -> MessageRecipient {
        switch parseCompositeID(target.id)?.kind {
        case .contact:
            guard let dto = try await liveContact(for: target) else {
                throw IntentError.invalidRecipient
            }
            return .contact(dto)
        case .channel:
            guard let dto = try await liveChannel(for: target) else {
                throw IntentError.invalidRecipient
            }
            return .channel(dto)
        case nil:
            throw IntentError.invalidRecipient
        }
    }

    @MainActor
    private func liveContact(for entity: MessageTargetEntity) async throws -> ContactDTO? {
        guard let scope = currentRadioScope(bridge) else { return nil }
        let contacts = try await scope.store.fetchContacts(radioID: scope.radioID)
        return contacts.first { MessageTargetEntity(dto: $0).id == entity.id }
    }

    @MainActor
    private func liveChannel(for entity: MessageTargetEntity) async throws -> ChannelDTO? {
        guard let scope = currentRadioScope(bridge) else { return nil }
        let channels = try await scope.store.fetchChannels(radioID: scope.radioID)
        return resolveUniqueChannels(matching: [entity.id], in: channels).first
    }

    // MARK: - Enqueue

    /// Performs the durable-queue send after confirmation, re-reading services
    /// first. `requestConfirmation` is an unbounded user-driven await, and a drop
    /// during it nils `services` synchronously, so a nil read here means the
    /// radio is no longer ready: the send returns `.mustForeground` so the caller
    /// escalates rather than reporting a queued message that never enqueued.
    /// Reached only with a confirmed-live decision; carries Sendable DTOs.
    @MainActor
    static func performSend(
        message: String,
        recipient: MessageRecipient,
        in appState: AppState
    ) async throws -> SendOutcome {
        guard let services = appState.services else { return .mustForeground }
        // The recipient was resolved against one radio before the confirmation
        // await; a switch to another radio during it leaves services non-nil for
        // the new radio. Enqueuing here would scope the message row to the old
        // radio and the PendingSend to the new one, mis-routing the send, so a
        // radio change foreground-escalates exactly like a dropped connection.
        guard appState.currentRadioID == recipient.radioID else { return .mustForeground }
        // Re-validate against the node name the envelope is about to capture. The
        // budget checked before the unbounded confirmation await can be stale: a
        // rename or reconnect during it changes the firmware-prepended
        // "<NodeName>: " length, and a now-too-long channel message would
        // otherwise be silently truncated on the air.
        let nodeNameByteCount = appState.connectedDevice?.nodeName.utf8.count ?? 0
        try validate(message: message, for: recipient, nodeNameByteCount: nodeNameByteCount)

        let pending: MessageDTO
        do {
            switch recipient {
            case .contact(let dto):
                pending = try await services.messageService.createPendingMessage(text: message, to: dto)
            case .channel(let dto):
                pending = try await services.messageService.createPendingChannelMessage(
                    text: message,
                    channelIndex: dto.index,
                    radioID: dto.radioID
                )
            }
        } catch {
            throw mapToIntentError(error)
        }

        do {
            switch recipient {
            case .contact(let dto):
                try await services.chatSendQueueService.enqueueDM(
                    DirectMessageEnvelope(messageID: pending.id, contactID: dto.id)
                )
            case .channel(let dto):
                try await services.chatSendQueueService.enqueueChannel(
                    ChannelMessageEnvelope(
                        messageID: pending.id,
                        channelIndex: dto.index,
                        isResend: false,
                        messageText: pending.text,
                        messageTimestamp: pending.timestamp,
                        localNodeName: appState.connectedDevice?.nodeName
                    )
                )
            }
        } catch {
            // The row is already persisted as `.pending`, but the enqueue write
            // failed so no `PendingSend` backs it and the drain never sends it.
            // Mark it `.failed` so it surfaces a retry instead of hanging pending,
            // the same recovery the chat send path performs.
            _ = try? await services.dataStore.updateMessageStatusUnlessDelivered(id: pending.id, status: .failed)
            throw mapToIntentError(error)
        }
        // A headless send never crosses the chat view model that an in-app send
        // refreshes through, so announce the change here or the Chats list keeps
        // its cached preview until an unrelated reload fires.
        appState.refreshConversations()
        return .queued
    }

    /// Foregrounds the app (still launching, connecting, or a restorable
    /// disconnect) so the user can finish the send there, speaking the handoff
    /// prompt as it hands off. The dictated text is not carried across: this
    /// hands control to the app rather than enqueuing the message itself.
    @MainActor
    private func continueInForeground() async throws {
        try await requestToContinueInForeground(
            IntentDialog(stringLiteral: L10n.Tools.Intent.Send.foreground)
        )
    }
}

@available(iOSApplicationExtension, unavailable)
extension SendMessageIntent: ForegroundContinuableIntent {}
