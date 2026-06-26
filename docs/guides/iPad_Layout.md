# iPad Layout Guide

This guide describes how MeshCore One adapts its UI for iPad.

## Overview

`ContentView` (see `MC1/ContentView.swift`) branches on horizontal size class:

- `MainSidebarView` when the size class is `.regular` (common on iPad)
- `MainTabView` when the size class is `.compact` (iPhone, iPad in split view)

Both paths address the same five sections, identified by the `AppTab` enum (`MC1/State/AppTab.swift`), whose `Int` raw value is the selection index shared with `NavigationCoordinator.selectedTab`:

- Chats (0)
- Nodes (1)
- Map (2)
- Tools (3)
- Settings (4)

`MainTabView` presents these as a `TabView` of five `Tab`s (the iPhone / compact layout). `MainSidebarView` presents the same sections as a single iPad `NavigationSplitView` shell, choosing the layout once at the `ContentView` level rather than maintaining a per-tab navigation model.

## Split View Pattern

`MainSidebarView` (`MC1/Views/MainSidebarView.swift`) hosts one shared `NavigationSplitView` whose shape depends on the selected `AppTab`:

- Sidebar column: `AppSidebar` (`MC1/Views/AppSidebar.swift`), the five icon-only `AppTab` rows. Selecting a row collapses the sidebar Mail-style.
- The list sections (Chats, Nodes, Settings, Tools) use a three-column split: a content column plus a detail column.
- The Map section uses a two-column split (sidebar plus detail), rendering `MapView` directly in the detail column.

The shared view models (`ChatViewModel`, `LineOfSightViewModel`) are hosted on `MainSidebarView` so each section's content and detail columns read one instance; per-section selection that must survive a section switch lives in `NavigationCoordinator`.

The content and detail columns are dedicated sidebar views (`ChatsContentColumn`, `ContactsContentColumn`, `SettingsListContent`, `ToolsContentColumn`, and the matching detail columns), not the standalone tab views. The standalone views below are the `MainTabView` (compact) section roots:

- Chats: `MC1/Views/Chats/ChatsView.swift`
- Nodes: `MC1/Views/Contacts/ContactsListView.swift`
- Tools: `MC1/Views/Tools/ToolsView.swift`
- Settings: `MC1/Views/Settings/SettingsView.swift`

The Map section uses `MapView` (its own `NavigationStack`) in both paths:

- Map: `MC1/Views/Map/MapView.swift`

## Testing

Run the app test suite through the `make` target, which pins the standard simulator destination (iPhone 17e / iOS 26):

```bash
make test-app
```

The sidebar layout logic is covered by `MC1Tests/SidebarNavigationLayoutTests.swift`, which exercises `MainSidebarView.sidebarVisibility(isWide:toolCollapsesSidebar:sectionCollapsed:)` directly (it is `nonisolated` so the test can call it). This is an xcodegen project, so the simulator-vs-stack branching is best verified on a running iPad simulator.

## Common Pitfalls

- Ensure app-wide state is accessed via `@Environment(\.appState)`.
- Per-section selection that must survive a section switch lives in `NavigationCoordinator`, not in the views; the iPad shell tears down and rebuilds a section's columns when `AppTab` changes.

## Further Reading

- [Development Guide](../Development.md)
- [Architecture Overview](../Architecture.md)
- [User Guide](../User_Guide.md)
