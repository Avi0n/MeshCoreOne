import CoreLocation
import MC1Services
import SwiftUI

/// Reusable row showing a contact matched by a channel sender's name.
struct ContactMatchRow: View {
    enum SelectionStyle {
        case toggle(isSelected: Bool)
        case tap
    }

    let contact: ContactDTO
    let style: SelectionStyle
    let userLocation: CLLocation?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                leadingSelectionGlyph

                ContactAvatar(contact: contact, size: 40)

                VStack(alignment: .leading, spacing: 4) {
                    Text(contact.displayName)
                        .font(.body)
                        .bold()
                        .foregroundStyle(.primary)

                    RelativeTimestampText(timestamp: contact.lastAdvertTimestamp)

                    HStack(spacing: 4) {
                        Text(contactTypeLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        if contact.hasLocation {
                            Label(L10n.Contacts.Contacts.Row.location, systemImage: "location.fill")
                                .labelStyle(.iconOnly)
                                .font(.caption)
                                .foregroundStyle(.green)

                            if let distance = distanceText {
                                Text(distance)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    Text(L10n.Chats.Chats.ContactMatch.key(contact.publicKey.uppercaseHexString(separator: " ")))
                        .font(.caption)
                        .monospaced()
                        .foregroundStyle(.tertiary)
                        .lineLimit(2)
                }

                trailingChevron
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(contact.displayName)
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(accessibilityTraits)
    }

    @ViewBuilder
    private var leadingSelectionGlyph: some View {
        if case .toggle(let isSelected) = style {
            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(isSelected ? .blue : .secondary)
                .font(.title3)
        }
    }

    @ViewBuilder
    private var trailingChevron: some View {
        if case .tap = style {
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .foregroundStyle(.tertiary)
                .font(.caption)
        }
    }

    private var accessibilityValue: String {
        switch style {
        case .toggle(let isSelected):
            isSelected
                ? L10n.Chats.Chats.ContactMatch.Accessibility.selected
                : L10n.Chats.Chats.ContactMatch.Accessibility.notSelected
        case .tap:
            ""
        }
    }

    private var accessibilityTraits: AccessibilityTraits {
        switch style {
        case .toggle: .isToggle
        case .tap: .isButton
        }
    }

    private var contactTypeLabel: String {
        contact.type.localizedName
    }

    private var distanceText: String? {
        guard let userLocation, contact.hasLocation else { return nil }

        let contactLocation = CLLocation(
            latitude: contact.latitude,
            longitude: contact.longitude
        )
        let meters = userLocation.distance(from: contactLocation)
        let measurement = Measurement(value: meters, unit: UnitLength.meters)
        let formatted = measurement.formatted(.measurement(width: .abbreviated, usage: .road))
        return L10n.Contacts.Contacts.Row.away(formatted)
    }
}
