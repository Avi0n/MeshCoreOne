import MC1Services
import SwiftUI

/// Success and null-outcome content for the backup import sheet. Extracted
/// from `ImportPreviewSheet` so the success rendering can stay under the
/// project's ~300-line-per-file guideline and keep its own disclosure state.
struct ImportSuccessContent: View {
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: AppBackupViewModel
  let result: ImportResult
  @State private var isAlreadyHereExpanded = false
  @State private var isDroppedExpanded = false

  private var didAdd: Bool {
    result.totalInserted > 0
  }

  private var didMerge: Bool {
    result.totalMerged > 0
  }

  private var hasSkipped: Bool {
    result.totalSkipped > 0
  }

  private var hasDropped: Bool {
    result.totalDropped > 0
  }

  private var heroTitle: String {
    result.hasRestoredChanges
      ? L10n.Settings.Settings.Backup.Import.Success.title
      : L10n.Settings.Settings.Backup.Import.NothingToImport.title
  }

  private var heroSubtitle: String {
    if didAdd {
      return L10n.Settings.Settings.Backup.Import.Success.subtitleAdded(result.totalInserted)
    }
    if didMerge {
      return L10n.Settings.Settings.Backup.Import.Success.subtitleRefreshed(result.totalMerged)
    }
    return L10n.Settings.Settings.Backup.Import.NothingToImport.subtitle
  }

  private var heroIconName: String {
    result.hasRestoredChanges ? "checkmark.circle.fill" : "info.circle.fill"
  }

  private var heroIconColor: Color {
    result.hasRestoredChanges ? .green : .secondary
  }

  var body: some View {
    List {
      heroSection
        .themedRowBackground(theme)
      if didAdd {
        addedSection
          .themedRowBackground(theme)
      }
      if hasSkipped {
        alreadyHereSection
          .themedRowBackground(theme)
      }
      if hasDropped {
        droppedSection
          .themedRowBackground(theme)
      }
      doneSection
    }
    .themedCanvas(theme)
    .onAppear {
      AccessibilityNotification.Announcement("\(heroTitle). \(heroSubtitle)").post()
    }
  }

  private var heroSection: some View {
    Section {
      VStack(spacing: 12) {
        Image(systemName: heroIconName)
          .font(.system(.largeTitle))
          .imageScale(.large)
          .foregroundStyle(heroIconColor)
          .accessibilityHidden(true)
        Text(heroTitle)
          .font(.headline)
        Text(heroSubtitle)
          .font(.subheadline)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 8)
    }
  }

  private var addedSection: some View {
    Section(L10n.Settings.Settings.Backup.Import.Success.addedSection) {
      ForEach(BackupModelKind.allCases, id: \.self) { kind in
        let inserted = result.counts[kind]?.inserted ?? 0
        if inserted > 0 {
          LabeledContent(kind.label, value: "\(inserted)")
        }
      }
    }
  }

  private var alreadyHereSection: some View {
    Section {
      DisclosureGroup(isExpanded: $isAlreadyHereExpanded) {
        ForEach(BackupModelKind.allCases, id: \.self) { kind in
          let skipped = result.counts[kind]?.skipped ?? 0
          if skipped > 0 {
            LabeledContent(kind.label, value: "\(skipped)")
          }
        }
      } label: {
        Text(L10n.Settings.Settings.Backup.Import.Success.alreadyHereSummary(result.totalSkipped))
      }
    } header: {
      Text(L10n.Settings.Settings.Backup.Import.Success.alreadyHereSection)
    } footer: {
      VStack(alignment: .leading, spacing: 4) {
        Text(L10n.Settings.Settings.Backup.Import.Success.alreadyHereFooter)
        if didMerge {
          Text(L10n.Settings.Settings.Backup.Import.Success.alreadyHereRefreshed(result.totalMerged))
        }
      }
    }
  }

  private var droppedSection: some View {
    Section {
      DisclosureGroup(isExpanded: $isDroppedExpanded) {
        ForEach(BackupModelKind.allCases, id: \.self) { kind in
          let dropped = result.counts[kind]?.dropped ?? 0
          if dropped > 0 {
            LabeledContent(kind.label, value: "\(dropped)")
          }
        }
      } label: {
        Text(L10n.Settings.Settings.Backup.Import.Success.droppedSummary(result.totalDropped))
      }
    } header: {
      Text(L10n.Settings.Settings.Backup.Import.Success.droppedSection)
    } footer: {
      Text(Self.droppedFooterText(for: result))
    }
  }

  /// Footer copy for the dropped-items section based on which kinds were dropped.
  static func droppedFooterText(for result: ImportResult) -> String {
    let channelDropped = (result.counts[.channels]?.dropped ?? 0) > 0
    let discoverDropped = (result.counts[.discoveredNodes]?.dropped ?? 0) > 0
    let cap = PersistenceStore.maxDiscoveredNodes
    if channelDropped, discoverDropped {
      return L10n.Settings.Settings.Backup.Import.Success.droppedFooterMixed(cap)
    }
    if discoverDropped {
      return L10n.Settings.Settings.Backup.Import.Success.droppedFooterDiscoveredNodes(cap)
    }
    return L10n.Settings.Settings.Backup.Import.Success.droppedFooter
  }

  private var doneSection: some View {
    Section {
      Group {
        if result.hasRestoredChanges {
          doneButton.liquidGlassProminentButtonStyle()
        } else {
          doneButton.liquidGlassButtonStyle()
        }
      }
      .controlSize(.large)
      .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
      .listRowBackground(Color.clear)
    }
  }

  private var doneButton: some View {
    Button {
      viewModel.dismissImportSheet()
    } label: {
      Text(L10n.Settings.Settings.Backup.Import.Success.done)
        .frame(maxWidth: .infinity)
    }
  }
}
