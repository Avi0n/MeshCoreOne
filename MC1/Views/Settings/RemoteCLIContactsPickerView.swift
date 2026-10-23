import MC1Services
import SwiftUI

/// Picker for selecting contacts allowed to send Remote CLI over the mesh.
struct RemoteCLIContactsPickerView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var contacts: [ContactDTO] = []
  @State private var searchText = ""
  @State private var showFavoritesOnly = false
  @State private var pendingGrantedIDs: Set<UUID> = []
  @State private var initialGrantedIDs: Set<UUID> = []
  @State private var isApplying = false
  @State private var errorMessage: String?
  @State private var successTrigger = 0

  private var settingsModified: Bool {
    pendingGrantedIDs != initialGrantedIDs
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
        Toggle(L10n.Settings.RemoteCLIContacts.favoritesOnly, isOn: $showFavoritesOnly)
      }
      .themedRowBackground(theme)

      Section {
        if filteredContacts.isEmpty {
          switch Selection.filterEmptyState(searchText: searchText, showFavoritesOnly: showFavoritesOnly) {
          case .noSearchResults:
            ContentUnavailableView(
              L10n.Settings.RemoteCLIContacts.noResults,
              systemImage: "magnifyingglass",
              description: Text(L10n.Settings.RemoteCLIContacts.noResultsDescription(searchText))
            )
          case .noFavorites:
            ContentUnavailableView(
              L10n.Settings.RemoteCLIContacts.noFavorites,
              systemImage: "star.slash",
              description: Text(L10n.Settings.RemoteCLIContacts.noFavoritesDescription)
            )
          case .noContacts:
            ContentUnavailableView(
              L10n.Settings.RemoteCLIContacts.noContacts,
              systemImage: "person.2.slash",
              description: Text(L10n.Settings.RemoteCLIContacts.noContactsDescription)
            )
          }
        } else {
          ForEach(filteredContacts) { contact in
            let isGranted = pendingGrantedIDs.contains(contact.id)
            Button {
              if isGranted {
                pendingGrantedIDs.remove(contact.id)
              } else {
                pendingGrantedIDs.insert(contact.id)
              }
            } label: {
              HStack {
                Text(contact.displayName)
                Spacer()
                if isGranted {
                  Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                }
              }
            }
            .foregroundStyle(.primary)
            .accessibilityLabel(contact.displayName)
            .accessibilityAddTraits(isGranted ? [.isSelected] : [])
          }
        }
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .disabled(isApplying)
    .searchable(text: $searchText, prompt: L10n.Settings.RemoteCLIContacts.searchPrompt)
    .navigationTitle(L10n.Settings.RemoteCLIContacts.title)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) {
        Button { applyChanges() } label: {
          Text(L10n.Settings.RemoteCLIContacts.apply)
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

  enum Selection {
    enum FilterEmptyState: Equatable {
      case noContacts
      case noFavorites
      case noSearchResults
    }

    static func eligibleContacts(_ contacts: [ContactDTO]) -> [ContactDTO] {
      contacts.filter { $0.type == .chat && !$0.isBlocked }
    }

    static func grantedIDs(in contacts: [ContactDTO]) -> Set<UUID> {
      Set(
        eligibleContacts(contacts)
          .filter { ContactService.hasRemoteCLIAllowed(flags: $0.flags) }
          .map(\.id)
      )
    }

    static func idsToGrant(pending: Set<UUID>, initial: Set<UUID>, eligible: Set<UUID>) -> Set<UUID> {
      pending.intersection(eligible).subtracting(initial)
    }

    static func idsToRevoke(pending: Set<UUID>, initial: Set<UUID>) -> Set<UUID> {
      initial.subtracting(pending)
    }

    static func baselineAfterGrant(_ id: UUID, initial: Set<UUID>) -> Set<UUID> {
      var next = initial
      next.insert(id)
      return next
    }

    static func baselineAfterRevoke(_ id: UUID, initial: Set<UUID>) -> Set<UUID> {
      var next = initial
      next.remove(id)
      return next
    }

    static func filterEmptyState(searchText: String, showFavoritesOnly: Bool) -> FilterEmptyState {
      if !searchText.isEmpty { return .noSearchResults }
      if showFavoritesOnly { return .noFavorites }
      return .noContacts
    }
  }

  private func loadContacts() async {
    guard let radioID = appState.connectedDevice?.radioID,
          let contactService = appState.services?.contactService else { return }
    do {
      let loaded = try await contactService.getContacts(radioID: radioID)
      contacts = Selection.eligibleContacts(loaded)
      let granted = Selection.grantedIDs(in: contacts)
      initialGrantedIDs = granted
      pendingGrantedIDs = granted
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
        let eligibleIDs = Set(contacts.map(\.id))
        let toGrant = Selection.idsToGrant(
          pending: pendingGrantedIDs,
          initial: initialGrantedIDs,
          eligible: eligibleIDs
        )
        let toRevoke = Selection.idsToRevoke(pending: pendingGrantedIDs, initial: initialGrantedIDs)

        for id in toGrant {
          try await contactService.setRemoteCLIAllowed(id, granted: true)
          initialGrantedIDs = Selection.baselineAfterGrant(id, initial: initialGrantedIDs)
        }
        for id in toRevoke {
          try await contactService.setRemoteCLIAllowed(id, granted: false)
          initialGrantedIDs = Selection.baselineAfterRevoke(id, initial: initialGrantedIDs)
        }

        initialGrantedIDs = pendingGrantedIDs.intersection(eligibleIDs)
        pendingGrantedIDs = initialGrantedIDs
        successTrigger += 1
      } catch {
        errorMessage = error.userFacingMessage
      }
      isApplying = false
    }
  }
}
