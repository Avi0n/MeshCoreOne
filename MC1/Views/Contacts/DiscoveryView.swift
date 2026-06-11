import CoreLocation
import SwiftUI
import MC1Services

/// Shows contacts discovered via advertisement that haven't been added to the device
struct DiscoveryView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var viewModel = DiscoveryViewModel()
    @State private var searchText = ""
    @State private var selectedSegment: DiscoverSegment = .all
    @AppStorage(AppStorageKey.discoverySortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard
    @State private var addingNodeID: UUID?
    @State private var showClearConfirmation = false

    private var filteredNodes: [DiscoveredNodeDTO] {
        let effectiveSortOrder = (sortOrder == .distance && appState.bestAvailableLocation == nil)
            ? .lastHeard
            : sortOrder

        return viewModel.filteredNodes(
            searchText: searchText,
            segment: selectedSegment,
            sortOrder: effectiveSortOrder,
            userLocation: appState.bestAvailableLocation
        )
    }

    private var isSearching: Bool {
        !searchText.isEmpty
    }

    private var showErrorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )
    }

    /// Segment picker as the pinned section header; `pinnedFilterHeaderBackground` documents the
    /// per-OS backing.
    private var pinnedFilterHeader: some View {
        DiscoverSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
            .frame(maxWidth: .infinity)
            .pinnedFilterHeaderBackground(theme)
    }

    @ViewBuilder
    private var emptyState: some View {
        Group {
            if isSearching {
                DiscoverySearchEmptyView(searchText: searchText)
            } else {
                DiscoveryEmptyView()
            }
        }
        .containerRelativeFrame([.horizontal, .vertical])
    }

    var body: some View {
        Group {
            if !viewModel.hasLoadedOnce {
                loadingBody
            } else {
                loadedBody
            }
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Contacts.Contacts.Discovery.title)
        .toolbar {
            ToolbarItem(placement: .automatic) {
                DiscoverySortMenu(sortOrder: $sortOrder)
            }

            ToolbarItem(placement: .automatic) {
                DiscoveryMoreMenu(
                    isEmpty: viewModel.discoveredNodes.isEmpty,
                    showClearConfirmation: $showClearConfirmation
                )
            }
        }
        .searchable(
            text: $searchText,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: L10n.Contacts.Contacts.Discovery.searchPrompt
        )
        .onChange(of: searchText) { oldValue, newValue in
            if oldValue.isEmpty, !newValue.isEmpty {
                AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Discovery.searchingAllTypes).post()
            }
        }
        .task {
            viewModel.configure(appState: appState)
            await loadDiscoveredNodes()
        }
        .onChange(of: appState.servicesVersion) { _, _ in
            Task {
                await loadDiscoveredNodes()
            }
        }
        .onChange(of: appState.contactsVersion) { _, _ in
            Task {
                await loadDiscoveredNodes()
            }
        }
        .alert(L10n.Contacts.Contacts.Common.error, isPresented: showErrorBinding) {
            Button(L10n.Contacts.Contacts.Common.ok) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .confirmationDialog(
            L10n.Contacts.Contacts.Discovery.Clear.title,
            isPresented: $showClearConfirmation,
            titleVisibility: .visible
        ) {
            Button(L10n.Contacts.Contacts.Discovery.Clear.confirm, role: .destructive) {
                Task {
                    await clearAllDiscoveredNodes()
                }
            }
        } message: {
            Text(L10n.Contacts.Contacts.Discovery.Clear.message)
        }
    }

    /// Leading inset for the inter-row divider, aligning it under the row text past the avatar.
    private static let rowSeparatorLeadingInset: CGFloat = 72

    private var loadingBody: some View {
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section { } header: { pinnedFilterHeader }
            }
        }
        .overlay { ProgressView() }
    }

    private var loadedBody: some View {
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section {
                    if filteredNodes.isEmpty {
                        emptyState
                    } else {
                        rows
                    }
                } header: {
                    pinnedFilterHeader
                }
            }
        }
    }

    private var rows: some View {
        ForEach(Array(filteredNodes.enumerated()), id: \.element.id) { index, node in
            DiscoveryNodeRow(
                node: node,
                isAdded: viewModel.isAdded(node),
                isAdding: addingNodeID == node.id,
                onAdd: { addNode(node) },
                onDelete: {
                    Task {
                        await viewModel.deleteDiscoveredNode(node)
                    }
                }
            )
            .transition(.opacity)
            if index < filteredNodes.count - 1 {
                Divider().padding(.leading, Self.rowSeparatorLeadingInset)
            }
        }
    }

    private func loadDiscoveredNodes() async {
        guard let radioID = appState.connectedDevice?.radioID else { return }
        viewModel.configure(appState: appState)
        await viewModel.loadDiscoveredNodes(radioID: radioID)
    }

    private func addNode(_ node: DiscoveredNodeDTO) {
        guard let contactService = appState.services?.contactService else { return }

        addingNodeID = node.id
        Task {
            do {
                let frame = ContactFrame(
                    publicKey: node.publicKey,
                    type: node.nodeType,
                    flags: 0,
                    outPathLength: node.outPathLength,
                    outPath: node.outPath,
                    name: node.name,
                    lastAdvertTimestamp: node.lastAdvertTimestamp,
                    latitude: node.latitude,
                    longitude: node.longitude,
                    lastModified: 0
                )
                try await contactService.addOrUpdateContact(radioID: node.radioID, contact: frame)
                await viewModel.loadDiscoveredNodes(radioID: node.radioID)
            } catch ContactServiceError.contactTableFull {
                let maxContacts = appState.connectedDevice?.maxContacts
                if let maxContacts {
                    viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
                } else {
                    viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
                }
            } catch {
                viewModel.errorMessage = error.localizedDescription
            }
            addingNodeID = nil
        }
    }

    private func clearAllDiscoveredNodes() async {
        guard let radioID = appState.connectedDevice?.radioID else { return }
        await viewModel.clearAllDiscoveredNodes(radioID: radioID)

        AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Discovery.clearedAllNodes).post()
    }
}

