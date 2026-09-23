# iPad Layout Guide

This guide describes how MeshCore One adapts its UI for iPad.

## Overview

`ContentView` (`MC1/ContentView.swift`) mounts `OnboardingView` until onboarding completes, then always mounts `MainTabView` (`MC1/Views/MainTabView.swift`). The root does not swap on size class or device idiom.

The five sections are identified by `AppTab` (`MC1/State/AppTab.swift`), whose `Int` raw value is the selection index shared with `NavigationCoordinator.selectedTab`:

- Chats (0)
- Nodes (1)
- Map (2)
- Tools (3)
- Settings (4)

`MainTabView` presents these as a native `TabView`. Chats, Nodes, and Settings each own a `NavigationSplitView` that tiles list and detail when width allows and collapses on compact width. Tools is a stack bound to `NavigationCoordinator.selectedTool`. Line of Sight is one workspace that shows analysis beside the map when both surfaces fit, and otherwise keeps the map with an analysis sheet. Map keeps its existing `NavigationStack`.

List actions (compose, node sort/add, settings device menu, radio status) stay on the list column. Conversation info and other detail actions stay on the detail. Native split provides the sidebar toggle and Back. Radio status uses `bleStatusToolbarItem()` and keeps a high overflow priority on iOS 27. The connection status pill sits below the top tab bar so it cannot cover tab hits.

## Section hosts

- Chats: `MC1/Views/Chats/ChatsView.swift`
- Nodes: `MC1/Views/Contacts/ContactsListView.swift`
- Map: `MC1/Views/Map/MapView.swift`
- Tools: `MC1/Views/Tools/ToolsView.swift`
- Settings: `MC1/Views/Settings/SettingsView.swift`

Per-section selection that must survive a tab switch lives in `NavigationCoordinator`. List models live in the section hosts.

## Testing

Run the app test suite through the `make` target, which pins the standard simulator destination (iPhone 17e / iOS 27.0):

```bash
make test-app
```

Root tab and split navigation behavior is covered by `MC1Tests/Views/AdaptiveNavigationTests.swift`. Verify tab placement and split geometry on a running iPad simulator as well.

## Common Pitfalls

- Ensure app-wide state is accessed via `@Environment(\.appState)`.
- Do not restore a size-class swap at `ContentView`. Compact and regular share `MainTabView`.
- Do not clear `selectedTool` merely because the Tools tab is inactive.

## Further Reading

- [Development Guide](../Development.md)
- [Architecture Overview](../Architecture.md)
- [User Guide](../User_Guide.md)
