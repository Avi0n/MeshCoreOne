import SwiftUI
import MC1Services

/// SwiftUI content displayed in a popover callout when a map pin is tapped
struct ContactCalloutContent: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    let contact: ContactDTO
    let onDetail: () -> Void
    let onMessage: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            (Text(idPrefixHex)
                .monospaced()
                .foregroundStyle(.secondary)
                + Text(" \(contact.displayName)"))
                .font(.headline)
                .accessibilityLabel(contact.displayName)

            HStack(spacing: 6) {
                Image(systemName: contact.type.iconSystemName)
                    .foregroundStyle(contact.type.displayColor)
                Text(typeDisplayName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)

            Divider()

            // Action buttons - same width
            VStack(spacing: 6) {
                Button(L10n.Map.Map.Callout.details, systemImage: "info.circle", action: onDetail)
                    .buttonStyle(.bordered)
                    .accessibilityHint(contact.displayName)

                if contact.type == .chat {
                    Button {
                        dismiss()
                        onMessage()
                    } label: {
                        Label(L10n.Map.Map.Callout.message, systemImage: "message.fill")
                    }
                    .buttonStyle(.bordered)
                    .accessibilityHint(contact.displayName)
                }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(12)
        .frame(minWidth: 160)
    }

    // MARK: - Computed Properties

    private var idPrefixHex: String {
        let hashSize = appState.connectedDevice?.hashSize ?? 1
        return contact.publicKey.prefix(hashSize).hexString()
    }

    private var typeDisplayName: String {
        switch contact.type {
        case .chat:
            L10n.Map.Map.Callout.NodeKind.contact
        case .repeater:
            L10n.Map.Map.Callout.NodeKind.repeater
        case .room:
            L10n.Map.Map.Callout.NodeKind.room
        }
    }
}

// MARK: - Preview

#Preview {
    ContactCalloutContent(
        contact: ContactDTO(
            from: Contact(
                radioID: UUID(),
                publicKey: Data(repeating: 0x01, count: 32),
                name: "Alice",
                typeRawValue: 0,
                latitude: 37.7749,
                longitude: -122.4194,
                isFavorite: true
            )
        ),
        onDetail: {},
        onMessage: {}
    )
    .background(Color(.systemBackground))
}