// MARK: - Empty View

private struct DiscoveryEmptyView: View {
    var body: some View {
        ContentUnavailableView(
            L10n.Contacts.Contacts.Discovery.Empty.title,
            systemImage: "antenna.radiowaves.left.and.right",
            description: Text(L10n.Contacts.Contacts.Discovery.Empty.description)
        )
    }
}

// MARK: - Search Empty View

private struct DiscoverySearchEmptyView: View {
    let searchText: String

    var body: some View {
        ContentUnavailableView(
            L10n.Contacts.Contacts.Discovery.Empty.Search.title,
            systemImage: "magnifyingglass",
            description: Text(L10n.Contacts.Contacts.Discovery.Empty.Search.description(searchText))
        )
    }
}

// MARK: - Row Layout

private enum DiscoveryListLayout {
    static let rowHorizontalPadding: CGFloat = 16
}

// MARK: - Node Row

private struct DiscoveryNodeRow: View {
    @Environment(\.appState) private var appState
    let node: DiscoveredNodeDTO
    let isAdded: Bool
    let isAdding: Bool
    let onAdd: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack {
            avatarView

            VStack(alignment: .leading, spacing: 2) {
                Text(node.name)
                    .font(.body)
                    .bold()

                Text(node.publicKey.hexString())
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                HStack(spacing: 4) {
                    Text(nodeTypeLabel)

                    if node.hasLocation {
                        Text("·")

                        Label(L10n.Contacts.Contacts.Row.location, systemImage: "location.fill")
                            .labelStyle(.iconOnly)
                            .foregroundStyle(.green)

                        if let distance = distanceToNode {
                            Text(distance)
                        }
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                HStack(spacing: 4) {
                    Image(systemName: "arrowshape.bounce.right")
                    if node.isFloodRouted {
                        Text(L10n.Contacts.Contacts.Route.flood)
                    } else if node.pathHopCount == 0 {
                        Text(L10n.Contacts.Contacts.Route.direct)
                    } else {
                        let pathNodes = node.pathNodesHex
                        Text("\(node.pathHopCount)")

                        if !pathNodes.isEmpty {
                            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                            Text(formattedPath(pathNodes))
                                .monospaced()
                        }
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            Spacer()

            RelativeTimestampText(timestamp: node.lastAdvertTimestamp)

            if isAdded {
                Button(L10n.Contacts.Contacts.Discovery.added) {}
                    .buttonStyle(.bordered)
                    .disabled(true)
                    .accessibilityLabel(L10n.Contacts.Contacts.Discovery.addedAccessibility)
            } else {
                Button(L10n.Contacts.Contacts.Discovery.add) {
                    onAdd()
                }
                .buttonStyle(.borderedProminent)
                .disabled(isAdding)
            }
        }
        .padding(.horizontal, DiscoveryListLayout.rowHorizontalPadding)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(.rect)
        .contextMenu {
            Button(role: .destructive) {
                onDelete()
            } label: {
                Label(L10n.Contacts.Contacts.Discovery.remove, systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private var avatarView: some View {
        switch node.nodeType {
        case .chat:
            ContactAvatar(name: node.name, size: 44)
        case .repeater:
            NodeAvatar(publicKey: node.publicKey, role: .repeater, size: 44)
        case .room:
            NodeAvatar(publicKey: node.publicKey, role: .roomServer, size: 44)
        }
    }

    private var nodeTypeLabel: String {
        switch node.nodeType {
        case .chat: return L10n.Contacts.Contacts.NodeKind.chat
        case .repeater: return L10n.Contacts.Contacts.NodeKind.repeater
        case .room: return L10n.Contacts.Contacts.NodeKind.room
        }
    }

    private func formattedPath(_ nodes: [String]) -> String {
        if nodes.count > 6 {
            let first = nodes.prefix(3).joined(separator: ",")
            let last = nodes.suffix(3).joined(separator: ",")
            return "\(first)…\(last)"
        }
        return nodes.joined(separator: ",")
    }

    private var distanceToNode: String? {
        guard let userLocation = appState.bestAvailableLocation,
              node.hasLocation else { return nil }

        let nodeLocation = CLLocation(
            latitude: node.latitude,
            longitude: node.longitude
        )
        let meters = userLocation.distance(from: nodeLocation)
        let measurement = Measurement(value: meters, unit: UnitLength.meters)

        let formattedDistance = measurement.formatted(.measurement(
            width: .abbreviated,
            usage: .road
        ))
        return L10n.Contacts.Contacts.Row.away(formattedDistance)
    }
}

// MARK: - Sort Menu

private struct DiscoverySortMenu: View {
    @Binding var sortOrder: NodeSortOrder

    var body: some View {
        Menu {
            ForEach(NodeSortOrder.allCases, id: \.self) { order in
                Button {
                    sortOrder = order
                } label: {
                    if sortOrder == order {
                        Label(order.localizedTitle, systemImage: "checkmark")
                    } else {
                        Text(order.localizedTitle)
                    }
                }
            }
        } label: {
            Label(L10n.Contacts.Contacts.List.sort, systemImage: "arrow.up.arrow.down")
        }
        .liquidGlassSecondaryButtonStyle()
        .accessibilityLabel(L10n.Contacts.Contacts.Discovery.sortMenu)
        .accessibilityHint(L10n.Contacts.Contacts.Discovery.sortMenuHint)
    }
}

// MARK: - More Menu

private struct DiscoveryMoreMenu: View {
    let isEmpty: Bool
    @Binding var showClearConfirmation: Bool

    var body: some View {
        Menu {
            Button(role: .destructive) {
                showClearConfirmation = true
            } label: {
                Label(L10n.Contacts.Contacts.Discovery.clear, systemImage: "trash")
            }
            .disabled(isEmpty)
        } label: {
            Label(L10n.Contacts.Contacts.Discovery.menu, systemImage: "ellipsis.circle")
        }
        .liquidGlassSecondaryButtonStyle()
    }
}

#Preview {
    NavigationStack {
        DiscoveryView()
    }
    .environment(\.appState, AppState())
}
