import MC1Services
import SwiftUI

struct NodeDiscoveryView: View {
    @Environment(\.appState) private var appState
    @State private var viewModel = NodeDiscoveryViewModel()

    private var isConnected: Bool {
        appState.services?.session != nil
    }

    var body: some View {
        Group {
            if !isConnected {
                disconnectedState
            } else if !viewModel.isScanning && viewModel.sortedResults.isEmpty && viewModel.errorMessage == nil {
                initialState
            } else {
                ResultsList(viewModel: viewModel)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if isConnected {
                ScanButtonBar(viewModel: viewModel)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                SortMenu(viewModel: viewModel)
            }
        }
        .errorAlert($viewModel.errorMessage, title: L10n.Tools.Tools.NodeDiscovery.errorTitle)
        .sensoryFeedback(.impact(weight: .medium), trigger: viewModel.scanStartHapticTrigger)
        .sensoryFeedback(.success, trigger: viewModel.scanSuccessHapticTrigger)
        .sensoryFeedback(.warning, trigger: viewModel.scanEmptyHapticTrigger)
        .sensoryFeedback(.success, trigger: viewModel.addSuccessHapticTrigger)
        .sensoryFeedback(.error, trigger: viewModel.addErrorHapticTrigger)
        .task(id: appState.servicesVersion) {
            viewModel.configure(
                session: appState.services?.session,
                dataStore: appState.offlineDataStore,
                radioID: appState.connectedDevice?.radioID,
                contactService: appState.services?.contactService,
                maxContacts: appState.connectedDevice?.maxContacts
            )
        }
        .onDisappear {
            viewModel.stopScan()
        }
        .onChange(of: viewModel.filter) { _, _ in
            viewModel.stopScan()
        }
    }
}

// MARK: - States

extension NodeDiscoveryView {
    private var disconnectedState: some View {
        ContentUnavailableView {
            Label(L10n.Tools.Tools.RxLog.notConnected, systemImage: "antenna.radiowaves.left.and.right.slash")
        } description: {
            Text(L10n.Tools.Tools.NodeDiscovery.notConnectedDescription(viewModel.filter.localizedTitle))
        }
    }

    private var initialState: some View {
        VStack {
            NodeDiscoverySegmentPicker(selection: $viewModel.filter, isScanning: viewModel.isScanning)

            Spacer()

            ContentUnavailableView {
                Label(L10n.Tools.Tools.NodeDiscovery.scanPrompt(viewModel.filter.localizedTitle), systemImage: "magnifyingglass")
            } description: {
                Text(L10n.Tools.Tools.NodeDiscovery.scanPromptDescription(viewModel.filter.localizedTitle))
            }

            Spacer()
        }
    }
}

// MARK: - Results List

extension NodeDiscoveryView {
    private struct ResultsList: View {
        @Bindable var viewModel: NodeDiscoveryViewModel
        @Environment(\.appTheme) private var theme

        var body: some View {
            List {
                Section {
                    NodeDiscoverySegmentPicker(selection: $viewModel.filter, isScanning: viewModel.isScanning)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listSectionSeparator(.hidden)

                if viewModel.sortedResults.isEmpty && !viewModel.isScanning {
                    ContentUnavailableView {
                        Label(L10n.Tools.Tools.NodeDiscovery.noResults(viewModel.filter.localizedTitle), systemImage: "magnifyingglass")
                    } description: {
                        Text(L10n.Tools.Tools.NodeDiscovery.noResultsDescription(viewModel.filter.localizedTitle))
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                } else {
                    ForEach(viewModel.sortedResults) { result in
                        NodeDiscoveryRowView(
                            result: result,
                            isAdded: viewModel.isAdded(publicKey: result.publicKey),
                            isAdding: viewModel.addingPublicKey == result.publicKey,
                            onAdd: ContactType(rawValue: result.nodeType) != nil
                                ? { viewModel.addNode(result) }
                                : nil
                        )
                    }
                }
            }
            .listStyle(.plain)
            .themedCanvas(theme)
        }
    }
}

// MARK: - Scan Button

extension NodeDiscoveryView {
    private struct ScanButtonBar: View {
        let viewModel: NodeDiscoveryViewModel

        var body: some View {
            LiquidGlassContainer {
                Button {
                    if viewModel.isScanning {
                        viewModel.stopScan()
                    } else {
                        viewModel.scan()
                    }
                } label: {
                    if viewModel.isScanning {
                        HStack {
                            ProgressView().controlSize(.small)
                            Text(L10n.Tools.Tools.NodeDiscovery.stopButton)
                        }
                    } else {
                        Text(L10n.Tools.Tools.NodeDiscovery.scanButton(viewModel.filter.localizedTitle))
                    }
                }
                .liquidGlassProminentButtonStyle()
            }
            .padding(.bottom, 24)
        }
    }
}

// MARK: - Sort Menu

extension NodeDiscoveryView {
    private struct SortMenu: View {
        let viewModel: NodeDiscoveryViewModel

        var body: some View {
            Menu {
                ForEach(NodeDiscoverySortOrder.allCases, id: \.self) { order in
                    Button {
                        viewModel.sortOrder = order
                    } label: {
                        if viewModel.sortOrder == order {
                            Label(order.localizedTitle, systemImage: "checkmark")
                        } else {
                            Text(order.localizedTitle)
                        }
                    }
                }
            } label: {
                Label(L10n.Tools.Tools.NodeDiscovery.sortMenu, systemImage: "arrow.up.arrow.down")
            }
            .liquidGlassButtonStyle()
            .accessibilityLabel(L10n.Tools.Tools.NodeDiscovery.sortMenu)
            .accessibilityHint(L10n.Tools.Tools.NodeDiscovery.sortMenuHint)
        }
    }
}

// MARK: - Segment Picker

struct NodeDiscoverySegmentPicker: View {
    @Binding var selection: NodeDiscoveryFilter
    let isScanning: Bool

    var body: some View {
        Picker(L10n.Tools.Tools.NodeDiscovery.repeaters, selection: $selection) {
            ForEach(NodeDiscoveryFilter.allCases, id: \.self) { filter in
                Text(filter.localizedTitle).tag(filter)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.vertical, 8)
        .opacity(isScanning ? 0.5 : 1.0)
        .disabled(isScanning)
    }
}

#Preview {
    NavigationStack {
        NodeDiscoveryView()
    }
    .environment(\.appState, AppState())
}
