import SwiftUI
import MC1Services

struct BackupRestoreView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var viewModel: AppBackupViewModel
    @State private var showExportConfirmation = false
    @State private var showFileImporter = false

    init(
        connectionManager: ConnectionManager,
        onImportRestoredData: (@MainActor () -> Void)? = nil,
        onChannelDraftSlotsAffected: (@MainActor ([UUID: Set<UInt8>]) -> Void)? = nil
    ) {
        _viewModel = State(initialValue: AppBackupViewModel(
            connectionManager: connectionManager,
            onImportRestoredData: onImportRestoredData,
            onChannelDraftSlotsAffected: onChannelDraftSlotsAffected
        ))
    }

    var body: some View {
        List {
            Section {
                exportRow
                importRow
            } header: {
                Text(L10n.Settings.Settings.Backup.FileBackup.header)
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if appState.connectionState.isConnected {
                        Text(L10n.Settings.Settings.Backup.Import.disabledWhenConnected)
                    }
                    Text(L10n.Settings.Settings.Backup.FileBackup.footer)
                }
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.Settings.Backup.title)
        .alert(L10n.Settings.Settings.Backup.Export.Alert.title, isPresented: $showExportConfirmation) {
            Button(L10n.Settings.Settings.Backup.Export.Alert.cancel, role: .cancel) {}
            Button(L10n.Settings.Settings.Backup.Export.Alert.export) {
                viewModel.performExport()
            }
        } message: {
            Text(L10n.Settings.Settings.Backup.Export.Alert.message)
        }
        .fileExporter(
            isPresented: Binding(
                get: { viewModel.pendingExport != nil },
                // A dismissal that only writes the binding must still resolve the pending
                // export, or the export row stays wedged for the rest of the session.
                // handleExportResult is idempotent, so a later onCompletion is a no-op.
                set: { isPresented in
                    if !isPresented, viewModel.pendingExport != nil {
                        viewModel.handleExportResult(.failure(CocoaError(.userCancelled)))
                    }
                }
            ),
            document: exportDocument,
            contentType: .mc1Backup,
            defaultFilename: viewModel.defaultExportFilename
        ) { result in
            viewModel.handleExportResult(result)
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.mc1Backup]
        ) { result in
            viewModel.handleFileSelected(result: result)
        }
        .sheet(isPresented: Binding(
            get: { viewModel.isImportSheetActive },
            set: { if !$0 { viewModel.dismissImportSheet() } }
        )) {
            ImportPreviewSheet(viewModel: viewModel)
        }
        .sheet(item: Binding(
            get: { viewModel.exportSummary },
            set: { if $0 == nil { viewModel.dismissExportSuccess() } }
        )) { summary in
            NavigationStack {
                ExportSuccessContent(summary: summary, onDismiss: viewModel.dismissExportSuccess)
            }
        }
        .errorAlert($viewModel.errorMessage)
    }

    // MARK: - Rows

    private var exportRow: some View {
        Button {
            showExportConfirmation = true
        } label: {
            HStack {
                if viewModel.isExporting {
                    ProgressView()
                    Text(L10n.Settings.Settings.Backup.Export.progress)
                } else {
                    Label(L10n.Settings.Settings.Backup.Export.title, systemImage: "square.and.arrow.up")
                }
                Spacer()
                Text(L10n.Settings.Settings.Backup.Export.subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .disabled(viewModel.isExporting || viewModel.isImporting || viewModel.isParsing)
    }

    @ViewBuilder
    private var importRow: some View {
        let isRadioConnected = appState.connectionState.isConnected
        let isBusy = viewModel.isExporting || viewModel.isImporting || viewModel.isParsing
        let content = Button {
            showFileImporter = true
        } label: {
            HStack {
                if viewModel.isParsing {
                    ProgressView()
                    Text(L10n.Settings.Settings.Backup.Import.parsing)
                } else {
                    Label(L10n.Settings.Settings.Backup.Import.title, systemImage: "square.and.arrow.down")
                }
                Spacer()
                Text(L10n.Settings.Settings.Backup.Import.subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        if isRadioConnected {
            content
                .disabled(true)
                .foregroundStyle(.secondary)
                .accessibilityHint(L10n.Settings.Settings.Backup.Import.disabledWhenConnected)
        } else {
            content.disabled(isBusy)
        }
    }

    // MARK: - Helpers

    private var exportDocument: AppBackupDocument? {
        guard let data = viewModel.pendingExport?.data else { return nil }
        return AppBackupDocument(data: data)
    }
}
