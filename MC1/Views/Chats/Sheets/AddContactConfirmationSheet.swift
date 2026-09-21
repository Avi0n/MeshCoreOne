import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "AddContactConfirmationSheet")

/// Confirmation sheet shown when tapping a meshcore://contact/add link in a chat message
@MainActor
struct AddContactConfirmationSheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState

  let contactResult: MeshCoreURLParser.ContactResult
  let onComplete: (ContactDTO?) -> Void

  @State private var existingContact: ContactDTO?
  @State private var didResolveExisting = false
  @State private var isAdding = false
  @State private var errorMessage: String?
  @State private var successTrigger = 0

  private var isMissingDevice: Bool {
    appState.connectedDevice == nil
  }

  var body: some View {
    NavigationStack {
      Group {
        // Wait for the local lookup so a saved contact can View while disconnected.
        if isMissingDevice, didResolveExisting, existingContact == nil {
          ContactMissingDeviceContent(
            contactName: contactResult.name,
            onDismiss: {
              onComplete(nil)
              dismiss()
            }
          )
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .background(Color(.systemGroupedBackground))
        } else {
          ContactAddConfirmationContent(
            contactResult: contactResult,
            existingContact: existingContact,
            errorMessage: errorMessage,
            isAdding: isAdding,
            onAdd: {
              if let existingContact {
                onComplete(existingContact)
                dismiss()
              } else {
                Task { await addContact() }
              }
            },
            onScanAgain: nil
          )
        }
      }
      .navigationTitle(existingContact?.displayName ?? L10n.Contacts.Contacts.Add.nodeTitle)
      .task {
        existingContact = await fetchExistingContact()
        didResolveExisting = true
      }
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

  private func fetchExistingContact() async -> ContactDTO? {
    guard let radioID = appState.currentRadioID else { return nil }
    let store = appState.services?.dataStore ?? appState.offlineDataStore
    return try? await store?.fetchContact(
      radioID: radioID,
      publicKey: contactResult.publicKey
    )
  }

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
