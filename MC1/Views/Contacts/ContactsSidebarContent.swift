import MC1Services
import SwiftUI

struct ContactsSidebarContent: View {
  @Environment(\.appState) private var appState
  @Bindable var state: ContactsSidebarState
  @FocusState private var isSearchFocused: Bool
  @State private var isMounted = false

  let filteredContacts: [ContactDTO]
  let timestampsByID: [UUID: UInt32]
  let searchPrompt: String
  let selectedContact: ContactDTO?
  @Binding var sortOrder: NodeSortOrder
  let blockedContactsRoute: NodesRoute?
  let rendererRevision: Int
  let onSelect: (ContactDTO) -> Void

  var body: some View {
    ContactsListContent(
      selectedContact: selectedContact,
      onSelect: onSelect,
      selectedSegment: $state.selectedSegment,
      isSearching: !state.searchText.isEmpty,
      searchText: state.searchText,
      filteredContacts: filteredContacts,
      timestampsByID: timestampsByID,
      hasLoadedOnce: state.viewModel.hasLoadedOnce,
      viewModel: state.viewModel,
      scrollPositionID: Binding(
        get: { state.scrollPositionID },
        set: { state.updateScrollPosition($0, rendererRevision: rendererRevision) }
      ),
      onRefresh: {
        await state.synchronize(appState: appState)
        state.scrollPositionID = nil
      }
    )
    .onScrollGeometryChange(for: Bool.self) { geometry in
      geometry.contentOffset.y <= -geometry.contentInsets.top
    } action: { wasAtTop, isAtTop in
      guard !wasAtTop, isAtTop, isMounted else { return }
      state.clearScrollPosition(rendererRevision: rendererRevision)
    }
    .navigationTitle(L10n.Contacts.Contacts.List.title)
    .navigationDestination(for: ContactRoute.self) { route in
      switch route {
      case .blockedContacts:
        BlockedContactsView()
      }
    }
    .searchable(
      text: Binding(
        get: { state.searchText },
        set: { state.updateSearchText($0, rendererRevision: rendererRevision) }
      ),
      placement: .navigationBarDrawer(displayMode: .always),
      prompt: searchPrompt
    )
    .searchFocused($isSearchFocused)
    .task {
      guard state.rendererRevision == rendererRevision else { return }
      isMounted = true
      isSearchFocused = state.isSearchFocused
    }
    .onDisappear { isMounted = false }
    .onChange(of: isSearchFocused) { _, focused in
      guard isMounted else { return }
      state.updateSearchFocus(focused, rendererRevision: rendererRevision)
    }
    .toolbar {
      bleStatusToolbarItem()

      ToolbarItem(placement: .automatic) {
        Menu {
          ForEach(NodeSortOrder.allCases, id: \.self) { order in
            let title = order == .lastHeard ? L10n.Contacts.Contacts.Detail.lastAdvert : order.localizedTitle
            Button {
              sortOrder = order
            } label: {
              if sortOrder == order {
                Label(title, systemImage: "checkmark")
              } else {
                Text(title)
              }
            }
          }
        } label: {
          Label(L10n.Contacts.Contacts.List.sort, systemImage: "arrow.up.arrow.down")
        }
      }

      ToolbarItem(placement: .automatic) {
        Menu {
          blockedContactsLink

          Divider()

          Button {
            state.showShareMyContact = true
          } label: {
            Label(L10n.Contacts.Contacts.List.shareMyContact, systemImage: "square.and.arrow.up")
          }

          Button {
            state.showAddContact = true
          } label: {
            Label(L10n.Contacts.Contacts.List.addContact, systemImage: "plus")
          }

          Divider()

          Button {
            appState.navigation.navigateToDiscovery()
          } label: {
            Label(L10n.Contacts.Contacts.List.discover, systemImage: "antenna.radiowaves.left.and.right")
          }

          Divider()

          Button {
            Task { await state.synchronize(appState: appState) }
          } label: {
            Label(L10n.Contacts.Contacts.List.syncNodes, systemImage: "arrow.triangle.2.circlepath")
          }
          .disabled(state.viewModel.isSyncing)
        } label: {
          Label(L10n.Contacts.Contacts.List.options, systemImage: "ellipsis.circle")
        }
      }
    }
  }

  @ViewBuilder
  private var blockedContactsLink: some View {
    if let blockedContactsRoute {
      NavigationLink(value: blockedContactsRoute) { blockedContactsLabel }
    } else {
      NavigationLink(value: ContactRoute.blockedContacts) { blockedContactsLabel }
    }
  }

  private var blockedContactsLabel: some View {
    Label(L10n.Contacts.Contacts.List.blockedContacts, systemImage: "hand.raised.fill")
  }
}
