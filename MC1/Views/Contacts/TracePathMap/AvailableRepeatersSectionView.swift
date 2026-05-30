import SwiftUI
import MC1Services

/// Disclosure group section listing available repeaters to add to a trace path
struct AvailableRepeatersSectionView: View {
    @Environment(\.appTheme) private var theme
    var viewModel: TracePathViewModel
    @Binding var recentlyAddedRepeaterID: UUID?
    @Binding var addHapticTrigger: Int

    @State private var isRepeatersExpanded = false
    @AppStorage("tracePathShowOnlyFavorites") private var showOnlyFavorites = false
    @AppStorage("tracePathIncludeRooms") private var includeRooms = false
    @AppStorage("tracePathIncludeDiscovered") private var includeDiscovered = false

    private var filteredNodes: [PickerNode] {
        var nodes: [PickerNode] = viewModel.availableRepeaters.map { .contact($0) }
        if includeRooms {
            nodes += viewModel.availableRooms.map { .contact($0) }
        }
        if includeDiscovered {
            let contactKeys = Set(nodes.compactMap {
                if case .contact(let c) = $0 { c.publicKey } else { nil }
            })
            nodes += viewModel.discoveredRepeaters
                .filter { !contactKeys.contains($0.publicKey) }
                .map { .discovered($0) }
        }
        if showOnlyFavorites {
            nodes = nodes.filter {
                switch $0 {
                case .contact(let c): c.isFavorite
                case .discovered: false
                }
            }
        }
        return nodes
    }

    var body: some View {
        let nodes = filteredNodes
        Section {
            DisclosureGroup(isExpanded: $isRepeatersExpanded) {
                Toggle(L10n.Contacts.Contacts.Trace.List.favoritesOnly, isOn: $showOnlyFavorites)
                Toggle(L10n.Contacts.Contacts.Trace.List.includeRooms, isOn: $includeRooms)
                if !showOnlyFavorites {
                    Toggle(L10n.Contacts.Contacts.Trace.List.includeDiscovered, isOn: $includeDiscovered)
                }

                if nodes.isEmpty {
                    if showOnlyFavorites {
                        ContentUnavailableView(
                            L10n.Contacts.Contacts.Trace.List.NoFavorites.title,
                            systemImage: "star.slash",
                            description: Text(L10n.Contacts.Contacts.Trace.List.NoFavorites.description)
                        )
                    } else {
                        ContentUnavailableView(
                            L10n.Contacts.Contacts.PathEdit.NoRepeaters.title,
                            systemImage: "antenna.radiowaves.left.and.right.slash",
                            description: Text(L10n.Contacts.Contacts.PathEdit.NoRepeaters.description)
                        )
                    }
                } else {
                    ForEach(nodes) { node in
                        Button {
                            recentlyAddedRepeaterID = node.id
                            addHapticTrigger += 1
                            viewModel.addNode(node.underlying)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    HStack {
                                        Text(node.displayName)
                                        if node.isRoom {
                                            NodeKindBadge(text: L10n.Contacts.Contacts.NodeKind.room, color: .orange)
                                        }
                                        if node.isDiscovered {
                                            NodeKindBadge(text: L10n.Contacts.Contacts.NodeKind.discovered, color: .blue)
                                        }
                                    }
                                    Text(node.publicKeyHex)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                                Spacer()
                                Image(systemName: recentlyAddedRepeaterID == node.id ? "checkmark.circle.fill" : "plus.circle")
                                    .foregroundStyle(recentlyAddedRepeaterID == node.id ? Color.green : Color.accentColor)
                                    .contentTransition(.symbolEffect(.replace))
                            }
                        }
                        .id(node.id)
                        .foregroundStyle(.primary)
                        .accessibilityLabel(L10n.Contacts.Contacts.PathEdit.addToPathAsHop(node.displayName, viewModel.outboundPath.count + 1))
                    }
                }
            } label: {
                HStack {
                    Text(L10n.Contacts.Contacts.Trace.List.repeaters)
                    Spacer()
                    Text("\(nodes.count)")
                        .foregroundStyle(.secondary)
                }
            }
            .onChange(of: showOnlyFavorites) { _, newValue in
                if newValue {
                    includeDiscovered = false
                }
            }
        }
        .themedRowBackground(theme)
    }
}
