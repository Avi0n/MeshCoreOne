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
            set: { appState.navigation.selectedTool = $0 }
        )
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
            // Tools renders only through the iPad split here, so the radio shows whenever the sidebar
            // is collapsed (the sidebar otherwise owns it).
            bleStatusToolbarItem(isVisible: appState.navigation.isSidebarCollapsed)
        }
    }

    private var lineOfSightPanel: some View {
        LineOfSightView(viewModel: lineOfSightViewModel, layoutMode: .panel)
            .navigationTitle(L10n.Tools.Tools.lineOfSight)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        appState.navigation.selectedTool = nil
                    } label: {
                        Label(L10n.Tools.Tools.title, systemImage: "chevron.backward")
                    }
                }
            }
    }
}
