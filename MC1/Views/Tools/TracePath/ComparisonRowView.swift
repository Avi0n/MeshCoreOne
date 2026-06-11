import SwiftUI
import MC1Services

/// Row comparing current trace RTT with a previous saved path run
struct ComparisonRowView: View {
    let currentMs: Int
    let previousRun: TracePathRunDTO
    @Bindable var viewModel: TracePathViewModel

    var body: some View {
        let diff = currentMs - previousRun.roundTripMs
        let percentChange = previousRun.roundTripMs > 0
            ? Double(diff) / Double(previousRun.roundTripMs) * 100
            : 0

        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(L10n.Contacts.Contacts.PathDetail.roundTrip)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(currentMs) ms")
                    .font(.body.monospacedDigit())

                // Change indicator
                if diff != 0 {
                    Text(diff > 0 ? "\u{25B2}" : "\u{25BC}")
                        .foregroundStyle(diff > 0 ? .red : .green)
                        .accessibilityLabel(diff > 0
                            ? L10n.Contacts.Contacts.Results.Comparison.increased
                            : L10n.Contacts.Contacts.Results.Comparison.decreased)
                    Text(abs(percentChange), format: .number.precision(.fractionLength(0)))
                        .font(.caption.monospacedDigit())
                    + Text("%")
                        .font(.caption)
                }
            }

            Text(L10n.Contacts.Contacts.Results.comparison(previousRun.roundTripMs, previousRun.date.formatted(date: .abbreviated, time: .omitted)))
                .font(.caption)
                .foregroundStyle(.secondary)
        }

        // Sparkline with history link
        if let savedPath = viewModel.activeSavedPath, !savedPath.recentRTTs.isEmpty {
            HStack {
                MiniSparkline(values: savedPath.recentRTTs)
                    .frame(height: 20)

                Spacer()

                NavigationLink {
                    SavedPathDetailView(savedPath: savedPath)
                } label: {
                    Text(L10n.Contacts.Contacts.Results.viewRuns(savedPath.runCount))
                        .font(.caption)
                }
            }
            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
        }
    }
}
