import SwiftUI

/// Feature-request and bug-report contacts, plus `DiagnosticsSection` for log export.
struct FeedbackView: View {
  @Environment(\.appTheme) private var theme
  @State private var exportedLogFile: ExportedLogFile?

  private enum FeedbackContact {
    static let issuesURL = URL(string: "https://github.com/Avi0n/MeshCoreOne/issues")!
    static let email = "info@meshcoreone.com"
    static let mailtoURL = URL(string: "mailto:\(email)")!
  }

  var body: some View {
    List {
      Section {
        Text(L10n.Settings.Feedback.Header.body)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }
      .themedRowBackground(theme)

      Section {
        Link(destination: FeedbackContact.issuesURL) {
          HStack {
            TintedLabel(L10n.Settings.Feedback.GitHub.link, systemImage: "chevron.left.forwardslash.chevron.right")
            Spacer()
            Image(systemName: "arrow.up.right")
              .font(.caption)
              .foregroundStyle(.tertiary)
          }
        }
        .foregroundStyle(.primary)

        Link(destination: FeedbackContact.mailtoURL) {
          HStack {
            TintedLabel(L10n.Settings.Feedback.Email.link, systemImage: "envelope")
            Spacer()
            Image(systemName: "arrow.up.right")
              .font(.caption)
              .foregroundStyle(.tertiary)
          }
        }
        .foregroundStyle(.primary)
      }
      .themedRowBackground(theme)

      DiagnosticsSection(exportedFile: $exportedLogFile, isSidebar: false)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Feedback.title)
    .navigationBarTitleDisplayMode(.inline)
    .sheet(item: $exportedLogFile) { file in
      ActivityView(activityItems: [file.url])
    }
  }
}

#Preview {
  NavigationStack {
    FeedbackView()
  }
}
