import SwiftUI

/// The iPad sidebar's Tools content column. Normally a selectable list of tools driving
/// `NavigationCoordinator.selectedTool`; when Line of Sight is selected it is replaced by the
/// analysis panel (the map renders in the detail column from the shared `lineOfSightViewModel`),
/// with a back control returning to the list. The panel carries readable RF figures, so it keeps
/// an opaque background — the floating-glass background extension is applied to the list only.
struct ToolsContentColumn: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  let lineOfSightViewModel: LineOfSightViewModel

  private var selection: Binding<ToolSelection?> {
    Binding(
      get: { appState.navigation.selectedTool },
      set: { setSelectedTool($0) }
    )
  }

  /// Selecting or leaving Line of Sight swaps this column's `NavigationStack` root, which animates
  /// the navigation bar; suppressing the animation on that transition stops the radio control from
  /// leaving a stuck ghost over the title while the leading slot reconfigures to the back button.
  /// Selections that only drive the detail column (every other tool) keep their default animation.
  private func setSelectedTool(_ tool: ToolSelection?) {
    let togglesPanel = tool == .lineOfSight || appState.navigation.selectedTool == .lineOfSight
    guard togglesPanel else {
      appState.navigation.selectedTool = tool
      return
    }
    var transaction = Transaction()
    transaction.disablesAnimations = true
    withTransaction(transaction) { appState.navigation.selectedTool = tool }
  }

  var body: some View {
    NavigationStack {
      if appState.navigation.selectedTool == .lineOfSight {
        lineOfSightPanel
      } else {
        toolList
      }
    }
  }

  private var toolList: some View {
    List(selection: selection) {
      ForEach(ToolSelection.allCases, id: \.self) { tool in
        Label(tool.title, systemImage: tool.systemImage)
          .tag(Optional(tool))
      }
    }
    .listStyle(.sidebar)
    .navigationTitle(L10n.Tools.Tools.title)
    .modifier(SidebarContentColumnBackground(theme: theme))
    .toolbar {
      bleStatusToolbarItem()
    }
  }

  private var lineOfSightPanel: some View {
    LineOfSightView(viewModel: lineOfSightViewModel, layoutMode: .panel)
      .navigationTitle(L10n.Tools.Tools.lineOfSight)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button {
            setSelectedTool(nil)
          } label: {
            Label(L10n.Tools.Tools.title, systemImage: "chevron.backward")
          }
        }
        // The back button owns the leading slot here, so the radio moves to the trailing edge.
        bleStatusToolbarItem(placement: .topBarTrailing)
      }
  }
}
