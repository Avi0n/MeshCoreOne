import SwiftUI

/// The compact (iPhone / compact-width) Tools tab: a stack that pushes each tool. The iPad
/// regular-width layout routes Tools through `MainSidebarView`'s split (`ToolsContentColumn` +
/// `ToolsDetailColumn`) instead, so this view is only reached in compact width.
struct ToolsView: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        NavigationStack {
            List {
                ForEach(ToolSelection.allCases, id: \.self) { tool in
                    NavigationLink {
                        ToolDestinationView(tool: tool) { LineOfSightView() }
                    } label: {
                        Label(tool.title, systemImage: tool.systemImage)
                    }
                }
                .themedRowBackground(theme)
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
