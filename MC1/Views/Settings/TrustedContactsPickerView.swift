import SwiftUI
import MC1Services

/// Picker for selecting trusted contacts for telemetry
struct TrustedContactsPickerView: View {
    @Environment(\.appState) private var appState
    @State private var contacts: [ContactDTO] = []
    @State private var searchText = ""
    @State private var showFavoritesOnly = false
    @State private var pendingTrustedIDs: Set<UUID> = []
    @State private var initialTrustedIDs: Set<UUID> = []
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var successTrigger = 0

    private var settingsModified: Bool {
        pendingTrustedIDs != initialTrustedIDs
    }

    private var canApply: Bool {
        appState.connectionState == .ready && settingsModified && !isApplying
    }

    private var filteredContacts: [ContactDTO] {
        var result = contacts
        if showFavoritesOnly {
            result = result.filter(\.isFavorite)
        }
        if !searchText.isEmpty {
            result = result.filter { $0.displayName.localizedStandardContains(searchText) }
        }
        return result
    }

    var body: some View {
        List {
            Section {
                Toggle(L10n.Settings.TrustedContacts.favoritesOnly, isOn: $showFavoritesOnly)
            }

            Section {
                if filteredContacts.isEmpty {
                    if !searchText.isEmpty || showFavoritesOnly {
                        ContentUnavailableView(
                            L10n.Settings.TrustedContacts.noResults,
                            systemImage: "magnifyingglass",
                            description: Text(L10n.Settings.TrustedContacts.noResultsDescription(searchText))
                        )
                    } else {
                        ContentUnavailableView(
                            L10n.Settings.TrustedContacts.noContacts,
                            systemImage: "person.2.slash",
                            description: Text(L10n.Settings.TrustedContacts.noContactsDescription)
                        )
                    }
                } else {
                    ForEach(filteredContacts) { contact in
                        Button {
                            if pendingTrustedIDs.contains(contact.id) {
                                pendingTrustedIDs.remove(contact.id)
                            } else {
                                pendingTrustedIDs.insert(contact.id)
                            }
                        } label: {
                            HStack {
                                Text(contact.displayName)
                                Spacer()
                                if pendingTrustedIDs.contains(contact.id) {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }
            }
        }
        .disabled(isApplying)
        .searchable(text: $searchText, prompt: L10n.Settings.TrustedContacts.searchPrompt)
        .navigationTitle(L10n.Settings.TrustedContacts.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { applyChanges() } label: {
                    Text(L10n.Settings.TrustedContacts.apply)
                }
                .disabled(!canApply)
            }
        }
        .sensoryFeedback(.success, trigger: successTrigger)
        .errorAlert($errorMessage)
        .task {
            await loadContacts()
        }
    }

    private func loadContacts() async {
        guard let radioID = appState.connectedDevice?.radioID,
              let contactService = appState.services?.contactService else { return }
        do {
            contacts = try await contactService.getContacts(radioID: radioID)
                .filter { $0.type == .chat }
            let trusted = Set(
                contacts
                    .filter { ContactService.hasTelemetryPermissions(flags: $0.flags) }
                    .map(\.id)
            )
            initialTrustedIDs = trusted
            pendingTrustedIDs = trusted
        } catch {
            // User can navigate back and retry
        }
    }

    private func applyChanges() {
        guard !isApplying else { return }
        guard let contactService = appState.services?.contactService else { return }

        isApplying = true
        Task {
            do {
                let toGrant = pendingTrustedIDs.subtracting(initialTrustedIDs)
                let toRevoke = initialTrustedIDs.subtracting(pendingTrustedIDs)

                for id in toGrant {
                    try await contactService.setTelemetryPermissions(id, granted: true)
                }
                for id in toRevoke {
                    try await contactService.setTelemetryPermissions(id, granted: false)
                }

                initialTrustedIDs = pendingTrustedIDs
                successTrigger += 1
            } catch {
                errorMessage = error.localizedDescription
            }
            isApplying = false
        }
    }
}
