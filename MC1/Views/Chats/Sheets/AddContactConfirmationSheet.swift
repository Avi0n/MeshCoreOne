import SwiftUI
import MC1Services
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "AddContactConfirmationSheet")

/// Confirmation sheet shown when tapping a meshcore://contact/add link in a chat message
@MainActor
struct AddContactConfirmationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState

    let contactResult: MeshCoreURLParser.ContactResult
    let onComplete: (ContactDTO?) -> Void

    @State private var isAdding = false
    @State private var errorMessage: String?
    @State private var successTrigger = 0

    private var isMissingDevice: Bool {
        appState.connectedDevice == nil
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isMissingDevice {
                    ContactMissingDeviceContent(
                        contactName: contactResult.name,
                        onDismiss: {
                            onComplete(nil)
                            dismiss()
                        }
                    )
                } else {
                    ContactAddConfirmationContent(
                        contactResult: contactResult,
                        errorMessage: errorMessage,
                        isAdding: isAdding,
                        onAdd: { Task { await addContact() } }
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground))
            .navigationTitle(L10n.Contacts.Contacts.Add.nodeTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Contacts.Contacts.Common.cancel) {
                        onComplete(nil)
                        dismiss()
                    }
                }
            }
            .sensoryFeedback(.success, trigger: successTrigger)
            .sensoryFeedback(.error, trigger: errorMessage)
        }
    }

    // MARK: - Private Methods

    private func addContact() async {
        guard let radioID = appState.connectedDevice?.radioID else { return }

        guard let contactService = appState.services?.contactService,
              let dataStore = appState.services?.dataStore else {
            errorMessage = L10n.Contacts.Contacts.Add.Error.notConnected
            return
        }

        isAdding = true
        errorMessage = nil

        do {
            let contact = ContactFrame(
                publicKey: contactResult.publicKey,
                type: contactResult.contactType,
                flags: 0,
                outPathLength: PacketBuilder.floodPathSentinel,
                outPath: Data(),
                name: contactResult.name,
                lastAdvertTimestamp: 0,
                latitude: 0,
                longitude: 0,
                lastModified: UInt32(Date().timeIntervalSince1970)
            )

            try await contactService.addOrUpdateContact(
                radioID: radioID,
                contact: contact
            )

            if let addedContact = try await dataStore.fetchContact(
                radioID: radioID,
                publicKey: contactResult.publicKey
            ) {
                successTrigger += 1
                onComplete(addedContact)
                dismiss()
            } else {
                errorMessage = L10n.Contacts.Contacts.Common.errorOccurred
            }
        } catch {
            logger.error("Failed to add contact from link: \(error)")
            errorMessage = error.userFacingMessage
        }

        isAdding = false
    }
}

// MARK: - Extracted Views

private struct ContactMissingDeviceContent: View {
    let contactName: String
    let onDismiss: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(L10n.Localizable.Common.Status.disconnected, systemImage: "antenna.radiowaves.left.and.right.slash")
        } description: {
            Text(L10n.Contacts.Contacts.Add.Error.notConnected)
        } actions: {
            Button(L10n.Contacts.Contacts.Common.ok, action: onDismiss)
                .liquidGlassProminentButtonStyle()
        }
    }
}

private struct ContactAddConfirmationContent: View {
    let contactResult: MeshCoreURLParser.ContactResult
    let errorMessage: String?
    let isAdding: Bool
    let onAdd: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(contactResult.contactType.displayColor)
                        .frame(width: 80, height: 80)

                    Image(systemName: contactResult.contactType.iconSystemName)
                        .font(.system(size: 36, weight: .bold))
                        .foregroundStyle(.white)
                }

                Text(contactResult.name)
                    .font(.title)
                    .bold()

                Text(contactResult.contactType.localizedName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Text(contactResult.publicKey.uppercaseHexString())
                    .font(.caption)
                    .monospaced()
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            }

            Button(action: onAdd) {
                if isAdding {
                    ProgressView()
                } else {
                    Text(L10n.Contacts.Contacts.Add.add)
                }
            }
            .liquidGlassProminentButtonStyle()
            .disabled(isAdding)
            .padding(.horizontal, 48)
            .padding(.bottom, 32)
        }
    }

}

#Preview {
    let result = MeshCoreURLParser.ContactResult(
        name: "TestRepeater",
        publicKey: Data(repeating: 0xAA, count: 32),
        contactType: .repeater
    )
    AddContactConfirmationSheet(contactResult: result) { _ in }
        .environment(\.appState, AppState())
        .presentationDetents([.medium, .large])
}
