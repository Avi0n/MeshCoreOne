import SwiftUI
import MC1Services

/// Section with path configuration toggles, copy, and clear actions
struct PathActionsSectionView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Bindable var viewModel: TracePathViewModel
    @Binding var showingClearConfirmation: Bool
    @Binding var copyHapticTrigger: Int

    var body: some View {
        Section {
            if !viewModel.outboundPath.isEmpty {
                Toggle(isOn: $viewModel.autoReturnPath) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Contacts.Contacts.Trace.List.autoReturn)
                        Text(L10n.Contacts.Contacts.Trace.List.autoReturnDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Toggle(isOn: $viewModel.batchEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Contacts.Contacts.Trace.List.batchTrace)
                        Text(L10n.Contacts.Contacts.Trace.List.batchTraceDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if viewModel.batchEnabled {
                    HStack(spacing: 12) {
                        Text(L10n.Contacts.Contacts.Trace.List.traces)
                            .foregroundStyle(.secondary)
                        Spacer()
                        BatchSizeChip(size: 3, selectedSize: $viewModel.batchSize)
                        BatchSizeChip(size: 5, selectedSize: $viewModel.batchSize)
                        BatchSizeChip(size: 10, selectedSize: $viewModel.batchSize)
                    }
                }

                if appState.connectedDevice?.supportsTraceHashSizeOverride == true {
                    VStack(alignment: .leading, spacing: 4) {
                        Picker(L10n.Contacts.Contacts.Trace.List.hashSize, selection: Binding(
                            get: { viewModel.effectiveTraceMode },
                            set: { viewModel.setTraceHashMode($0) }
                        )) {
                            Text(L10n.Contacts.Contacts.Trace.List.hashSizeOneByte).tag(UInt8(0))
                            Text(L10n.Contacts.Contacts.Trace.List.hashSizeTwoBytes).tag(UInt8(1))
                            Text(L10n.Contacts.Contacts.Trace.List.hashSizeFourBytes).tag(UInt8(2))
                        }
                        .pickerStyle(.menu)
                        .tint(.primary)

                        if viewModel.effectiveTraceMode > 0 {
                            Text(L10n.Contacts.Contacts.Trace.List.hashSizeFooter)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                HStack {
                    Text(viewModel.fullPathString)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)

                    Spacer()

                    Button(L10n.Contacts.Contacts.Trace.List.copyPath, systemImage: "doc.on.doc") {
                        copyHapticTrigger += 1
                        viewModel.copyPathToClipboard()
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                }

                Button(L10n.Contacts.Contacts.Trace.clearPath, systemImage: "trash", role: .destructive) {
                    showingClearConfirmation = true
                }
            }
        } footer: {
            if !viewModel.outboundPath.isEmpty {
                Text(L10n.Contacts.Contacts.Trace.List.rangeWarning)
            }
        }
        .themedRowBackground(theme)
    }
}
