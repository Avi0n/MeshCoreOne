import SwiftUI

/// The "What's New" sheet. Non-safety chrome, so glass on the Continue button is
/// allowed; Continue and swipe-to-dismiss share one dismiss path that persists the baseline.
struct WhatsNewSheet: View {
  let release: WhatsNewRelease

  @Environment(\.dismiss) private var dismiss

  fileprivate enum Metrics {
    static let titleTopPadding: CGFloat = 32
    static let rowSpacing: CGFloat = 28
    static let titleToRowsSpacing: CGFloat = 36
    static let symbolColumnWidth: CGFloat = 44
    static let symbolToTextSpacing: CGFloat = 16
    static let titleToBodySpacing: CGFloat = 4
  }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: Metrics.titleToRowsSpacing) {
        Text(L10n.WhatsNew.WhatsNew.title)
          .font(.largeTitle.bold())
          .multilineTextAlignment(.center)
          .frame(maxWidth: .infinity, alignment: .center)
          .accessibilityHeading(.h1)
          .padding(.top, Metrics.titleTopPadding)

        VStack(alignment: .leading, spacing: Metrics.rowSpacing) {
          ForEach(release.items) { item in
            WhatsNewRow(item: item)
          }
        }
      }
      .padding()
    }
    .safeAreaInset(edge: .bottom) {
      Button {
        dismiss()
      } label: {
        Text(L10n.WhatsNew.WhatsNew.continueButton)
          .font(.headline)
          .frame(maxWidth: .infinity)
          .padding()
      }
      .liquidGlassProminentButtonStyle()
      .padding()
    }
    .presentationDragIndicator(.hidden)
  }
}

/// A decorative SF Symbol plus the feature's title and description; VoiceOver reads
/// the pair as one element.
private struct WhatsNewRow: View {
  let item: WhatsNewItem

  var body: some View {
    HStack(alignment: .top, spacing: WhatsNewSheet.Metrics.symbolToTextSpacing) {
      Image(systemName: item.symbol)
        .font(.title)
        .foregroundStyle(.tint)
        .frame(width: WhatsNewSheet.Metrics.symbolColumnWidth)
        .accessibilityHidden(true)

      VStack(alignment: .leading, spacing: WhatsNewSheet.Metrics.titleToBodySpacing) {
        Text(item.title)
          .font(.headline)
        Text(item.description)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }

      Spacer(minLength: 0)
    }
    .accessibilityElement(children: .combine)
  }
}

#Preview {
  Color.clear.sheet(isPresented: .constant(true)) {
    WhatsNewSheet(release: .preview)
  }
}

private extension WhatsNewRelease {
  static let preview = WhatsNewRelease(
    version: WhatsNewVersion(major: 1, minor: 1),
    items: [
      WhatsNewItem(
        symbol: "sparkles",
        title: "Faster Messaging",
        description: "Messages now send and sync more quickly across your mesh."
      ),
      WhatsNewItem(
        symbol: "antenna.radiowaves.left.and.right",
        title: "Stronger Multi-Hop Routing",
        description: "Improved route handling keeps distant nodes connected."
      ),
      WhatsNewItem(
        symbol: "lock.shield",
        title: "Private by Default",
        description: "Your messages stay encrypted end to end, on device."
      )
    ]
  )
}
