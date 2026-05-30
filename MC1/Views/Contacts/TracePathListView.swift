import SwiftUI
import UIKit
import MC1Services

/// List-based view for building trace paths
struct TracePathListView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Bindable var viewModel: TracePathViewModel

    @Binding var addHapticTrigger: Int
    @Binding var dragHapticTrigger: Int
    @Binding var copyHapticTrigger: Int
    @Binding var recentlyAddedRepeaterID: UUID?
    @Binding var showingClearConfirmation: Bool
    @Binding var presentedResult: TraceResult?
    @Binding var showJumpToPath: Bool

    @State private var codeInput = ""
    @State private var codeInputError: String?
    @State private var pastedSuccessfully = false

    var body: some View {
        List {
            codeInputSection
                .themedRowBackground(theme)
            AvailableRepeatersSectionView(
                viewModel: viewModel,
                recentlyAddedRepeaterID: $recentlyAddedRepeaterID,
                addHapticTrigger: $addHapticTrigger
            )
            outboundPathSection
                .themedRowBackground(theme)
            PathActionsSectionView(
                viewModel: viewModel,
                showingClearConfirmation: $showingClearConfirmation,
                copyHapticTrigger: $copyHapticTrigger
            )
            RunTraceSectionView(
                viewModel: viewModel,
                showJumpToPath: $showJumpToPath
            )

            Color.clear
                .frame(height: 1)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
                .id("bottom")
        }
        .themedCanvas(theme)
        .environment(\.editMode, .constant(.active))
    }

    // MARK: - Code Input Section

    private var codeInputSection: some View {
        Section {
            HStack {
                TextField(L10n.Contacts.Contacts.Trace.List.codePlaceholder, text: $codeInput)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .onSubmit {
                        processCodeInput()
                    }

                Button(L10n.Contacts.Contacts.Trace.List.paste, systemImage: "doc.on.clipboard") {
                    pasteAndProcess()
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
            }
        } footer: {
            if let error = codeInputError {
                Text(error)
                    .foregroundStyle(.red)
            } else if !pastedSuccessfully {
                Text(L10n.Contacts.Contacts.Trace.List.codeFooter)
            }
        }
    }

    private func processCodeInput() {
        guard !codeInput.trimmingCharacters(in: .whitespaces).isEmpty else { return }

        pastedSuccessfully = false
        let result = viewModel.addRepeatersFromCodes(codeInput)
        codeInputError = result.errorMessage

        if !result.added.isEmpty {
            addHapticTrigger += 1
        }
    }

    private func pasteAndProcess() {
        guard let pasteboardString = UIPasteboard.general.string,
              !pasteboardString.isEmpty else { return }

        codeInput = pasteboardString
        let result = viewModel.addRepeatersFromCodes(codeInput)
        codeInputError = result.errorMessage
        pastedSuccessfully = !result.added.isEmpty

        if !result.added.isEmpty {
            addHapticTrigger += 1
        }
    }

    // MARK: - Outbound Path Section

    private var outboundPathSection: some View {
        Section {
            if viewModel.outboundPath.isEmpty {
                Text(L10n.Contacts.Contacts.Trace.List.emptyPath)
                    .foregroundStyle(.secondary)
                    .frame(minHeight: 44)
            } else {
                ForEach(Array(viewModel.outboundPath.enumerated()), id: \.element.id) { index, hop in
                    TracePathHopRow(hop: hop, hopNumber: index + 1)
                }
                .onMove { source, destination in
                    dragHapticTrigger += 1
                    viewModel.moveRepeater(from: source, to: destination)
                }
                .onDelete { indexSet in
                    for index in indexSet.sorted().reversed() {
                        viewModel.removeRepeater(at: index)
                    }
                }
            }
        } header: {
            Text(L10n.Contacts.Contacts.Trace.List.roundTripPath)
        }
    }
}
