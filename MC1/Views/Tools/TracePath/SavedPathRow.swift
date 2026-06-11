import SwiftUI
import MC1Services

struct SavedPathRow: View {
    let path: SavedTracePathDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(path.name)
                .font(.body)

            HStack(spacing: 8) {
                // Run count and recency
                Text(subtitleText)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                // Health indicator
                healthDot

                // Mini sparkline
                if !path.recentRTTs.isEmpty {
                    MiniSparkline(values: path.recentRTTs)
                        .frame(width: 50, height: 16)
                        .accessibilityLabel(sparklineAccessibilityLabel)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var subtitleText: String {
        var parts: [String] = []

        // Run count
        let runText = path.runCount == 1
            ? L10n.Contacts.Contacts.SavedPaths.Runs.singular
            : L10n.Contacts.Contacts.SavedPaths.Runs.plural(path.runCount)
        parts.append(runText)

        // Last run
        if let lastDate = path.lastRunDate {
            parts.append(L10n.Contacts.Contacts.SavedPaths.lastRun(lastDate.relativeFormatted))
        }

        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var healthDot: some View {
        let rate = path.successRate
        let healthDescription = rate >= 90
            ? L10n.Contacts.Contacts.SavedPaths.Health.healthy
            : rate >= 50
            ? L10n.Contacts.Contacts.SavedPaths.Health.degraded
            : L10n.Contacts.Contacts.SavedPaths.Health.poor
        Circle()
            .fill(rate >= 90 ? .green : rate >= 50 ? .yellow : .red)
            .frame(width: 8, height: 8)
            .accessibilityLabel(L10n.Contacts.Contacts.SavedPaths.healthLabel(healthDescription, rate))
    }

    private var sparklineAccessibilityLabel: String {
        let rtts = path.recentRTTs
        guard !rtts.isEmpty else { return L10n.Contacts.Contacts.SavedPaths.noResponseData }

        let avgRTT = rtts.reduce(0, +) / rtts.count
        let trend: String
        if rtts.count >= 2 {
            let firstHalf = rtts.prefix(rtts.count / 2).reduce(0, +) / max(1, rtts.count / 2)
            let secondHalf = rtts.suffix(rtts.count / 2).reduce(0, +) / max(1, rtts.count / 2)
            if secondHalf > firstHalf + 50 {
                trend = L10n.Contacts.Contacts.SavedPaths.Trend.increasing
            } else if secondHalf < firstHalf - 50 {
                trend = L10n.Contacts.Contacts.SavedPaths.Trend.decreasing
            } else {
                trend = L10n.Contacts.Contacts.SavedPaths.Trend.stable
            }
        } else {
            trend = L10n.Contacts.Contacts.SavedPaths.Trend.stable
        }
        return L10n.Contacts.Contacts.SavedPaths.responseTimes(avgRTT, trend)
    }
}
