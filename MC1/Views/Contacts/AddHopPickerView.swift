import Accessibility
import SwiftUI
import MC1Services
import UIKit

/// Shared full-screen Add-Hop picker, pushed via `.navigationDestination(item:)`
/// from both the contact path editor (`PathEditingSheet`) and the trace path
/// builder (`TracePathListView`). Finds a node fast via name substring or hex
/// prefix; a comma in the search field switches to bulk code entry.
struct AddHopPickerView: View {
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    let viewModel: any HopPickerSource
    let intent: AddHopIntent
    /// Set when the picker is presented modally (a sheet) rather than pushed onto a
    /// navigation stack, so it surfaces its own dismiss control in place of the
    /// system back button. See `addHopPicker(for:source:)`.
    var presentsOwnDismiss = false

    @State private var searchText = ""
    @State private var filter: AddHopFilter = .all
    @State private var addHapticTrigger = 0

    /// Recent keys frozen at presentation. Tapping a row records the node into the
    /// view model's live recents (for the next time the picker opens), but the
    /// section partitioning reads this snapshot so a just-added row keeps its place
    /// instead of jumping into the Recent section mid-interaction.
    @State private var sessionRecentKeys: [Data]

    init(viewModel: any HopPickerSource, intent: AddHopIntent, presentsOwnDismiss: Bool = false) {
        self.viewModel = viewModel
        self.intent = intent
        self.presentsOwnDismiss = presentsOwnDismiss
        _sessionRecentKeys = State(initialValue: viewModel.recentPublicKeys)
    }

    /// A comma in the query switches the picker to bulk code entry.
    private var isBulkMode: Bool { searchText.contains(",") }

