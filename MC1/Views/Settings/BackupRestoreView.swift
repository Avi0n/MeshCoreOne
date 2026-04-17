import SwiftUI
import MC1Services
import TipKit

struct BackupRestoreView: View {
    @State private var viewModel: AppBackupViewModel
    @State private var showExportConfirmation = false
    @State private var showFileImporter = false

    init(
        connectionManager: ConnectionManager,
        onImportRestoredData: (@MainActor () -> Void)? = nil
    ) {
        _viewModel = State(initialValue: AppBackupViewModel(
            connectionManager: connectionManager,
            onImportRestoredData: onImportRestoredData
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
                Text(L10n.Settings.Settings.Backup.FileBackup.footer)
            }
        }
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
                get: { viewModel.exportedData != nil },
                set: { if !$0 { viewModel.exportedData = nil } }
            ),
            document: exportDocument,
            contentType: .mc1Backup,
            defaultFilename: viewModel.defaultExportFilename
        ) { result in
            if case .success = result {
                BackupTip.exportCompleted.sendDonation()
            }
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

    private var importRow: some View {
        Button {
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
        .disabled(viewModel.isExporting || viewModel.isImporting || viewModel.isParsing)
    }

    // MARK: - Helpers

    private var exportDocument: AppBackupDocument? {
        guard let data = viewModel.exportedData else { return nil }
        return AppBackupDocument(data: data)
    }
}
