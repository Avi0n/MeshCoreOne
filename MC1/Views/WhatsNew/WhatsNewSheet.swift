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
        static let titleToSubtitleSpacing: CGFloat = 12
        static let symbolColumnWidth: CGFloat = 44
        static let symbolToTextSpacing: CGFloat = 16
        static let titleToBodySpacing: CGFloat = 4
        static let buttonSpacing: CGFloat = 8
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.titleToRowsSpacing) {
                VStack(spacing: Metrics.titleToSubtitleSpacing) {
                    Text(release.title ?? L10n.WhatsNew.WhatsNew.title)
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)
                        .accessibilityHeading(.h1)

                    if let subtitle = release.subtitle {
                        Text(subtitle)
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, Metrics.titleTopPadding)

                VStack(alignment: .leading, spacing: Metrics.rowSpacing) {
                    ForEach(release.items) { item in
                        WhatsNewRow(item: item)
                    }
                }

                if let footer = release.footer {
                    Text(footer)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .padding()
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: Metrics.buttonSpacing) {
                if let actionURL = release.actionURL, let actionTitle = release.actionTitle {
                    Link(destination: actionURL) {
                        filledLabel(actionTitle)
                    }
                    .liquidGlassProminentButtonStyle()

                    Button(role: .cancel) {
                        dismiss()
                    } label: {
                        Text(L10n.WhatsNew.WhatsNew.continueButton)
                            .font(.headline)
                    }
                } else {
                    Button {
                        dismiss()
                    } label: {
                        filledLabel(L10n.WhatsNew.WhatsNew.continueButton)
                    }
                    .liquidGlassProminentButtonStyle()
                }
            }
            .padding()
            .frame(maxWidth: .infinity)
            .background(Color(.systemBackground))
        }
        .presentationDragIndicator(.hidden)
    }

    private func filledLabel(_ text: String) -> some View {
        Text(text)
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding()
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
        build: 164,
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
