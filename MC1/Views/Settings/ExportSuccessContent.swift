import MC1Services
import SwiftUI

/// Success content for the backup export sheet. Structural mirror of
/// `ImportSuccessContent` minus the "already here" section — export
/// has no skip concept. Lives in the MC1 target because it uses `L10n`.
struct ExportSuccessContent: View {
    @Environment(\.appTheme) private var theme
    let summary: AppBackupViewModel.ExportSuccessSummary
    let onDismiss: @MainActor () -> Void

    var body: some View {
        List {
            heroSection
                .themedRowBackground(theme)
            includedSection
                .themedRowBackground(theme)
            doneSection
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.Settings.Backup.Export.Success.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            AccessibilityNotification.Announcement(
                L10n.Settings.Settings.Backup.Export.Success.announcement(summary.filename)
            ).post()
        }
        .sensoryFeedback(.success, trigger: summary.id)
    }

    private var heroSection: some View {
        Section {
            VStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(.largeTitle))
                    .imageScale(.large)
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
                Text(L10n.Settings.Settings.Backup.Export.Success.title)
                    .font(.headline)
                VStack(spacing: 2) {
                    Text(summary.filename)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Text(summary.byteCount.formatted(.byteCount(style: .file)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }

    private var includedSection: some View {
        Section(L10n.Settings.Settings.Backup.Export.Success.includedSection) {
            ForEach(BackupModelKind.allCases, id: \.self) { kind in
                let count = summary.manifest.count(for: kind)
                if count > 0 {
                    LabeledContent(kind.label, value: "\(count)")
                }
            }
        }
    }

    private var doneSection: some View {
        Section {
            Button {
                onDismiss()
            } label: {
                Text(L10n.Settings.Settings.Backup.Export.Success.done)
                    .frame(maxWidth: .infinity)
            }
            .liquidGlassProminentButtonStyle()
            .controlSize(.large)
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            .listRowBackground(Color.clear)
        }
    }
}
