import Accessibility
import SwiftUI
import MC1Services

/// Pushed onto the same NavigationStack as `PathEditingSheet` via
/// `.navigationDestination(item: $viewModel.insertionIntent)`. Dedicated to
/// finding a repeater fast via name substring or hex prefix.
struct AddHopPickerView: View {
    @Bindable var viewModel: PathManagementViewModel
    let intent: AddHopIntent

    @State private var searchText = ""
    @State private var filter: AddHopFilter = .all
    @State private var addHapticTrigger = 0

    var body: some View {
        List {
            if viewModel.isPathFull {
                Section {
                    maxHopsReachedView
                        .listRowBackground(Color.clear)
                }
            } else {
                resultsContent
            }
        }
        .listStyle(.insetGrouped)
        .environment(\.editMode, .constant(.inactive))  // override parent's .active
        .navigationHeader(
            title: L10n.Contacts.Contacts.PathEdit.addHop,
            subtitle: viewModel.isPathFull ? "" : bannerText
        )
        .searchable(
            text: $searchText,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: L10n.Contacts.Contacts.PathEdit.searchPrompt
        )
        .safeAreaInset(edge: .top, spacing: 0) {
            AddHopSegmentPicker(selection: $filter)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: addHapticTrigger)
    }

    @ViewBuilder
    private var resultsContent: some View {
        let results = buildResults()
        repeaterSection(L10n.Contacts.Contacts.PathEdit.Sections.recent, results: results.recent)
        repeaterSection(L10n.Contacts.Contacts.PathEdit.Sections.favorites, results: results.favorites)
        repeaterSection(L10n.Contacts.Contacts.PathEdit.Sections.contacts, results: results.contacts)
        repeaterSection(L10n.Contacts.Contacts.PathEdit.Sections.discovered, results: results.discovered)
        repeaterSection(L10n.Contacts.Contacts.PathEdit.Sections.rooms, results: results.rooms)
        if results.isEmpty {
            Section {
                emptyResultsView
                    .listRowBackground(Color.clear)
            }
        }
    }

    @ViewBuilder
    private func repeaterSection(_ title: String, results: [PickerNode]) -> some View {
        if !results.isEmpty {
            Section(title) {
                ForEach(results, id: \.id) { node in
                    PickerRowView(
                        node: node,
                        intent: intent,
                        viewModel: viewModel,
                        addHapticTrigger: $addHapticTrigger
                    )
                }
            }
        }
    }

    // MARK: - Subtitle text

    private var bannerText: String {
        Self.bannerText(for: viewModel, intent: intent)
    }

    /// Shared text source so the navigation subtitle and the row-tap
    /// announcement (posted from `PickerRowView.handleTap`) read identically.
    @MainActor
    static func bannerText(for viewModel: PathManagementViewModel, intent: AddHopIntent) -> String {
        if viewModel.isPathFull {
            return L10n.Contacts.Contacts.PathEdit.MaxHops.reached
        }
        switch intent {
        case .append:
            return L10n.Contacts.Contacts.PathEdit.positionAppend(viewModel.editablePath.count + 1)
        }
    }

    // MARK: - Result builders

    /// Results for all five sections, built once per body so row rendering and
    /// the empty-state guard read from the same materialized state.
    private struct PickerResults {
        var recent: [PickerNode] = []
        var favorites: [PickerNode] = []
        var contacts: [PickerNode] = []
        var discovered: [PickerNode] = []
        var rooms: [PickerNode] = []

        var isEmpty: Bool {
            recent.isEmpty && favorites.isEmpty && contacts.isEmpty && discovered.isEmpty && rooms.isEmpty
        }
    }

    private func buildResults() -> PickerResults {
        let recentKeys = Set(viewModel.recentPublicKeys)
        let contactKeys = Set(viewModel.availableRepeaters.map(\.publicKey))
        var results = PickerResults()
        if showsRecent { results.recent = recentResults() }
        if showsFavorites { results.favorites = favoriteResults(excluding: recentKeys) }
        if showsContacts { results.contacts = contactResults(excluding: recentKeys) }
        if showsDiscovered { results.discovered = discoveredResults(recentKeys: recentKeys, contactKeys: contactKeys) }
        if showsRooms { results.rooms = roomResults(excluding: recentKeys) }
        return results
    }

    /// Recent hits resolved against contacts + discovered nodes, preserving LRU
    /// order. Filtered against the current search query.
    private func recentResults() -> [PickerNode] {
        let resolved = viewModel.recentPublicKeys.compactMap { pubkey -> PickerNode? in
            if let contact = viewModel.availableRepeaters.first(where: { $0.publicKey == pubkey }) {
                return .contact(contact)
            }
            if let discovered = viewModel.discoveredRepeaters.first(where: { $0.publicKey == pubkey }) {
                return .discovered(discovered)
            }
            return nil
        }
        return PathManagementViewModel.filtered(resolved, by: searchText)
    }

    /// Favorite contacts minus anything already in Recent.
    private func favoriteResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRepeaters
            .filter { $0.isFavorite && !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return PathManagementViewModel.filtered(nodes, by: searchText)
    }

