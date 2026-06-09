import MC1Services
import SwiftUI

/// Sheet for editing a contact's routing path. Two surfaces:
/// 1. Main screen (this view) — ordered hops, primary CTA, empty-state actions
/// 2. Add Hop picker — pushed via navigationDestination(item:) (see `AddHopPickerView`)
struct PathEditingSheet: View {
    /// Firmware path-length sentinel: 0x00 = direct routing (no repeaters).
    /// Distinct from the flood sentinel 0xFF and from `encodePathLen` output,
    /// which only uses values where the top two bits encode the hash mode.
    private static let directRoutingPathLength: UInt8 = 0x00

    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: PathManagementViewModel
    let contact: ContactDTO

    @State private var dragHapticTrigger = 0
    @State private var deleteHapticTrigger = 0
    @State private var saveCompletedToken = 0
    @State private var routingConfirmedToken = 0

    @State private var showingDirectConfirmation = false
    @State private var showingFloodConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                headerSection
                    .themedRowBackground(theme)
                if viewModel.editablePath.isEmpty {
                    emptyStateSection
                } else {
                    currentPathSection
                        .themedRowBackground(theme)
                    addHopCtaSection
                }
            }
            .themedCanvas(theme)
            .navigationTitle(L10n.Contacts.Contacts.PathEdit.title)
            .navigationBarTitleDisplayMode(.inline)
            .environment(\.editMode, .constant(.active))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Contacts.Contacts.Common.cancel) { dismiss() }
                }
                if !viewModel.editablePath.isEmpty {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.Contacts.Contacts.Common.save) {
                            Task {
                                await viewModel.saveEditedPath(for: contact)
                                guard viewModel.errorMessage == nil else { return }
                                saveCompletedToken += 1
                                dismiss()
                            }
                        }
                    }
                }
            }
            .addHopPicker(for: $viewModel.insertionIntent, source: viewModel)
            .sensoryFeedback(.impact(weight: .light), trigger: dragHapticTrigger)
            .sensoryFeedback(.impact(weight: .medium), trigger: deleteHapticTrigger)
            .sensoryFeedback(.success, trigger: saveCompletedToken)
            .sensoryFeedback(.selection, trigger: routingConfirmedToken)
            .alert(
                L10n.Contacts.Contacts.PathEdit.DirectRouting.Confirm.title,
                isPresented: $showingDirectConfirmation
            ) {
                Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
                Button(L10n.Contacts.Contacts.PathEdit.DirectRouting.Confirm.confirm, role: .destructive) {
                    Task {
                        await viewModel.setPath(
                            for: contact,
                            path: Data(),
                            pathLength: Self.directRoutingPathLength
                        )
                        guard viewModel.errorMessage == nil else { return }
                        routingConfirmedToken += 1
                        dismiss()
                    }
                }
            } message: {
                Text(L10n.Contacts.Contacts.PathEdit.DirectRouting.Confirm.message(
                    contact.displayName,
                    contact.displayName
                ))
            }
            .alert(
                L10n.Contacts.Contacts.PathEdit.FloodRouting.Confirm.title,
                isPresented: $showingFloodConfirmation
            ) {
                Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
                Button(L10n.Contacts.Contacts.PathEdit.FloodRouting.Confirm.confirm, role: .destructive) {
                    Task {
                        await viewModel.resetPath(for: contact)
                        guard viewModel.errorMessage == nil else { return }
                        routingConfirmedToken += 1
                        dismiss()
                    }
                }
            } message: {
                Text(L10n.Contacts.Contacts.PathEdit.FloodRouting.Confirm.message(
                    contact.displayName
                ))
            }
        }
        .presentationDragIndicator(.visible)
        .presentationSizing(.page)
    }

    // MARK: - Sections

    private var headerSection: some View {
        Section {
            Text(L10n.Contacts.Contacts.PathEdit.description(contact.displayName))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    /// Ordered hops with drag-to-reorder and swipe-to-delete.
    /// Uses `.onDelete` (not `.swipeActions`) because the list is in
    /// `.editMode == .active` for the always-visible drag handles, and
    /// active edit mode suppresses `.swipeActions`. `.onDelete` provides
    /// both the leading minus-circle and the trailing swipe gesture.
    private var currentPathSection: some View {
        Section {
            ForEach(Array(viewModel.editablePath.enumerated()), id: \.element.id) { index, hop in
                PathHopRow(
                    hop: hop,
                    index: index,
                    totalCount: viewModel.editablePath.count
                )
            }
            .onMove { source, destination in
                dragHapticTrigger += 1
                viewModel.moveRepeater(from: source, to: destination)
            }
            .onDelete { indexSet in
                deleteHapticTrigger += 1
                for index in indexSet.sorted().reversed() {
                    viewModel.removeRepeater(at: index)
                }
            }
        } header: {
            Text(L10n.Contacts.Contacts.PathEdit.currentPath)
        } footer: {
            Text(L10n.Contacts.Contacts.PathEdit.reorderHint)
        }
    }

    private var addHopCtaSection: some View {
        Section {
            Button {
                viewModel.insertionIntent = .append
            } label: {
                PathEditCTALabel(
                    title: viewModel.isPathFull
                        ? L10n.Contacts.Contacts.PathEdit.MaxHops.reached
                        : L10n.Contacts.Contacts.PathEdit.addHop,
                    systemImage: viewModel.isPathFull ? "checkmark.circle" : "plus.circle.fill"
                )
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(viewModel.isPathFull)
            .listRowInsets(PathEditMetrics.ctaRowInsets)
            .listRowBackground(Color.clear)
        } footer: {
            if viewModel.isPathFull {
                Text(L10n.Contacts.Contacts.PathEdit.MaxHops.footer(viewModel.maxHopCount))
            }
        }
    }

    @ViewBuilder
    private var emptyStateSection: some View {
        Section {
            ContentUnavailableView {
                Label(
                    L10n.Contacts.Contacts.PathEdit.Empty.title,
                    systemImage: "antenna.radiowaves.left.and.right.slash"
                )
            } description: {
                Text(L10n.Contacts.Contacts.PathEdit.Empty.description(contact.displayName))
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
        }

        Section {
            Button {
                viewModel.insertionIntent = .append
            } label: {
                PathEditCTALabel(
                    title: L10n.Contacts.Contacts.PathEdit.addHop,
                    systemImage: "plus.circle.fill"
                )
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .listRowInsets(PathEditMetrics.ctaRowInsets)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            Button {
                showingDirectConfirmation = true
            } label: {
                PathEditCTALabel(
                    title: L10n.Contacts.Contacts.PathEdit.useDirectRouting,
                    systemImage: "person.wave.2"
                )
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .listRowInsets(PathEditMetrics.ctaRowInsets)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            Button {
                showingFloodConfirmation = true
            } label: {
                PathEditCTALabel(
                    title: L10n.Contacts.Contacts.PathEdit.useFloodRouting,
                    systemImage: "dot.radiowaves.left.and.right"
                )
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .listRowInsets(PathEditMetrics.ctaRowInsets)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }
}

// MARK: - Row views

/// Row displaying a single hop in the path with an index capsule.
private struct PathHopRow: View {
    let hop: PathHop
    let index: Int
    let totalCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let name = hop.resolvedName {
                Text(name).font(.body)
                Text(hop.hashHex)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            } else {
                Text(hop.hashHex).font(.body.monospaced())
            }
        }
        .frame(minHeight: PathEditMetrics.tapTarget)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityDescription)
        .accessibilityHint(L10n.Contacts.Contacts.PathEdit.hopHint)
    }

    private var accessibilityDescription: String {
        if let name = hop.resolvedName {
            return L10n.Contacts.Contacts.PathEdit.hopWithName(index + 1, totalCount, name)
        } else {
            return L10n.Contacts.Contacts.PathEdit.hopWithHex(index + 1, totalCount, hop.hashHex)
        }
    }
}
