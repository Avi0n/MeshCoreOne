import SwiftUI

struct ToolsView: View {
    private static let lineOfSightSidebarWidthMin: CGFloat = 380
    private static let lineOfSightSidebarWidthIdeal: CGFloat = 440
    private static let lineOfSightSidebarWidthMax: CGFloat = 560

    private enum ToolSelection: Hashable, CaseIterable {
        case tracePath
        case lineOfSight
        case rxLog
        case noiseFloor
        case nodeDiscovery
        case cli

        var title: String {
            switch self {
            case .tracePath: L10n.Tools.Tools.tracePath
            case .lineOfSight: L10n.Tools.Tools.lineOfSight
            case .rxLog: L10n.Tools.Tools.rxLog
            case .noiseFloor: L10n.Tools.Tools.noiseFloor
            case .nodeDiscovery: L10n.Tools.Tools.nodeDiscovery
            case .cli: L10n.Tools.Tools.cli
            }
        }

        var systemImage: String {
            switch self {
            case .tracePath: "point.3.connected.trianglepath.dotted"
            case .lineOfSight: "eye"
            case .rxLog: "waveform.badge.magnifyingglass"
            case .noiseFloor: "waveform"
            case .nodeDiscovery: "dot.radiowaves.left.and.right"
            case .cli: "terminal"
            }
        }

        var requiresRadio: Bool {
            self != .lineOfSight
        }
    }

    private enum SidebarDestination: Hashable {
        case lineOfSightPoints
    }

    @Environment(\.appState) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.appTheme) private var theme

    @State private var selectedTool: ToolSelection?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var sidebarPath = NavigationPath()
    @State private var isShowingLineOfSightPoints = false

    @State private var lineOfSightViewModel = LineOfSightViewModel()

    private var shouldUseSplitView: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        if shouldUseSplitView {
            NavigationSplitView(columnVisibility: $columnVisibility) {
                if isShowingLineOfSightPoints {
                    sidebarStack
                        .navigationSplitViewColumnWidth(
                            min: Self.lineOfSightSidebarWidthMin,
                            ideal: Self.lineOfSightSidebarWidthIdeal,
                            max: Self.lineOfSightSidebarWidthMax
                        )
                } else {
                    sidebarStack
                }
            } detail: {
                NavigationStack {
                    if selectedTool == .lineOfSight {
                        toolDetailView
                            .navigationBarTitleDisplayMode(.inline)
                    } else {
                        toolDetailView
                            .navigationTitle(selectedTool?.title ?? L10n.Tools.Tools.title)
                            .navigationBarTitleDisplayMode(.inline)
                    }
                }
                .liquidGlassToolbarBackground()
            }
            .ignoresSafeArea(edges: .top)
            .onChange(of: sidebarPath) { _, _ in
                if sidebarPath.isEmpty, isShowingLineOfSightPoints {
                    isShowingLineOfSightPoints = false
                    selectedTool = nil
                }
            }
            .onChange(of: appState.connectedDevice) { _, newDevice in
                if newDevice == nil, selectedTool?.requiresRadio == true {
                    selectedTool = nil
                    isShowingLineOfSightPoints = false
                    sidebarPath = NavigationPath()
                }
            }
        } else {
            NavigationStack {
                List {
                    ForEach(ToolSelection.allCases, id: \.self) { tool in
                        NavigationLink {
                            toolDestination(for: tool)
                        } label: {
                            Label(tool.title, systemImage: tool.systemImage)
                        }
                    }
                    .themedRowBackground(theme)
                }
                .themedCanvas(theme)
                .navigationTitle(L10n.Tools.Tools.title)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        BLEStatusIndicatorView()
                    }
                }
            }
        }
    }

    private var sidebarStack: some View {
        NavigationStack(path: $sidebarPath) {
            List {
                ForEach(ToolSelection.allCases, id: \.self) { tool in
                    Button {
                        selectTool(tool)
                    } label: {
                        Label(tool.title, systemImage: tool.systemImage)
                    }
                }
            }
            .listStyle(.sidebar)
            .navigationTitle(L10n.Tools.Tools.title)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    BLEStatusIndicatorView()
                }
            }
            .navigationDestination(for: SidebarDestination.self) { destination in
                switch destination {
                case .lineOfSightPoints:
                    LineOfSightView(viewModel: lineOfSightViewModel, layoutMode: .panel)
                        .navigationTitle(L10n.Tools.Tools.lineOfSight)
                        .navigationBarTitleDisplayMode(.inline)
                }
            }
        }
    }

    private func selectTool(_ tool: ToolSelection) {
        selectedTool = tool
        sidebarPath = NavigationPath()

        if tool == .lineOfSight {
            isShowingLineOfSightPoints = true
            sidebarPath.append(SidebarDestination.lineOfSightPoints)
        } else {
            isShowingLineOfSightPoints = false
        }
    }

    @ViewBuilder
    private func toolDestination(for tool: ToolSelection) -> some View {
        switch tool {
        case .tracePath: TracePathView()
        case .lineOfSight: LineOfSightView()
        case .rxLog: RxLogView()
        case .noiseFloor: NoiseFloorView()
        case .nodeDiscovery: NodeDiscoveryView()
        case .cli: CLIToolView()
        }
    }

    @ViewBuilder
    private var toolDetailView: some View {
        switch selectedTool {
        case .tracePath: TracePathView()
        case .lineOfSight: LineOfSightView(viewModel: lineOfSightViewModel, layoutMode: .map)
        case .rxLog: RxLogView()
        case .noiseFloor: NoiseFloorView()
        case .nodeDiscovery: NodeDiscoveryView()
        case .cli: CLIToolView()
        case .none: ContentUnavailableView(L10n.Tools.Tools.selectTool, systemImage: "wrench.and.screwdriver")
        }
    }
}

#Preview {
    ToolsView()
        .environment(\.appState, AppState())
}
