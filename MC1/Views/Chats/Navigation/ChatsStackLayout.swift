import MC1Services
import SwiftUI

struct ChatsStackLayout<RootContent: View>: View {
  @Environment(\.appState) private var appState

  let viewModel: ChatViewModel
  @Binding var navigationPath: NavigationPath
  @Binding var activeRoute: ChatRoute?

  @ViewBuilder let rootContent: RootContent

  var body: some View {
    NavigationStack(path: $navigationPath) {
      rootContent
        .navigationDestination(for: ChatRoute.self) { route in
          Group {
            switch route {
            case let .direct(contact):
              ChatConversationView(conversationType: .dm(contact), parentViewModel: viewModel)
                .id(contact.id)

            case let .channel(channel):
              ChatConversationView(conversationType: .channel(channel), parentViewModel: viewModel)
                .id(channel.id)

            case let .room(session):
              RoomConversationView(session: session)
                .id(session.id)
            }
          }
          .onAppear {
            activeRoute = route
            appState.navigation.tabBarVisibility = .hidden
          }
        }
        .onChange(of: navigationPath) { _, newPath in
          if newPath.isEmpty {
            activeRoute = nil
            appState.navigation.tabBarVisibility = .visible
            viewModel.requestConversationReload()
          }
        }
        .toolbarVisibility(appState.navigation.tabBarVisibility, for: .tabBar)
    }
  }
}
