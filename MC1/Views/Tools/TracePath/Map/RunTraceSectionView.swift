import SwiftUI
import MC1Services

/// Section with the run trace button and running state indicator
struct RunTraceSectionView: View {
    @Environment(\.appState) private var appState
    var viewModel: TracePathViewModel
    @Binding var showJumpToPath: Bool

    var body: some View {
        Section {
            HStack {
                Spacer()
                if viewModel.isRunning {
                    HStack(spacing: 8) {
                        ProgressView()
                            .controlSize(.small)
                        if viewModel.batchEnabled {
                            Text(L10n.Contacts.Contacts.Trace.List.runningBatch(viewModel.currentTraceIndex, viewModel.batchSize))
                        } else {
                            Text(L10n.Contacts.Contacts.Trace.List.runningTrace)
                        }
                    }
                    .frame(minWidth: 160)
                    .padding(.vertical, 12)
                    .padding(.horizontal, 16)
                    .background(.regularMaterial, in: .capsule)
                    .overlay {
                        Capsule()
                            .strokeBorder(Color.secondary.opacity(0.3), lineWidth: 1)
                    }
                    .accessibilityLabel(viewModel.batchEnabled
                        ? L10n.Contacts.Contacts.Trace.List.runningBatchLabel(viewModel.currentTraceIndex, viewModel.batchSize)
                        : L10n.Contacts.Contacts.Trace.List.runningLabel)
                    .accessibilityHint(L10n.Contacts.Contacts.Trace.List.runningHint)
                } else {
                    Button {
                        Task {
                            if viewModel.batchEnabled {
                                await viewModel.runBatchTrace()
                            } else {
                                await viewModel.runTrace()
                            }
                        }
                    } label: {
                        Text(L10n.Contacts.Contacts.Trace.List.runTrace)
                            .frame(minWidth: 160)
                            .padding(.vertical, 4)
                    }
                    .liquidGlassProminentButtonStyle()
                    .radioDisabled(for: appState.connectionState, or: !viewModel.canRunTraceWhenConnected)
                    .accessibilityLabel(L10n.Contacts.Contacts.Trace.List.runTraceLabel)
                    .accessibilityHint(viewModel.batchEnabled
                        ? L10n.Contacts.Contacts.Trace.List.batchHint(viewModel.batchSize)
                        : L10n.Contacts.Contacts.Trace.List.singleHint)
                }
                Spacer()
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .id("runTrace")
            .onAppear {
                withAnimation(.easeInOut(duration: 0.2)) {
                    showJumpToPath = false
                }
            }
            .onDisappear {
                withAnimation(.easeInOut(duration: 0.2)) {
                    showJumpToPath = true
                }
            }
        }
        .listSectionSeparator(.hidden)
    }
}
