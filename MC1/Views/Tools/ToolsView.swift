import SwiftUI

/// Tools tab: a stack bound to `NavigationCoordinator.selectedTool` so the
/// selected workspace survives tab switches. Back writes an empty path.
struct ToolsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  private var toolPath: Binding<[ToolSelection]> {
    Binding(
      get: { appState.navigation.selectedTool.map { [$0] } ?? [] },
      set: { appState.navigation.selectedTool = $0.last }
    )
  }

  var body: some View {
    NavigationStack(path: toolPath) {
      List {
        ForEach(ToolSelection.allCases, id: \.self) { tool in
          NavigationLink(value: tool) {
            Label(tool.title, systemImage: tool.systemImage)
          }
        }
        .themedRowBackground(theme)
      }
      .navigationDestination(for: ToolSelection.self) { tool in
        ToolDestinationView(tool: tool)
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Tools.Tools.title)
      .toolbar {
        bleStatusToolbarItem()
      }
    }
  }
}

#Preview {
  ToolsView()
    .environment(\.appState, AppState())
}
