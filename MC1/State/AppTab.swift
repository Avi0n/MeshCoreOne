/// Tab indices for the main `TabView`. The raw value is the integer each
/// `Tab(value:)` uses for selection, so `ContentView` and `NavigationCoordinator`
/// agree on which index selects a given tab. Backed by `Int` because
/// `NavigationCoordinator.selectedTab` remains `Int` (see the deferred
/// Int-to-AppTab migration).
enum AppTab: Int {
  case chats
  case nodes
  case map
  case tools
  case settings
}
