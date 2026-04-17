import SwiftUI
import MC1Services

struct ImportPreviewSheet: View {
    @Bindable var viewModel: AppBackupViewModel

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isImporting {
                    importingView
                } else if let result = viewModel.importResult {
                    successView(result: result)
                } else if let error = viewModel.sheetErrorMessage {
                    errorView(message: error)
                } else if viewModel.isCancelled {
                    cancelledView
                } else if let envelope = viewModel.previewEnvelope {
                    previewView(envelope: envelope)
                }
            }
            .navigationTitle(L10n.Settings.Settings.Backup.Import.Preview.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Settings.Settings.Backup.Import.Preview.cancel) {
                        if viewModel.isImporting {
                            viewModel.cancelImport()
                        } else {
                            viewModel.dismissImportSheet()
                        }
                    }
                    .disabled(viewModel.isCancellingImport)
                }
            }
            .interactiveDismissDisabled(viewModel.isImporting)
            .sensoryFeedback(.success, trigger: viewModel.importResult) { _, newValue in newValue != nil }
            .sensoryFeedback(.error, trigger: viewModel.sheetErrorMessage) { _, newValue in newValue != nil }
        }
    }

    // MARK: - Preview

    private func previewView(envelope: AppBackupEnvelope) -> some View {
        List {
            Section(L10n.Settings.Settings.Backup.Import.Preview.details) {
                LabeledContent(L10n.Settings.Settings.Backup.Import.Preview.exported, value: envelope.exportDate.formatted(date: .abbreviated, time: .shortened))
                LabeledContent(L10n.Settings.Settings.Backup.Import.Preview.appVersion, value: "\(envelope.appVersion) (\(envelope.appBuild))")
            }

            Section(L10n.Settings.Settings.Backup.Import.Preview.contents) {
                manifestRows(envelope.manifest)
            }

            Section {
                Label {
                    Text(L10n.Settings.Settings.Backup.Import.Preview.info)
                } icon: {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(.tint)
                }
            }

            Section {
                Button {
                    viewModel.performImport()
                } label: {
                    Label(L10n.Settings.Settings.Backup.Import.Preview.button, systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity)
                }
                .liquidGlassProminentButtonStyle()
                .controlSize(.large)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
            }
        }
    }

    // MARK: - Importing

    private var importingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)
            Text(viewModel.isCancellingImport
                 ? L10n.Settings.Settings.Backup.Import.cancelling
                 : L10n.Settings.Settings.Backup.Import.progress)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            AccessibilityNotification.Announcement(L10n.Settings.Settings.Backup.Import.progress).post()
        }
    }

    // MARK: - Success

    private func successView(result: ImportResult) -> some View {
        let didRestore = result.hasRestoredChanges
        let headerTitle = didRestore
            ? L10n.Settings.Settings.Backup.Import.Success.title
            : L10n.Settings.Settings.Backup.Import.NothingToImport.title
        return List {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: didRestore ? "checkmark.circle.fill" : "info.circle.fill")
                        .font(.system(.largeTitle))
                        .imageScale(.large)
                        .foregroundStyle(didRestore ? Color.green : Color.secondary)
                        .accessibilityHidden(true)
                    Text(headerTitle)
                        .font(.headline)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            if result.totalRestoredRecordCount > 0 {
                Section(L10n.Settings.Settings.Backup.Import.Success.imported) {
                    importCountRows(result: result)
                }
            }

            if result.totalSkipped > 0 {
                Section {
                    if didRestore {
                        Text(L10n.Settings.Settings.Backup.Import.Success.skippedD(result.totalSkipped))
                            .foregroundStyle(.secondary)
                    } else {
                        Text(L10n.Settings.Settings.Backup.Import.Success.allSkipped)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section {
                Button {
                    viewModel.dismissImportSheet()
                } label: {
                    HStack {
                        Spacer()
                        Text(L10n.Settings.Settings.Backup.Import.Success.done)
                            .bold()
                        Spacer()
                    }
                }
            }
        }
        .onAppear {
            AccessibilityNotification.Announcement(headerTitle).post()
        }
    }

    // MARK: - Cancelled

    private var cancelledView: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "info.circle.fill")
                        .font(.system(.largeTitle))
                        .imageScale(.large)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    Text(L10n.Settings.Settings.Backup.Import.Cancelled.title)
                        .font(.headline)
                    Text(L10n.Settings.Settings.Backup.Import.Cancelled.message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            Section {
                Button {
                    viewModel.dismissImportSheet()
                } label: {
                    HStack {
                        Spacer()
                        Text(L10n.Settings.Settings.Backup.Import.Success.done)
                            .bold()
                        Spacer()
                    }
                }
            }
        }
        .onAppear {
            AccessibilityNotification.Announcement(L10n.Settings.Settings.Backup.Import.Cancelled.title).post()
        }
    }

    // MARK: - Error

    private func errorView(message: String) -> some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(.largeTitle))
                        .imageScale(.large)
                        .foregroundStyle(.red)
                        .accessibilityHidden(true)
                    Text(L10n.Settings.Settings.Backup.Import.Error.title)
                        .font(.headline)
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            Section {
                Button {
                    viewModel.dismissImportSheet()
                } label: {
                    HStack {
                        Spacer()
                        Text(L10n.Settings.Settings.Backup.Import.Error.dismiss)
                            .bold()
                        Spacer()
                    }
                }
            }
        }
        .onAppear {
            AccessibilityNotification.Announcement(L10n.Settings.Settings.Backup.Import.Error.title).post()
        }
    }

    // MARK: - Count rows

    @ViewBuilder
    private func manifestRows(_ manifest: BackupManifest) -> some View {
        ForEach(BackupModelKind.allCases, id: \.self) { kind in
            manifestRow(label(for: kind), count: manifest.count(for: kind))
        }
    }

    @ViewBuilder
    private func importCountRows(result: ImportResult) -> some View {
        ForEach(BackupModelKind.allCases, id: \.self) { kind in
            let counts = result.counts[kind] ?? .zero
            manifestRow(label(for: kind), count: counts.inserted + counts.merged)
        }
    }

    @ViewBuilder
    private func manifestRow(_ label: String, count: Int) -> some View {
        if count > 0 {
            LabeledContent(label, value: "\(count)")
        }
    }

    private func label(for kind: BackupModelKind) -> String {
        switch kind {
        case .messages: L10n.Settings.Settings.Backup.Import.Preview.messages
        case .contacts: L10n.Settings.Settings.Backup.Import.Preview.contacts
        case .channels: L10n.Settings.Settings.Backup.Import.Preview.channels
        case .devices: L10n.Settings.Settings.Backup.Import.Preview.devices
        case .roomMessages: L10n.Settings.Settings.Backup.Import.Preview.roomMessages
        case .reactions: L10n.Settings.Settings.Backup.Import.Preview.reactions
        case .messageRepeats: L10n.Settings.Settings.Backup.Import.Preview.messageRepeats
        case .savedTracePaths: L10n.Settings.Settings.Backup.Import.Preview.savedPaths
        case .remoteNodeSessions: L10n.Settings.Settings.Backup.Import.Preview.remoteNodeSessions
        case .blockedChannelSenders: L10n.Settings.Settings.Backup.Import.Preview.blockedSenders
        case .nodeStatusSnapshots: L10n.Settings.Settings.Backup.Import.Preview.nodeStatusSnapshots
        }
    }
}
