import SwiftUI

/// The iPad sidebar's Tools detail column: renders the view for the tool selected in
/// `NavigationCoordinator.selectedTool`. Line of Sight shows its map here while
/// `ToolsContentColumn` shows the analysis panel, both driven by the shared `lineOfSightViewModel`.
/// The map self-manages its safe area, so no blanket `ignoresSafeArea` is applied here (that would
/// push the other tools' titles under the status bar).
struct ToolsDetailColumn: View {
    @Environment(\.appState) private var appState

    let lineOfSightViewModel: LineOfSightViewModel

    private var selectedTool: ToolSelection? {
        appState.navigation.selectedTool
    }

    var body: some View {
        NavigationStack {
            detail
                .navigationTitle(navigationTitle)
                .navigationBarTitleDisplayMode(.inline)
        }
        .id(selectedTool)
        .liquidGlassToolbarBackground()
    }

    @ViewBuilder
    private var detail: some View {
        if let selectedTool {
            ToolDestinationView(tool: selectedTool) {
                LineOfSightView(viewModel: lineOfSightViewModel, layoutMode: .map)
            }
        } else {
            ContentUnavailableView(L10n.Tools.Tools.selectTool, systemImage: "wrench.and.screwdriver")
        }
    }

    /// Line of Sight owns its own inline chrome over the map, so it renders titleless.
    private var navigationTitle: String {
        guard let selectedTool, selectedTool != .lineOfSight else { return "" }
        return selectedTool.title
    }
}
