import MC1Services
import SwiftUI

struct DisappearedNeighborRow: View {
    let neighbor: NeighborSnapshotEntry
    let displayName: String
    let matchKind: NodeNameMatchKind

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(displayName)
                    if matchKind == .fallback {
                        FallbackMatchIndicatorView(
                            accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.possibleMatch,
                            accessibilityHint: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation,
                            title: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchTitle,
                            explanation: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation
                        )
                    }
                }
                Text(L10n.RemoteNodes.RemoteNodes.History.notSeen)
                    .font(.caption2)
            }
            Spacer()
            Text(L10n.RemoteNodes.RemoteNodes.Status.snrFormat(neighbor.snr.formatted(.number.precision(.fractionLength(1)))))
                .font(.caption)
        }
        .foregroundStyle(.tertiary)
    }
}