    var body: some View {
        List {
            if viewModel.isPathFull {
                Section {
                    maxHopsReachedView
                        .listRowBackground(Color.clear)
                }
            } else if isBulkMode {
                bulkAddContent
            } else {
                resultsContent
            }
        }
        .listStyle(.insetGrouped)
        .themedCanvas(theme)
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
        .toolbar {
            if presentsOwnDismiss {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Contacts.Contacts.Common.done) { dismiss() }
                }
            }
            ToolbarItem(placement: .primaryAction) {
                // Pastes clipboard text into the search field; a comma in the
                // pasted value switches the picker to bulk code entry.
                Button(L10n.Contacts.Contacts.PathEdit.paste, systemImage: "doc.on.clipboard") {
                    guard let pasted = UIPasteboard.general.string?
                        .trimmingCharacters(in: .whitespacesAndNewlines),
                          !pasted.isEmpty else { return }
                    searchText = pasted
                }
            }
        }
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
            .themedRowBackground(theme)
        }
    }

    // MARK: - Bulk add

    /// Bulk code entry: parse the comma-separated query, preview per-code validity,
    /// and add every resolvable code on tap, then clear the search.
    @ViewBuilder
    private var bulkAddContent: some View {
        let classifications = viewModel.classifyCodes(searchText)
        let addableCodes = classifications.filter(\.willBeAdded).map(\.code)
        Section {
            ForEach(classifications) { classification in
                BulkCodeRow(classification: classification)
            }
        }
        .themedRowBackground(theme)
        Section {
            Button {
                let result = viewModel.addCodes(searchText)
                if !result.added.isEmpty {
                    addHapticTrigger += 1
                    AccessibilityNotification.Announcement(bannerText).post()
                }
                searchText = ""
            } label: {
                Text(addableCodes.isEmpty
                    ? L10n.Contacts.Contacts.PathEdit.BulkAdd.empty
                    : L10n.Contacts.Contacts.PathEdit.BulkAdd.action(addableCodes.joined(separator: ", ")))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(addableCodes.isEmpty)
            .listRowBackground(Color.clear)
        }
    }

    // MARK: - Subtitle text

    private var bannerText: String {
        Self.bannerText(for: viewModel, intent: intent)
    }

    /// Shared text source so the navigation subtitle and the row-tap
    /// announcement (posted from `PickerRowView.handleTap`) read identically.
    @MainActor
    static func bannerText(for viewModel: any HopPickerSource, intent: AddHopIntent) -> String {
        if viewModel.isPathFull {
            return L10n.Contacts.Contacts.PathEdit.MaxHops.reached
        }
        switch intent {
        case .append:
            return L10n.Contacts.Contacts.PathEdit.positionAppend(viewModel.currentHopCount + 1)
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
        // Cross-section dedup only matters in `.all`, where every section is visible
        // at once. A single-section filter shows nothing else, so excluding recent or
        // contact keys there would hide a node that has no other section to appear in.
        let isUnfiltered = filter == .all
        let recentKeys = isUnfiltered ? Set(sessionRecentKeys) : []
        let contactKeys = isUnfiltered ? Set(viewModel.availableRepeaters.map(\.publicKey)) : []
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
        let resolved = sessionRecentKeys.compactMap { pubkey -> PickerNode? in
            if let contact = viewModel.availableRepeaters.first(where: { $0.publicKey == pubkey }) {
                return .contact(contact)
            }
            if let discovered = viewModel.discoveredRepeaters.first(where: { $0.publicKey == pubkey }) {
                return .discovered(discovered)
            }
            return nil
        }
        return HopNodeMatching.filtered(resolved, by: searchText)
    }

    /// Favorite contacts minus anything already in Recent.
    private func favoriteResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRepeaters
            .filter { $0.isFavorite && !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return HopNodeMatching.filtered(nodes, by: searchText)
    }

    /// Non-favorite contact repeaters minus Recent.
    private func contactResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRepeaters
            .filter { !$0.isFavorite && !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return HopNodeMatching.filtered(nodes, by: searchText)
    }

    /// Discovered repeaters minus any pubkey already present as a contact and
    /// anything in Recent.
    private func discoveredResults(recentKeys: Set<Data>, contactKeys: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.discoveredRepeaters
            .filter { !contactKeys.contains($0.publicKey) && !recentKeys.contains($0.publicKey) }
            .sorted { $0.resolvableName.localizedCaseInsensitiveCompare($1.resolvableName) == .orderedAscending }
            .map { PickerNode.discovered($0) }
        return HopNodeMatching.filtered(nodes, by: searchText)
    }

    /// Rooms (contact type == .room) — never double-listed.
    private func roomResults(excluding keySet: Set<Data>) -> [PickerNode] {
        let nodes = viewModel.availableRooms
            .filter { !keySet.contains($0.publicKey) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { PickerNode.contact($0) }
        return HopNodeMatching.filtered(nodes, by: searchText)
    }

    // MARK: - Visibility per filter

    private var showsRecent: Bool { filter == .all || filter == .recent }
    private var showsFavorites: Bool { filter == .all || filter == .favorites }
    private var showsContacts: Bool { filter == .all }
    private var showsDiscovered: Bool { filter == .all || filter == .discovered }
    private var showsRooms: Bool { filter == .all }

    // MARK: - Row + empty state

    /// Empty-state copy for the active filter. The curated subset filters get
    /// their own wording; `.all` and `.discovered` share the generic discovery
    /// copy, which is accurate for both.
    private var emptyStateTitle: String {
        switch filter {
        case .favorites:        L10n.Contacts.Contacts.PathEdit.NoFavorites.title
        case .recent:           L10n.Contacts.Contacts.PathEdit.NoRecent.title
        case .all, .discovered: L10n.Contacts.Contacts.PathEdit.NoRepeaters.title
        }
    }

    private var emptyStateDescription: String {
        switch filter {
        case .favorites:        L10n.Contacts.Contacts.PathEdit.NoFavorites.description
        case .recent:           L10n.Contacts.Contacts.PathEdit.NoRecent.description
        case .all, .discovered: L10n.Contacts.Contacts.PathEdit.NoRepeaters.description
        }
    }

    @ViewBuilder
    private var emptyResultsView: some View {
        if searchText.isEmpty {
            ContentUnavailableView(
                emptyStateTitle,
                systemImage: "antenna.radiowaves.left.and.right.slash",
                description: Text(emptyStateDescription)
            )
        } else {
            let roomsWouldMatch = filter != .all && viewModel.availableRooms.contains { room in
                HopNodeMatching.matches(.contact(room), query: searchText)
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
            Text(L10n.Contacts.Contacts.PathEdit.MaxHops.description(viewModel.hopLimit ?? 0))
        }
    }
}

// MARK: - Picker row

private struct PickerRowView: View {
    let node: PickerNode
    let intent: AddHopIntent
    let viewModel: any HopPickerSource
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
        viewModel.appendHop(node.underlying)
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
        L10n.Contacts.Contacts.PathEdit.addToPathAsHop(node.displayName, viewModel.currentHopCount + 1)
    }
}

// MARK: - Bulk code row

/// One parsed code in the bulk-add preview, showing the per-code outcome.
private struct BulkCodeRow: View {
    let classification: HopCodeClassification

    var body: some View {
        HStack(spacing: PathEditMetrics.rowContentSpacing) {
            Text(rowText)
                .font(.callout)
            Spacer()
            trailingIcon
        }
        .frame(minHeight: PathEditMetrics.tapTarget)
        .accessibilityElement(children: .combine)
    }

    /// Only problem statuses get a glyph. Rows that will be added rely on the
    /// single "Add codes" button, so they show no add-like affordance.
    @ViewBuilder
    private var trailingIcon: some View {
        if let icon = iconName, let tint = iconTint {
            Image(systemName: icon)
                .foregroundStyle(tint)
        }
    }

    private var rowText: String {
        let code = classification.code
        switch classification.status {
        case .willAdd:       return L10n.Contacts.Contacts.PathEdit.BulkAdd.willAdd(code)
        case .alreadyInPath: return L10n.Contacts.Contacts.CodeInput.Error.alreadyInPath(code)
        case .notFound:      return L10n.Contacts.Contacts.CodeInput.Error.notFound(code)
        case .invalidFormat: return L10n.Contacts.Contacts.CodeInput.Error.invalidFormat(code)
        case .pathFull:      return L10n.Contacts.Contacts.PathEdit.BulkAdd.pathFull(code)
        }
    }

    private var iconName: String? {
        switch classification.status {
        case .willAdd:       return nil
        case .alreadyInPath: return "checkmark.circle.fill"
        case .notFound:      return "questionmark.circle"
        case .invalidFormat: return "exclamationmark.triangle.fill"
        case .pathFull:      return "nosign"
        }
    }

    private var iconTint: Color? {
        switch classification.status {
        case .willAdd:       return nil
        case .alreadyInPath: return .secondary
        case .notFound:      return .orange
        case .invalidFormat: return .red
        case .pathFull:      return .secondary
        }
    }
}
