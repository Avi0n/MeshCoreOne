import AppIntents
import MC1Services

/// Shadow entity for a send target, either a contact (DM) or a channel
/// (broadcast), so a single Shortcuts recipient picker can list both. Its id is
/// radio-scoped and kind-tagged: the contact key is the public key, the channel
/// key a non-reversible secret digest, never the raw secret, the volatile
/// `ContactDTO`/`ChannelDTO` row UUID, or the firmware slot index (which drifts
/// when a channel is relocated). The kind is re-derived from the id at resolution
/// time, so the in-memory `kind` read here drives display only.
struct MessageTargetEntity: AppEntity {
    static let typeDisplayRepresentation = TypeDisplayRepresentation(
        name: LocalizedStringResource("intent.entity.target", table: "Tools")
    )
    static let defaultQuery = MessageTargetQuery()

    let id: String
    let kind: MessageTargetKind
    let displayName: String
    let subtitle: String

    var displayRepresentation: DisplayRepresentation {
        let symbol: String
        switch kind {
        case .contact: symbol = "person.fill"
        case .channel: symbol = "number"
        }
        return DisplayRepresentation(
            title: "\(displayName)",
            subtitle: "\(subtitle)",
            image: .init(systemName: symbol)
        )
    }

    init(dto: ContactDTO) {
        self.id = formatCompositeID(radioID: dto.radioID, kind: .contact, keyHex: dto.publicKey.hexString)
        self.kind = .contact
        self.displayName = dto.displayName
        self.subtitle = dto.publicKeyPrefix.hexString
    }

    init(dto: ChannelDTO) {
        self.id = formatCompositeID(
            radioID: dto.radioID,
            kind: .channel,
            keyHex: channelSecretDigestHex(radioID: dto.radioID, secret: dto.secret)
        )
        self.kind = .channel
        self.displayName = dto.displayName
        self.subtitle = L10n.Tools.Intent.Entity.channel
    }
}