    /// Non-favorite contact repeaters minus Recent.
    private func contactResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRepeaters
            .filter { !$0.isFavorite && !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return PathManagementViewModel.filtered(nodes, by: searchText)
    }

    /// Discovered repeaters minus any pubkey already present as a contact and
    /// anything in Recent.
    private func discoveredResults(recentKeys: Set<Data>, contactKeys: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.discoveredRepeaters
            .filter { !contactKeys.contains($0.publicKey) && !recentKeys.contains($0.publicKey) }
            .sorted { $0.resolvableName.localizedCaseInsensitiveCompare($1.resolvableName) == .orderedAscending }
            .map { PickerNode.discovered($0) }
        return PathManagementViewModel.filtered(nodes, by: searchText)
    }

    /// Rooms (contact type == .room) — never double-listed.
    private func roomResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRooms
            .filter { !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return PathManagementViewModel.filtered(nodes, by: searchText)
    }

    // MARK: - Visibility per filter

    private var showsRecent: Bool { filter == .all || filter == .recent }
    private var showsFavorites: Bool { filter == .all || filter == .favorites }
    private var showsContacts: Bool { filter == .all }
    private var showsDiscovered: Bool { filter == .all || filter == .discovered }
    private var showsRooms: Bool { filter == .all }

    // MARK: - Row + empty state

    @ViewBuilder
    private var emptyResultsView: some View {
        if searchText.isEmpty {
            ContentUnavailableView(
                L10n.Contacts.Contacts.PathEdit.NoRepeaters.title,
                systemImage: "antenna.radiowaves.left.and.right.slash",
                description: Text(L10n.Contacts.Contacts.PathEdit.NoRepeaters.description)
            )
        } else {
            let roomsWouldMatch = filter != .all && viewModel.availableRooms.contains { room in
                PathManagementViewModel.matches(.contact(room), query: searchText)
            }
            ContentUnavailableView {
                Label(
                    L10n.Contacts.Contacts.PathEdit.NoRepeaters.title,
                    systemImage: "magnifyingglass"
                )
            } description: {
                if roomsWouldMatch {
                    Text(L10n.Contacts.Contacts.PathEdit.Search.NoMatches.descriptionWithRoomsHint)
                } else {
                    Text(L10n.Contacts.Contacts.PathEdit.Search.NoMatches.description)
                }
            }
        }
    }

    private var maxHopsReachedView: some View {
        ContentUnavailableView {
            Label(
                L10n.Contacts.Contacts.PathEdit.MaxHops.reached,
                systemImage: "checkmark.circle"
            )
        } description: {
            Text(L10n.Contacts.Contacts.PathEdit.MaxHops.description(viewModel.maxHopCount))
        }
    }
}

private struct PickerRowView: View {
    let node: PickerNode
    let intent: AddHopIntent
    let viewModel: PathManagementViewModel
    @Binding var addHapticTrigger: Int

    @State private var showSuccess = false
    @State private var resetTask: Task<Void, Never>?

    private static let successDuration: Duration = .seconds(1.5)

    var body: some View {
        Button(action: handleTap) {
            HStack(spacing: PathEditMetrics.rowContentSpacing) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: PathEditMetrics.badgeSpacing) {
                        Text(node.displayName)
                            .font(.body)
                        if node.isFavorite {
                            Image(systemName: "star.fill")
                                .font(.caption)
                                .foregroundStyle(.yellow)
                                .accessibilityHidden(true)
                        }
                        if node.isDiscovered {
                            NodeKindBadge(
                                text: L10n.Contacts.Contacts.NodeKind.discovered,
                                color: .blue
                            )
                        }
                        if node.isRoom {
                            NodeKindBadge(
                                text: L10n.Contacts.Contacts.NodeKind.room,
                                color: .orange
                            )
                        }
                    }
                    Text(node.publicKeyHex)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
                trailingIcon
            }
            .frame(minHeight: PathEditMetrics.tapTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(rowAccessibilityLabel)
    }

    @ViewBuilder
    private var trailingIcon: some View {
        if showSuccess {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .transition(.scale.combined(with: .opacity))
        } else {
            Image(systemName: "plus.circle")
                .foregroundStyle(.tint)
                .transition(.opacity)
        }
    }

    private func handleTap() {
        guard !viewModel.isPathFull else { return }
        addHapticTrigger += 1
        viewModel.insert(node.underlying, at: intent)
        let updatedBanner = AddHopPickerView.bannerText(for: viewModel, intent: intent)
        AccessibilityNotification.Announcement(updatedBanner).post()
        resetTask?.cancel()
        resetTask = Task {
            withAnimation { showSuccess = true }
            try? await Task.sleep(for: Self.successDuration)
            if !Task.isCancelled {
                withAnimation { showSuccess = false }
            }
        }
    }

    private var rowAccessibilityLabel: String {
        L10n.Contacts.Contacts.PathEdit.addToPathAsHop(node.displayName, viewModel.editablePath.count + 1)
    }
}
