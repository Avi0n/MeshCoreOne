import CoreLocation
import SwiftUI
import MC1Services

/// The nodes list rendered as a `ScrollView` + `LazyVStack` rather than a `List`. `List` is backed
/// by `UpdateCoalescingCollectionView`, whose batch-consistency assertion is violated when the
/// selected row is deleted; a `LazyVStack` has no collection view, so that crash cannot occur. Row
/// actions live in a `.contextMenu`. One view serves both layouts: the compact stack navigates via
/// `NavigationLink`, the iPad split drives a selection binding the detail column reads.
struct ContactsListContent: View {
    enum ListMode {
        case selection(Binding<ContactDTO?>)
        case navigation
    }

    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let mode: ListMode
    @Binding var selectedSegment: NodeSegment
    let isSearching: Bool
    let searchText: String
    let filteredContacts: [ContactDTO]
    let hasLoadedOnce: Bool
    let viewModel: ContactsViewModel

    /// Leading inset for the inter-row divider, aligning it under the row text past the avatar.
    private static let rowSeparatorLeadingInset: CGFloat = 72

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
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section { } header: { pinnedSegmentHeader }
            }
        }
        .overlay { ProgressView() }
    }

    private var loadedBody: some View {
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section {
                    if filteredContacts.isEmpty {
                        emptyState
                    } else {
                        rows
                    }
                } header: {
                    pinnedSegmentHeader
                }
            }
        }
    }

    /// Segment picker as the pinned section header; `pinnedFilterHeaderBackground` documents the
    /// per-OS backing.
    private var pinnedSegmentHeader: some View {
        NodeSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
            .frame(maxWidth: .infinity)
            .pinnedFilterHeaderBackground(theme)
    }

    @ViewBuilder
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
            rowView(contact)
                .transition(.opacity)
            if index < filteredContacts.count - 1 {
                Divider().padding(.leading, Self.rowSeparatorLeadingInset)
            }
        }
    }

    @ViewBuilder
    private func rowView(_ contact: ContactDTO) -> some View {
        switch mode {
        case .selection(let selection):
            ContactSelectionRow(
                contact: contact,
                viewModel: viewModel,
                isSearching: isSearching,
                userLocation: appState.bestAvailableLocation,
                isSelected: selection.wrappedValue?.id == contact.id,
                onSelect: { selection.wrappedValue = contact }
            )
        case .navigation:
            ContactNavigationRow(
                contact: contact,
                viewModel: viewModel,
                isSearching: isSearching,
                userLocation: appState.bestAvailableLocation
            )
        }
    }
}

// MARK: - Row Layout

private enum ContactRowLayout {
    static let horizontalPadding: CGFloat = 16
    static let verticalPadding: CGFloat = 6
}

/// Renders a node's row body, shared by the selection and navigation rows.
private struct ContactListRowLabel: View {
    let contact: ContactDTO
    let viewModel: ContactsViewModel
    let isSearching: Bool
    let userLocation: CLLocation?

    var body: some View {
        ContactRowView(
            contact: contact,
            showTypeLabel: isSearching,
            userLocation: userLocation,
            isTogglingFavorite: viewModel.togglingFavoriteID == contact.id
        )
        .padding(.horizontal, ContactRowLayout.horizontalPadding)
        .padding(.vertical, ContactRowLayout.verticalPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(.rect)
    }
}

// MARK: - Extracted Rows

private struct ContactSelectionRow: View {
    let contact: ContactDTO
    let viewModel: ContactsViewModel
    let isSearching: Bool
    let userLocation: CLLocation?
    let isSelected: Bool
    let onSelect: () -> Void

    private var isDeleting: Bool { viewModel.deletingIDs.contains(contact.id) }

    var body: some View {
        Button(action: onSelect) {
            ContactListRowLabel(contact: contact, viewModel: viewModel, isSearching: isSearching, userLocation: userLocation)
        }
        .buttonStyle(.plain)
        .selectedRowHighlight(isSelected: isSelected)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .deletingRowOverlay(isDeleting: isDeleting)
        .contactContextMenu(contact: contact, viewModel: viewModel)
    }
}

private struct ContactNavigationRow: View {
    let contact: ContactDTO
    let viewModel: ContactsViewModel
    let isSearching: Bool
    let userLocation: CLLocation?

    private var isDeleting: Bool { viewModel.deletingIDs.contains(contact.id) }

    var body: some View {
        NavigationLink(value: contact) {
            ContactListRowLabel(contact: contact, viewModel: viewModel, isSearching: isSearching, userLocation: userLocation)
        }
        .buttonStyle(.plain)
        .deletingRowOverlay(isDeleting: isDeleting)
        .contactContextMenu(contact: contact, viewModel: viewModel)
    }
}
