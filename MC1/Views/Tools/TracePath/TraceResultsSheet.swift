import SwiftUI
import MC1Services

/// Full-screen sheet displaying trace results
struct TraceResultsSheet: View {
    let result: TraceResult
    @Bindable var viewModel: TracePathViewModel
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    // Save dialog state
    @State private var saveHapticTrigger = 0
    @State private var copyHapticTrigger = 0
    @State private var showingDistanceInfo = false

    var body: some View {
        NavigationStack {
            List {
                TraceResultsSectionView(
                    result: result,
                    viewModel: viewModel,
                    saveHapticTrigger: $saveHapticTrigger,
                    showingDistanceInfo: $showingDistanceInfo
                )
                roundTripPathSection
                    .themedRowBackground(theme)
            }
            .themedCanvas(theme)
            .navigationDestination(for: TracePathRoute.self) { route in
                switch route {
                case .savedPathDetail(let savedPath):
                    SavedPathDetailView(savedPath: savedPath)
                }
            }
            .navigationTitle(L10n.Contacts.Contacts.Results.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.Contacts.Contacts.Results.dismiss, systemImage: "xmark") {
                        dismiss()
                    }
                }
            }
            .sensoryFeedback(.success, trigger: saveHapticTrigger)
            .sensoryFeedback(.success, trigger: copyHapticTrigger)
        }
    }

    // MARK: - Outbound Path Section

    private var roundTripPathSection: some View {
        Section {
            HStack {
                Text(result.tracedPathString)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)

                Spacer()

                Button(L10n.Contacts.Contacts.Trace.List.copyPath, systemImage: "doc.on.doc") {
                    copyHapticTrigger += 1
                    UIPasteboard.general.string = result.tracedPathString
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
            }
        } header: {
            Text(L10n.Contacts.Contacts.Trace.List.roundTripPath)
        }
    }
}
