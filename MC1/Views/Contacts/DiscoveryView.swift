import CoreLocation
import MC1Services
import SwiftUI

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

  private var isSearching: Bool {
    !searchText.isEmpty
  }

  /// Sort orders that recompute when the user location sample changes.
  private var consumesLocation: Bool {
    switch sortOrder {
    case .distance, .hops: true
    case .lastHeard, .name: false
    }
  }

  /// Value-typed projection of `bestAvailableLocation` so `onChange` compares
  /// coordinates, not `CLLocation` identity (radio-GPS fallback allocates fresh).
  private var locationSample: LocationSample? {
    guard let location = appState.bestAvailableLocation else { return nil }
    return LocationSample(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
  }

  private var effectiveSortOrder: NodeSortOrder {
    (sortOrder == .distance && appState.bestAvailableLocation == nil) ? .lastHeard : sortOrder
  }

  /// Segment picker as the pinned section header; `pinnedFilterHeaderBackground` documents the
  /// per-OS backing.
  private var pinnedFilterHeader: some View {
    DiscoverSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
      .frame(maxWidth: .infinity)
      .pinnedFilterHeaderBackground(theme)
  }

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
      refreshVisibleNodes()
    }
    .onChange(of: selectedSegment) { _, _ in
      refreshVisibleNodes()
    }
    .onChange(of: sortOrder) { _, _ in
      refreshVisibleNodes()
    }
    .onChange(of: locationSample) { _, _ in
      guard consumesLocation else { return }
      refreshVisibleNodes()
    }
    .task {
      configureViewModel()
      await viewModel.loadDiscoveredNodes()
      // Seed filter inputs with the view's restored @AppStorage sort synchronously
      // after load so the first painted frame uses the correct order.
      refreshVisibleNodes()
    }
    .onChange(of: appState.servicesVersion) { _, _ in
      configureViewModel()
      viewModel.scheduleCoalescedReload()
    }
    .onChange(of: appState.contactsVersion) { _, _ in
      viewModel.scheduleCoalescedReload()
    }
    .errorAlert($viewModel.errorMessage, title: L10n.Contacts.Contacts.Common.error)
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
        Section {} header: { pinnedFilterHeader }
      }
    }
    .overlay { ProgressView() }
  }

  private var loadedBody: some View {
    ScrollView {
      LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
        Section {
          if viewModel.visibleNodes.isEmpty {
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
    let nodes = viewModel.visibleNodes
    return ForEach(Array(nodes.enumerated()), id: \.element.id) { index, node in
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
      if index < nodes.count - 1 {
        Divider().padding(.leading, Self.rowSeparatorLeadingInset)
      }
    }
  }

  private func configureViewModel() {
    viewModel.configure(
      dataStore: { [appState] in appState.offlineDataStore },
      radioID: { [appState] in appState.connectedDevice?.radioID }
    )
  }

  private func refreshVisibleNodes() {
    viewModel.updateVisibleNodes(
      searchText: searchText,
      segment: selectedSegment,
      sortOrder: effectiveSortOrder,
      userLocation: appState.bestAvailableLocation
    )
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
          lastModified: UInt32(Date().timeIntervalSince1970)
        )
        try await contactService.addOrUpdateContact(radioID: node.radioID, contact: frame)
        await viewModel.loadDiscoveredNodes()
      } catch ContactServiceError.contactTableFull {
        let maxContacts = appState.connectedDevice?.maxContacts
        if let maxContacts {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
        } else {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
        }
      } catch {
        viewModel.errorMessage = error.userFacingMessage
      }
      addingNodeID = nil
    }
  }

  private func clearAllDiscoveredNodes() async {
    await viewModel.clearAllDiscoveredNodes()

    AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Discovery.clearedAllNodes).post()
  }
}

/// Equatable projection of a location for `onChange` without `CLLocation` identity comparison.
/// Raw doubles are fine: `LocationService` is one-shot `requestLocation()`, not continuous GPS.
private struct LocationSample: Equatable {
  let latitude: Double
  let longitude: Double
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

        Text(node.publicKey.uppercaseHexString())
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
          if !node.isFloodRouted, node.pathHopCount == 0 {
            Text(L10n.Contacts.Contacts.Route.direct)
          } else if let hops = node.displayedHopCount {
            Text("\(hops)")

            let pathNodes = node.pathNodesHex
            if !node.isFloodRouted, !pathNodes.isEmpty {
              Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
              Text(formattedPath(pathNodes))
                .monospaced()
            }
          } else {
            Text(L10n.Contacts.Contacts.Route.flood)
          }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
      }

      Spacer()

      RelativeTimestampText(date: node.lastHeard)

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
    case .chat: L10n.Contacts.Contacts.NodeKind.chat
    case .repeater: L10n.Contacts.Contacts.NodeKind.repeater
    case .room: L10n.Contacts.Contacts.NodeKind.room
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
