import MC1Services
import SwiftUI

/// Nodes split detail: Discovery when active, otherwise the selected contact,
/// otherwise the empty prompt. Reads `appState.navigation`.
struct ContactsDetailColumn: View {
  @Environment(\.appState) private var appState

  var body: some View {
    if appState.navigation.nodesShowingDiscovery {
      DiscoveryView()
    } else if let selectedContact = appState.navigation.selectedContact {
      ContactDetailView(contact: selectedContact)
        .id(selectedContact.id)
    } else {
      ContentUnavailableView(L10n.Contacts.Contacts.List.selectNode, systemImage: "flipphone")
    }
  }
}

#Preview {
  NavigationStack {
    ContactsDetailColumn()
  }
  .environment(\.appState, AppState())
}
