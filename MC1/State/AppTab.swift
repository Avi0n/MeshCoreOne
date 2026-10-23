/// Tab indices for the main `TabView`. Raw values stay `Int` so `MainTabView`
/// and `NavigationCoordinator.selectedTab` select the same tab.
enum AppTab: Int {
  case chats
  case nodes
  case map
  case tools
  case settings
}
