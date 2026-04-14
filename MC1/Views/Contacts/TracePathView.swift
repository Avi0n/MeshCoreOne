import SwiftUI
import MC1Services

/// View mode for trace path building
enum TracePathViewMode: String, CaseIterable {
    case list
    case map

    var label: String {
        switch self {
        case .list: L10n.Contacts.Contacts.Trace.Mode.list
        case .map: L10n.Contacts.Contacts.Trace.Mode.map
        }
    }
}

/// View for building and executing network path traces
struct TracePathView: View {
    @Environment(\.appState) private var appState
    @State private var viewModel = TracePathViewModel()

    // Haptic feedback triggers
    @State private var addHapticTrigger = 0
    @State private var dragHapticTrigger = 0
    @State private var copyHapticTrigger = 0
    @State private var jumpHapticTrigger = 0

    // Row feedback
    @State private var recentlyAddedRepeaterID: UUID?

    @State private var showingSavedPaths = false
    @State private var presentedResult: TraceResult?
    @State private var showingClearConfirmation = false

    @State private var showJumpToPath = true
    @State private var pathLoadedFromSheet = false
    @AppStorage("tracePathViewMode") private var viewMode: TracePathViewMode = .list

    var body: some View {
        Group {
            switch viewMode {
            case .list:
                listView
            case .map:
                TracePathMapView(traceViewModel: viewModel, presentedResult: $presentedResult)
            }
        }
        .navigationTitle(L10n.Contacts.Contacts.Trace.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(L10n.Contacts.Contacts.Trace.viewMode, selection: $viewMode) {
                    ForEach(TracePathViewMode.allCases, id: \.self) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
            ToolbarItem(placement: .primaryAction) {
                Button(L10n.Contacts.Contacts.Trace.saved, systemImage: "bookmark") {
                    showingSavedPaths = true
                }
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: addHapticTrigger)
        .sensoryFeedback(.impact(weight: .light), trigger: dragHapticTrigger)
        .sensoryFeedback(.success, trigger: copyHapticTrigger)
        .sensoryFeedback(.error, trigger: viewModel.errorHapticTrigger)
        .sheet(isPresented: $showingSavedPaths) {
            SavedPathsSheet { selectedPath in
                viewModel.loadSavedPath(selectedPath)
                pathLoadedFromSheet = true
            } onDelete: { deletedPathId in
                viewModel.handleSavedPathDeleted(id: deletedPathId)
            }
        }
        .onChange(of: viewModel.resultID) { _, newID in
            guard newID != nil else { return }
            if let result = viewModel.result, result.success {
                if viewMode == .list {
                    presentedResult = result
                }
            }
        }
        .sheet(item: $presentedResult, onDismiss: {
            if viewModel.isBatchInProgress {
                viewModel.cancelBatchTrace()
            }
        }) { result in
            TraceResultsSheet(result: result, viewModel: viewModel)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            L10n.Contacts.Contacts.Trace.clearPath,
            isPresented: $showingClearConfirmation,
            titleVisibility: .visible
        ) {
            Button(L10n.Contacts.Contacts.Trace.clearPath, role: .destructive) {
                viewModel.clearPath()
            }
        } message: {
            Text(L10n.Contacts.Contacts.Trace.clearPathMessage)
        }
        .alert(L10n.Contacts.Contacts.Trace.failed, isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button(L10n.Contacts.Contacts.Common.ok) {
                viewModel.clearError()
            }
        } message: {
            if let error = viewModel.errorMessage {
                Text(error)
            }
        }
        .task {
            viewModel.configure(appState: appState)
            viewModel.startListening()
            if let radioID = appState.connectedDevice?.radioID {
                await viewModel.loadContacts(radioID: radioID)
            }
        }
        .onDisappear {
            viewModel.stopListening()
        }
        .task(id: recentlyAddedRepeaterID) {
            guard recentlyAddedRepeaterID != nil else { return }
            try? await Task.sleep(for: .seconds(1))
            if !Task.isCancelled {
                recentlyAddedRepeaterID = nil
            }
        }
    }

    @ViewBuilder
    private var listView: some View {
        ScrollViewReader { proxy in
            TracePathListView(
                viewModel: viewModel,
                addHapticTrigger: $addHapticTrigger,
                dragHapticTrigger: $dragHapticTrigger,
                copyHapticTrigger: $copyHapticTrigger,
                recentlyAddedRepeaterID: $recentlyAddedRepeaterID,
                showingClearConfirmation: $showingClearConfirmation,
                presentedResult: $presentedResult,
                showJumpToPath: $showJumpToPath
            )
            .scrollDismissesKeyboard(.interactively)
            .overlay(alignment: .bottom) {
                jumpToPathButton(proxy: proxy)
            }
            .onChange(of: showingSavedPaths) { wasShowing, isShowing in
                if wasShowing && !isShowing && pathLoadedFromSheet {
                    pathLoadedFromSheet = false
                    withAnimation {
                        proxy.scrollTo("runTrace", anchor: .bottom)
                    }
                }
            }
        }
    }

    // MARK: - Jump to Path Button

    @ViewBuilder
    private func jumpToPathButton(proxy: ScrollViewProxy) -> some View {
        JumpToPathButton(isVisible: showJumpToPath) {
            jumpHapticTrigger += 1
            withAnimation {
                showJumpToPath = false
                proxy.scrollTo("runTrace", anchor: .bottom)
            }
        }
        .padding(.bottom)
        .sensoryFeedback(.selection, trigger: jumpHapticTrigger)
    }
}

// MARK: - Jump to Path Button

/// Floating pill button to scroll to the Run Trace button
private struct JumpToPathButton: View {
    let isVisible: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Label(L10n.Contacts.Contacts.Trace.runBelow, systemImage: "arrow.down")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.accentColor, in: .capsule)
                .liquidGlass(in: .capsule)
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
        .opacity(isVisible ? 1 : 0)
        .scaleEffect(isVisible ? 1 : 0.8)
        .allowsHitTesting(isVisible)
        .animation(.snappy(duration: 0.2), value: isVisible)
        .accessibilityLabel(L10n.Contacts.Contacts.Trace.jumpLabel)
        .accessibilityHint(L10n.Contacts.Contacts.Trace.jumpHint)
        .accessibilityHidden(!isVisible)
    }
}
