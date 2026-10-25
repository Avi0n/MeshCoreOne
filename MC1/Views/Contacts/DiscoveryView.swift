import CoreLocation
import MC1Services
import SwiftUI

/// Shows contacts heard in adverts that have not been added to the radio.
struct DiscoveryView: View {
  @Environment(\.appTheme) private var theme
  @State private var ownedState: DiscoveryState
  private let suppliedState: DiscoveryState?
  private let rendererRevision: Int
  @FocusState private var isSearchFocused: Bool
  @State private var isMounted = false
  @AppStorage(AppStorageKey.discoverySortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard

  init() {
    _ownedState = State(initialValue: DiscoveryState())
    suppliedState = nil
    rendererRevision = 0
  }

  init(state: DiscoveryState, rendererRevision: Int = 0) {
    _ownedState = State(initialValue: state)
    suppliedState = state
    self.rendererRevision = rendererRevision
  }

  private var state: DiscoveryState {
    suppliedState ?? ownedState
  }

  private var viewModel: DiscoveryViewModel {
    state.viewModel
  }

  private var isSearching: Bool {
    !state.searchText.isEmpty
  }

  var body: some View {
    if suppliedState == nil {
      content.modifier(DiscoveryPresenter(state: state))
    } else {
      content
    }
  }

  private var content: some View {
    @Bindable var state = state
    return Group {
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
          showClearConfirmation: $state.showClearConfirmation
        )
      }
    }
    .searchable(
      text: Binding(
        get: { state.searchText },
        set: { state.updateSearchText($0, rendererRevision: rendererRevision) }
      ),
      placement: .navigationBarDrawer(displayMode: .always),
      prompt: L10n.Contacts.Contacts.Discovery.searchPrompt
    )
    .searchFocused($isSearchFocused)
    .task {
      guard state.rendererRevision == rendererRevision else { return }
      isMounted = true
      isSearchFocused = state.isSearchFocused
    }
    .onDisappear { isMounted = false }
    .onChange(of: isSearchFocused) { _, focused in
      guard isMounted else { return }
      state.updateSearchFocus(focused, rendererRevision: rendererRevision)
    }
  }

  private var filterHeader: some View {
    @Bindable var state = state
    return DiscoverSegmentPicker(selection: $state.selectedSegment, isSearching: isSearching)
      .frame(maxWidth: .infinity)
      .pinnedFilterHeaderBackground(theme)
  }

  private var emptyState: some View {
    Group {
      if isSearching {
        DiscoverySearchEmptyView(searchText: state.searchText)
      } else {
        DiscoveryEmptyView()
      }
    }
    .containerRelativeFrame([.horizontal, .vertical])
  }

  private static let rowSeparatorLeadingInset: CGFloat = 72

  private var loadingBody: some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        Section {} header: { filterHeader }
      }
      .scrollTargetLayout()
    }
    .overlay { ProgressView() }
  }

  private var loadedBody: some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        Section {
          if viewModel.visibleNodes.isEmpty {
            emptyState
          } else {
            rows
          }
        } header: {
          filterHeader
        }
      }
      .scrollTargetLayout()
    }
    .scrollPosition(id: Binding(
      get: { state.scrollPositionID },
      set: { state.updateScrollPosition($0, rendererRevision: rendererRevision) }
    ), anchor: .top)
  }

  private var rows: some View {
    let nodes = viewModel.visibleNodes
    return ForEach(Array(nodes.enumerated()), id: \.element.id) { index, node in
      VStack(spacing: 0) {
        DiscoveryNodeRow(
          node: node,
          isAdded: viewModel.isAdded(node),
          isAdding: state.addingNodeID == node.id,
          onAdd: { state.add(node) },
          onDelete: { state.delete(node) }
        )
        if index < nodes.count - 1 {
          Divider().padding(.leading, Self.rowSeparatorLeadingInset)
        }
      }
      .id(node.id)
      .transition(.opacity)
    }
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
  private static let avatarSize: CGFloat = 44
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
      ContactAvatar(name: node.name, size: Self.avatarSize)
    case .repeater:
      NodeAvatar(publicKey: node.publicKey, role: .repeater, size: Self.avatarSize)
    case .room:
      NodeAvatar(publicKey: node.publicKey, role: .roomServer, size: Self.avatarSize)
    case .sensor:
      NodeAvatar(publicKey: node.publicKey, role: .sensor, size: Self.avatarSize)
    }
  }

  private var nodeTypeLabel: String {
    switch node.nodeType {
    case .chat: L10n.Contacts.Contacts.NodeKind.chat
    case .repeater: L10n.Contacts.Contacts.NodeKind.repeater
    case .room: L10n.Contacts.Contacts.NodeKind.room
    case .sensor: L10n.Contacts.Contacts.NodeKind.sensor
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
