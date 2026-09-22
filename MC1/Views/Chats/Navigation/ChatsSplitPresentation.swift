import SwiftUI

/// Compact Back, expand-to-regular tiling, and tab-bar rules for a section
/// `NavigationSplitView`. Chats, Nodes, and Settings use the same recipe.
enum ChatsSplitPresentation {
  enum PreferredColumnAction: Equatable {
    case none
    case clearRootSelection
  }

  /// Column changes when the host size class itself changes. Compact `.detail`
  /// left in place at regular width overlays the list on the selected detail.
  struct SizeClassPresentation: Equatable {
    var columnVisibility: NavigationSplitViewVisibility?
    var preferredColumn: NavigationSplitViewColumn?
  }

  static func tabBarVisibility(
    sizeClass: UserInterfaceSizeClass?,
    preferredColumn: NavigationSplitViewColumn,
    hasSelection: Bool
  ) -> Visibility {
    let collapsedDetail = sizeClass == .compact
      && preferredColumn == .detail
      && hasSelection
    return collapsedDetail ? .hidden : .automatic
  }

  static func preferredColumnAction(
    preferredColumn: NavigationSplitViewColumn,
    sizeClass: UserInterfaceSizeClass?,
    nestedPathIsEmpty: Bool
  ) -> PreferredColumnAction {
    if preferredColumn == .sidebar, sizeClass == .compact, nestedPathIsEmpty {
      return .clearRootSelection
    }
    return .none
  }

  static func presentationForSizeClassChange(
    from old: UserInterfaceSizeClass?,
    to new: UserInterfaceSizeClass?,
    hasSelection: Bool
  ) -> SizeClassPresentation {
    if new == .regular, old != .regular {
      return SizeClassPresentation(columnVisibility: .all, preferredColumn: .sidebar)
    }
    if new == .compact, old != .compact, hasSelection {
      return SizeClassPresentation(columnVisibility: nil, preferredColumn: .detail)
    }
    return SizeClassPresentation(columnVisibility: nil, preferredColumn: nil)
  }
}

extension View {
  /// Owns column visibility, the compact preferred column, and the Back recipe.
  /// Each section still resets its nested path and decides what a cleared root means.
  func sectionSplitState(
    columnVisibility: Binding<NavigationSplitViewVisibility>,
    preferredCompactColumn: Binding<NavigationSplitViewColumn>,
    nestedPathIsEmpty: Bool,
    hasSelection: Bool,
    onClearRootSelection: @escaping () -> Void
  ) -> some View {
    modifier(
      SectionSplitStateModifier(
        columnVisibility: columnVisibility,
        preferredCompactColumn: preferredCompactColumn,
        nestedPathIsEmpty: nestedPathIsEmpty,
        hasSelection: hasSelection,
        onClearRootSelection: onClearRootSelection
      )
    )
  }

  /// Compact collapsed detail hides the tab bar. iOS 26+ ignores the top container inset
  /// so the split extends under the iPad tab bar; earlier OS keeps it so the timeline
  /// stays below the header. Ignoring safe area on `NavigationSplitView` crashes layout.
  @ViewBuilder
  func sectionSplitChrome(tabBarVisibility: Visibility) -> some View {
    if #available(iOS 26, *) {
      toolbarVisibility(tabBarVisibility, for: .tabBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.container, edges: .top)
    } else {
      toolbarVisibility(tabBarVisibility, for: .tabBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
  }

  /// Flows up to this column's navigation bar so default-theme splits can show
  /// through the status bar the way `NavigationStack` tabs already do.
  func sectionSplitColumnChrome() -> some View {
    modifier(SectionSplitColumnChromeModifier())
  }
}

private struct SectionSplitStateModifier: ViewModifier {
  @Environment(\.horizontalSizeClass) private var sizeClass

  @Binding var columnVisibility: NavigationSplitViewVisibility
  @Binding var preferredCompactColumn: NavigationSplitViewColumn
  let nestedPathIsEmpty: Bool
  let hasSelection: Bool
  let onClearRootSelection: () -> Void

  func body(content: Content) -> some View {
    content
      .onChange(of: sizeClass) { old, new in
        applySizeClassChange(from: old, to: new)
      }
      .onChange(of: preferredCompactColumn) { _, _ in
        applyPreferredColumnRecipe()
      }
      .onChange(of: nestedPathIsEmpty) { _, _ in
        applyPreferredColumnRecipe()
      }
      .task {
        if hasSelection, sizeClass == .compact {
          preferredCompactColumn = .detail
        }
      }
  }

  private func applySizeClassChange(
    from old: UserInterfaceSizeClass?,
    to new: UserInterfaceSizeClass?
  ) {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: old,
      to: new,
      hasSelection: hasSelection
    )
    if let visibility = presentation.columnVisibility {
      columnVisibility = visibility
    }
    if let column = presentation.preferredColumn {
      preferredCompactColumn = column
    }
  }

  private func applyPreferredColumnRecipe() {
    switch ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: preferredCompactColumn,
      sizeClass: sizeClass,
      nestedPathIsEmpty: nestedPathIsEmpty
    ) {
    case .clearRootSelection:
      onClearRootSelection()
    case .none:
      break
    }
  }
}

private struct SectionSplitColumnChromeModifier: ViewModifier {
  @Environment(\.appTheme) private var theme

  func body(content: Content) -> some View {
    content.toolbarBackgroundVisibility(navigationBarVisibility, for: .navigationBar)
  }

  private var navigationBarVisibility: Visibility {
    if theme.surfaces?.canvas != nil { return .visible }
    return .automatic
  }
}

/// Drops a copied room-auth sheet when the radio that produced it is gone.
enum ChatsRadioScopedSheets {
  static func shouldKeepRoomAuth(
    sessionRadioID: UUID,
    currentRadioID: UUID?,
    hasConnectedDevice: Bool
  ) -> Bool {
    hasConnectedDevice && currentRadioID == sessionRadioID
  }
}
