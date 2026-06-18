import SwiftUI
import MC1Services

/// Floating action buttons for trace path map (clear, run trace, view results)
struct TracePathFloatingButtonsView: View {
    var mapViewModel: TracePathMapViewModel
    @Binding var showingClearConfirmation: Bool
    @Binding var presentedResult: TraceResult?
    var buttonNamespace: Namespace.ID

    var body: some View {
        VStack {
            Spacer()

            LiquidGlassContainer(spacing: 12) {
                HStack(spacing: 12) {
                    if mapViewModel.hasPath {
                        // Clear button
                        Button {
                            showingClearConfirmation = true
                        } label: {
                            Text(L10n.Contacts.Contacts.Trace.Map.clear)
                        }
                        .liquidGlassButtonStyle()
                        .liquidGlassID("clear", in: buttonNamespace)
                        .confirmationDialog(
                            L10n.Contacts.Contacts.Trace.clearPath,
                            isPresented: $showingClearConfirmation,
                            titleVisibility: .visible
                        ) {
                            Button(L10n.Contacts.Contacts.Trace.clearPath, role: .destructive) {
                                mapViewModel.clearPath()
                            }
                        } message: {
                            Text(L10n.Contacts.Contacts.Trace.clearPathMessage)
                        }

                        // Run Trace button
                        Button {
                            Task {
                                await mapViewModel.runTrace()
                            }
                        } label: {
                            if mapViewModel.isRunning {
                                HStack {
                                    ProgressView()
                                        .controlSize(.small)
                                    Text(L10n.Contacts.Contacts.Trace.List.runningTrace)
                                }
                            } else {
                                Text(L10n.Contacts.Contacts.Trace.List.runTrace)
                            }
                        }
                        .liquidGlassProminentButtonStyle()
                        .liquidGlassID("trace", in: buttonNamespace)
                        .disabled(!mapViewModel.canRunTrace)

                        // View Results button
                        if let result = mapViewModel.result, result.success {
                            Button {
                                presentedResult = result
                            } label: {
                                Text(L10n.Contacts.Contacts.Trace.Map.viewResults)
                            }
                            .liquidGlassButtonStyle()
                            .liquidGlassID("viewResults", in: buttonNamespace)
                        }
                    }
                }
            }
            .animation(.spring(response: 0.3), value: mapViewModel.hasPath)
            .animation(.spring(response: 0.3), value: mapViewModel.result?.id)
            .padding(.bottom, 24)
        }
    }
}
