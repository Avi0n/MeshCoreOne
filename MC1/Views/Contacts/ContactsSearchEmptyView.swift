import SwiftUI

struct ContactsSearchEmptyView: View {
  let searchText: String

  var body: some View {
    ContentUnavailableView(
      L10n.Contacts.Contacts.List.Empty.Search.title,
      systemImage: "magnifyingglass",
      description: Text(L10n.Contacts.Contacts.List.Empty.Search.description(searchText))
    )
  }
}
