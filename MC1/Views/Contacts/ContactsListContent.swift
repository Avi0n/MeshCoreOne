import CoreLocation
import MC1Services
import SwiftUI

/// The nodes list rendered as a `ScrollView` + `LazyVStack` rather than a `List`. `List` is backed
/// by `UpdateCoalescingCollectionView`, whose batch-consistency assertion is violated when the
/// selected row is deleted; a `LazyVStack` has no collection view, so that crash cannot occur.
struct ContactsListContent: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  let selectedContact: ContactDTO?
  let onSelect: (ContactDTO) -> Void
  @Binding var selectedSegment: NodeSegment
  let isSearching: Bool
  let searchText: String
  let filteredContacts: [ContactDTO]
  let timestampsByID: [UUID: UInt32]
  let hasLoadedOnce: Bool
  let viewModel: ContactsViewModel
  @Binding var scrollPositionID: UUID?
  let onRefresh: () async -> Void

  /// Leading inset for the inter-row divider, aligning it under the row text past the avatar.
  private static let rowSeparatorLeadingInset: CGFloat = 72
  /// Content container target used to scroll to the top after pull-to-refresh.
  private static let topScrollAnchor = "nodesListTop"

  var body: some View {
    Group {
      if !hasLoadedOnce {
        loadingBody
      } else {
        loadedBody
      }
    }
    .themedCanvas(theme)
  }

  private var loadingBody: some View {
    refreshableScroll {
      LazyVStack(spacing: 0) {
        Section {} header: { segmentHeader }
      }
      .scrollTargetLayout()
    }
    .overlay { ProgressView() }
  }

  private var loadedBody: some View {
    refreshableScroll {
      LazyVStack(spacing: 0) {
        Section {
          if filteredContacts.isEmpty {
            emptyState
          } else {
            rows
          }
        } header: {
          segmentHeader
        }
      }
      .scrollTargetLayout()
    }
  }

  private func refreshableScroll(@ViewBuilder content: () -> some View) -> some View {
    let scrollContent = content()
    return ScrollViewReader { proxy in
      ScrollView {
        scrollContent.id(Self.topScrollAnchor)
      }
      .scrollPosition(id: $scrollPositionID, anchor: .top)
      .swipeActionsContainerIfAvailable()
      .refreshable {
        await onRefresh()
        // `.refreshable` on ScrollView leaves the large title jumped; scrollTo restacks it.
        // https://stackoverflow.com/questions/75718110
        Task { @MainActor in
          proxy.scrollTo(Self.topScrollAnchor, anchor: .top)
        }
      }
      .onChange(of: filteredContacts.map(\.id)) { _, ids in
        if let scrollPositionID, !ids.contains(scrollPositionID) {
          self.scrollPositionID = ids.first
        }
      }
    }
  }

  private var segmentHeader: some View {
    NodeSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
      .frame(maxWidth: .infinity)
      .pinnedFilterHeaderBackground(theme)
  }

  private var emptyState: some View {
    Group {
      if isSearching {
        ContactsSearchEmptyView(searchText: searchText)
      } else {
        ContactsEmptyView(selectedSegment: selectedSegment)
      }
    }
    .containerRelativeFrame([.horizontal, .vertical])
  }

  private var rows: some View {
    ForEach(Array(filteredContacts.enumerated()), id: \.element.id) { index, contact in
      VStack(spacing: 0) {
        ContactSelectionRow(
          contact: contact,
          timestamp: timestampsByID[contact.id],
          viewModel: viewModel,
          isSearching: isSearching,
          userLocation: appState.bestAvailableLocation,
          isSelected: selectedContact?.id == contact.id,
          onSelect: { onSelect(contact) }
        )
        if index < filteredContacts.count - 1 {
          Divider().padding(.leading, Self.rowSeparatorLeadingInset)
        }
      }
      .id(contact.id)
      .transition(.opacity)
    }
  }
}

// MARK: - Row Layout

private enum ContactRowLayout {
  static let horizontalPadding: CGFloat = 16
  static let verticalPadding: CGFloat = 6
}

/// Renders a node's row body, shared by selection rows.
private struct ContactListRowLabel: View {
  let contact: ContactDTO
  let timestamp: UInt32?
  let viewModel: ContactsViewModel
  let isSearching: Bool
  let userLocation: CLLocation?

  var body: some View {
    ContactRowView(
      contact: contact,
      timestamp: timestamp,
      showTypeLabel: isSearching,
      userLocation: userLocation,
      isTogglingFavorite: viewModel.togglingFavoriteID == contact.id,
      inboundHopCount: viewModel.inboundHopByKey[contact.publicKey]
    )
    .padding(.horizontal, ContactRowLayout.horizontalPadding)
    .padding(.vertical, ContactRowLayout.verticalPadding)
    .frame(maxWidth: .infinity, alignment: .leading)
    .contentShape(.rect)
  }
}

private struct ContactSelectionRow: View {
  let contact: ContactDTO
  let timestamp: UInt32?
  let viewModel: ContactsViewModel
  let isSearching: Bool
  let userLocation: CLLocation?
  let isSelected: Bool
  let onSelect: () -> Void

  private var isDeleting: Bool {
    viewModel.deletingIDs.contains(contact.id)
  }

  var body: some View {
    Button(action: onSelect) {
      ContactListRowLabel(
        contact: contact,
        timestamp: timestamp,
        viewModel: viewModel,
        isSearching: isSearching,
        userLocation: userLocation
      )
    }
    .buttonStyle(.plain)
    .selectedRowHighlight(isSelected: isSelected)
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .deletingRowOverlay(isDeleting: isDeleting)
    .contactContextMenu(contact: contact, viewModel: viewModel)
    .contactSwipeActions(contact: contact, viewModel: viewModel)
  }
}
