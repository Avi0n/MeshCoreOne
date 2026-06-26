import SwiftUI
import CoreLocation
import MC1Services

struct ContactRowView: View {
    @Environment(\.appState) private var appState
    let contact: ContactDTO
    let showTypeLabel: Bool
    let userLocation: CLLocation?
    let isTogglingFavorite: Bool
    let inboundHopCount: Int?

    init(
        contact: ContactDTO,
        showTypeLabel: Bool = false,
        userLocation: CLLocation? = nil,
        isTogglingFavorite: Bool = false,
        inboundHopCount: Int? = nil
    ) {
        self.contact = contact
        self.showTypeLabel = showTypeLabel
        self.userLocation = userLocation
        self.isTogglingFavorite = isTogglingFavorite
        self.inboundHopCount = inboundHopCount
    }

    var body: some View {
        HStack(spacing: 12) {
            avatarView

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    (Text(idPrefixHex)
                        .monospaced()
                        .foregroundStyle(.secondary)
                        + Text(" \(contact.displayName)")
                            .fontWeight(.medium))
                        .font(.body)
                        .accessibilityLabel(contact.displayName)

                    if contact.isBlocked {
                        Image(systemName: "hand.raised.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                            .accessibilityLabel(L10n.Contacts.Contacts.Row.blocked)
                    }

                    Spacer()

                    if isTogglingFavorite {
                        ProgressView()
                            .controlSize(.small)
                    } else if contact.isFavorite {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(.yellow)
                            .accessibilityLabel(L10n.Contacts.Contacts.Row.favorite)
                    }

                    RelativeTimestampText(timestamp: contact.lastModified)
                }

                HStack(spacing: 8) {
                    // Show type label only in search results
                    if showTypeLabel {
                        Text(contactTypeLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Text("\u{00B7}")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // Route indicator
                    Text(routeLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // Location indicator with optional distance
                    if contact.hasLocation {
                        Label(L10n.Contacts.Contacts.Row.location, systemImage: "location.fill")
                            .labelStyle(.iconOnly)
                            .font(.caption)
                            .foregroundStyle(.green)

                        if let distance = distanceToContact {
                            Text(distance)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var avatarView: some View {
        switch contact.type {
        case .chat:
            ContactAvatar(contact: contact, size: 44)
        case .repeater:
            NodeAvatar(publicKey: contact.publicKey, role: .repeater, size: 44)
        case .room:
            NodeAvatar(publicKey: contact.publicKey, role: .roomServer, size: 44)
        }
    }

    private var idPrefixHex: String {
        let hashSize = appState.connectedDevice?.hashSize ?? 1
        return contact.publicKey.prefix(hashSize).uppercaseHexString()
    }

    private var contactTypeLabel: String {
        contact.type.localizedName
    }

    private var routeLabel: String {
        if !contact.isFloodRouted, contact.pathHopCount == 0 {
            return L10n.Contacts.Contacts.Route.direct
        } else if let hops = contact.displayedHopCount(inboundHopCount: inboundHopCount) {
            return L10n.Contacts.Contacts.Route.hops(hops)
        } else {
            return L10n.Contacts.Contacts.Route.flood
        }
    }

    private var distanceToContact: String? {
        guard let userLocation, contact.hasLocation else { return nil }

        let contactLocation = CLLocation(
            latitude: contact.latitude,
            longitude: contact.longitude
        )
        let meters = userLocation.distance(from: contactLocation)
        let measurement = Measurement(value: meters, unit: UnitLength.meters)

        let formattedDistance = measurement.formatted(.measurement(
            width: .abbreviated,
            usage: .road
        ))
        return L10n.Contacts.Contacts.Row.away(formattedDistance)
    }
}
