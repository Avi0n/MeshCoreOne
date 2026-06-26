import AppIntents
import MC1Services

/// Resolves `MessageTargetEntity` values for the current radio, surfacing chat
/// contacts and channels in one picker. Scoped to a single radio: a `nil`
/// current radio yields an empty result, never an error. The protocol methods
/// stay nonisolated and hop to a single `@MainActor` fetch that reads the live
/// connection through `IntentBridge`.
struct MessageTargetQuery: EntityQuery {
    @Dependency private var bridge: IntentBridge

    /// Re-fetches by id against the current radio and resolves through
    /// `resolveMessageTargets`. Deliberately does not chat-filter:
    /// `SendMessageIntent.validate` is the type gate, so a contact that changed
    /// type after being saved still resolves here and is rejected at send time.
    func entities(for identifiers: [MessageTargetEntity.ID]) async throws -> [MessageTargetEntity] {
        guard !identifiers.isEmpty else { return [] }
        let fetched = try await fetchTargets()
        return resolveMessageTargets(matching: identifiers, contacts: fetched.contacts, channels: fetched.channels)
    }

    func suggestedEntities() async throws -> [MessageTargetEntity] {
        let fetched = try await fetchTargets()
        return buildMessageTargets(contacts: chatContacts(fetched.contacts), channels: fetched.channels)
    }

    @MainActor
    private func fetchTargets() async throws -> (contacts: [ContactDTO], channels: [ChannelDTO]) {
        guard let scope = currentRadioScope(bridge) else { return ([], []) }
        let contacts = try await scope.store.fetchContacts(radioID: scope.radioID)
        let channels = try await scope.store.fetchChannels(radioID: scope.radioID)
        return (contacts, channels)
    }
}

extension MessageTargetQuery: EntityStringQuery {
    func entities(matching string: String) async throws -> [MessageTargetEntity] {
        let needle = string.lowercased()
        let fetched = try await fetchTargets()
        return buildMessageTargets(contacts: chatContacts(fetched.contacts), channels: fetched.channels)
            .filter { $0.displayName.lowercased().contains(needle) }
    }
}
