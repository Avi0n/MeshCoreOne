import Foundation
import MC1Services

/// Conversation type discriminator for the unified chat view.
/// Not `@MainActor` — no mutable state. `@State` on the view provides main-actor isolation.
enum ChatConversationType: Sendable {
    case dm(ContactDTO)
    case channel(ChannelDTO)

    // MARK: - Computed Properties

    var navigationTitle: String {
        switch self {
        case .dm(let contact):
            contact.displayName
        case .channel(let channel):
            channel.displayName
        }
    }

    func navigationSubtitle(deviceDefaultFloodScopeName: String?) -> String {
        switch self {
        case .dm(let contact):
            if contact.isFloodRouted {
                return L10n.Chats.Chats.ConnectionStatus.floodRouting
            } else {
                return L10n.Chats.Chats.ConnectionStatus.direct(contact.pathHopCount)
            }
        case .channel(let channel):
            let base = channelTypeSubtitle(for: channel)
            if let region = effectiveRegionName(for: channel, deviceDefaultFloodScopeName: deviceDefaultFloodScopeName) {
                let regionDisplay = (region == deviceDefaultFloodScopeName)
                    ? L10n.Chats.Chats.ChannelInfo.Region.scopedDefault(region)
                    : region
                return "\(base) \u{00B7} \(regionDisplay)"
            }
            return base
        }
    }

    /// Accessibility label for the subtitle, providing a VoiceOver-friendly description
    /// when a region scope is active (the middle dot separator may be read literally).
    func navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: String?) -> String? {
        switch self {
        case .dm:
            return nil
        case .channel(let channel):
            guard let region = effectiveRegionName(for: channel, deviceDefaultFloodScopeName: deviceDefaultFloodScopeName) else {
                return nil
            }
            let typeSubtitle = channelTypeSubtitle(for: channel)
            if region == deviceDefaultFloodScopeName {
                return L10n.Chats.Chats.ChannelInfo.Region.defaultScopedAccessibility(typeSubtitle, region)
            }
            return L10n.Chats.Chats.ChannelInfo.Region.scopedAccessibility(typeSubtitle, region)
        }
    }

    // MARK: - Private Helpers

    private func channelTypeSubtitle(for channel: ChannelDTO) -> String {
        if channel.isPublicChannel {
            L10n.Chats.Chats.Channel.typePublic
        } else if channel.name.hasPrefix("#") {
            L10n.Chats.Chats.ChannelInfo.ChannelType.hashtag
        } else {
            L10n.Chats.Chats.Channel.typePrivate
        }
    }

    /// Resolves the region name to display alongside the channel subtitle. Delegates to
    /// ``ChannelFloodScopeResolver`` so the banner stays in sync with the FloodScope
    /// actually pushed to the radio.
    private func effectiveRegionName(
        for channel: ChannelDTO,
        deviceDefaultFloodScopeName: String?
    ) -> String? {
        let resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope: channel.floodScope,
            deviceDefaultFloodScopeName: deviceDefaultFloodScopeName,
            supportsUnscopedFloodSend: false
        )
        if case .scope(.region(let name)) = resolved { return name }
        return nil
    }

    var conversationID: UUID {
        switch self {
        case .dm(let contact):
            contact.id
        case .channel(let channel):
            channel.id
        }
    }

    var radioID: UUID {
        switch self {
        case .dm(let contact):
            contact.radioID
        case .channel(let channel):
            channel.radioID
        }
    }

    var isPublicStyleChannel: Bool {
        switch self {
        case .dm:
            false
        case .channel(let channel):
            !channel.isEncryptedChannel
        }
    }

    // MARK: - Transforms

    /// Returns a copy with the contact replaced (DM only). Returns self unchanged for channels.
    func replacingContact(_ contact: ContactDTO) -> ChatConversationType {
        guard case .dm = self else { return self }
        return .dm(contact)
    }

    /// Returns a copy with the channel replaced (channel only). Returns self unchanged for DMs.
    func replacingChannel(_ channel: ChannelDTO) -> ChatConversationType {
        guard case .channel = self else { return self }
        return .channel(channel)
    }
}
