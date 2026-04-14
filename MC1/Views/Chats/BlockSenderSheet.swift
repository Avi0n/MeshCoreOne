import CoreLocation
import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "BlockSenderSheet")

/// Confirmation sheet for blocking a channel sender name.
/// Shows name-based limitation warning and any matching contacts the user can optionally block.
struct BlockSenderSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    let senderName: String
    let radioID: UUID
    let onBlock: (_ blockedContactIDs: Set<UUID>) -> Void

    @State private var matchingContacts: [ContactDTO] = []
    @State private var selectedContactIDs: Set<UUID> = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(L10n.Chats.Chats.BlockSender.limitation)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    if !matchingContacts.isEmpty {
                        ContactMatchSection(
                            contacts: matchingContacts,
                            selectedIDs: $selectedContactIDs,
                            userLocation: appState.bestAvailableLocation
                        )
                    }
                }
                .padding()
            }
            .navigationTitle(L10n.Chats.Chats.BlockSender.title(senderName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Chats.Chats.BlockSender.cancel) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .destructiveAction) {
                    Button(L10n.Chats.Chats.BlockSender.blockAnyway, role: .destructive) {
                        onBlock(selectedContactIDs)
                        dismiss()
                    }
                }
            }
            .task {
                await loadMatchingContacts()
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(.background)
    }

    private func loadMatchingContacts() async {
        guard let store = appState.offlineDataStore else {
            logger.warning("No data store available for contact matching")
            return
        }

        do {
            let allContacts = try await store.fetchContacts(radioID: radioID)
            matchingContacts = allContacts.filter { contact in
                !contact.isBlocked
                    && contact.name.localizedCaseInsensitiveCompare(senderName) == .orderedSame
            }
            logger.info("Found \(matchingContacts.count) matching contacts for sender '\(senderName)'")
        } catch {
            logger.error("Failed to fetch contacts for matching: \(error)")
        }
    }
}

// MARK: - Contact Match Section

private struct ContactMatchSection: View {
    let contacts: [ContactDTO]
    @Binding var selectedIDs: Set<UUID>
    let userLocation: CLLocation?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.Chats.Chats.BlockSender.matchingContacts)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            ForEach(contacts) { contact in
                ContactMatchRow(
                    contact: contact,
                    isSelected: selectedIDs.contains(contact.id),
                    userLocation: userLocation,
                    onToggle: {
                        if selectedIDs.contains(contact.id) {
                            selectedIDs.remove(contact.id)
                        } else {
                            selectedIDs.insert(contact.id)
                        }
                    }
                )
            }
        }
    }
}

// MARK: - Contact Match Row

private struct ContactMatchRow: View {
    let contact: ContactDTO
    let isSelected: Bool
    let userLocation: CLLocation?
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? .blue : .secondary)
                    .font(.title3)

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

                    Text(L10n.Chats.Chats.BlockSender.key(contact.publicKey.hexString(separator: " ")))
                        .font(.caption)
                        .monospaced()
                        .foregroundStyle(.tertiary)
                        .lineLimit(2)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(contact.displayName)
        .accessibilityValue(isSelected
            ? L10n.Chats.Chats.BlockSender.Accessibility.selected
            : L10n.Chats.Chats.BlockSender.Accessibility.notSelected)
        .accessibilityAddTraits(.isToggle)
    }

    private var contactTypeLabel: String {
        switch contact.type {
        case .chat: L10n.Contacts.Contacts.NodeKind.contact
        case .repeater: L10n.Contacts.Contacts.NodeKind.repeater
        case .room: L10n.Contacts.Contacts.NodeKind.room
        }
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
