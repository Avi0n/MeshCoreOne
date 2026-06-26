import Foundation
import MC1Services

// MARK: - Scope resolution

/// The radio and persistence store an intent query reads from. Resolves the
/// store explicitly because pickers populate while disconnected and in a
/// cold/background context with no live `ServiceContainer`: when connected the
/// live `dataStore`, otherwise a standalone store over the same open container.
/// A `nil` current radio (no connection and nothing last-connected, or a
/// pre-launch bridge with no `AppState`) yields nil, which every caller maps to
/// an empty result rather than an error.
@MainActor
func currentRadioScope(_ bridge: IntentBridge) -> (radioID: UUID, store: PersistenceStore)? {
    guard let appState = bridge.appState,
          let radioID = appState.currentRadioID else { return nil }
    let store = appState.services?.dataStore
        ?? appState.connectionManager.createStandalonePersistenceStore()
    return (radioID, store)
}

// MARK: - Channel resolution

/// Matches saved-shortcut ids against live channels by their non-reversible
/// secret digest, returning each unambiguous match's DTO. A digest matching zero
/// or more than one channel fails safe to no match rather than guessing, so a
/// relocated or duplicated channel can never mis-route a send.
func resolveUniqueChannels(matching ids: [String], in channels: [ChannelDTO]) -> [ChannelDTO] {
    let wanted = Set(ids)
    var matchesByID: [String: [ChannelDTO]] = [:]
    for channel in channels {
        let id = formatCompositeID(
            radioID: channel.radioID,
            kind: .channel,
            keyHex: channelSecretDigestHex(radioID: channel.radioID, secret: channel.secret)
        )
        guard wanted.contains(id) else { continue }
        matchesByID[id, default: []].append(channel)
    }
    return wanted.compactMap { id in
        guard let matches = matchesByID[id], matches.count == 1 else { return nil }
        return matches.first
    }
}

// MARK: - Picker list

/// Chat contacts only: a DM target must be a person, never a repeater or room.
func chatContacts(_ contacts: [ContactDTO]) -> [ContactDTO] {
    contacts.filter { $0.type == .chat }
}

/// Resolves saved-shortcut ids against fetched DTOs the way `entities(for:)`
/// does: partition by the kind parsed from each id (dropping anything
/// unparseable), match contacts by exact id-equality and channels through the
/// zero/duplicate-digest fail-safe. The two strategies are distinct and must not
/// collapse: a channel digest can collide across rows, a public-key id cannot.
func resolveMessageTargets(matching ids: [String], contacts: [ContactDTO], channels: [ChannelDTO]) -> [MessageTargetEntity] {
    let wanted = Set(ids)
    let contactIDs = wanted.filter { parseCompositeID($0)?.kind == .contact }
    let channelIDs = wanted.filter { parseCompositeID($0)?.kind == .channel }
    let contactMatches = contacts
        .map(MessageTargetEntity.init(dto:))
        .filter { contactIDs.contains($0.id) }
    let channelMatches = resolveUniqueChannels(matching: Array(channelIDs), in: channels)
        .map(MessageTargetEntity.init(dto:))
    return contactMatches + channelMatches
}

/// The combined recipient-picker list: contacts then channels, each sorted by
/// display name so the order is stable across cold launches. Channels whose
/// secret digest collides are omitted because the send path cannot resolve them.
func buildMessageTargets(contacts: [ContactDTO], channels: [ChannelDTO]) -> [MessageTargetEntity] {
    func byDisplayName(_ lhs: MessageTargetEntity, _ rhs: MessageTargetEntity) -> Bool {
        lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
    }
    let contactEntities = contacts.map(MessageTargetEntity.init(dto:)).sorted(by: byDisplayName)
    let channelEntities = resolveUniqueChannels(matching: channels.map { MessageTargetEntity(dto: $0).id }, in: channels)
        .map(MessageTargetEntity.init(dto:))
        .sorted(by: byDisplayName)
    return contactEntities + channelEntities
}
