import SwiftUI
import MC1Services

// MARK: - Result Hop Row

/// Row for displaying a hop in the trace results
struct TraceResultHopRow: View {
    let hop: TraceHop
    let hopIndex: Int
    var batchStats: (avg: Double, min: Double, max: Double)?
    var latestSNR: Double?
    var isBatchInProgress: Bool = false

    /// SNR value to use for signal bars (latest during progress, average when complete)
    private var displaySNR: Double {
        if isBatchInProgress {
            return latestSNR ?? hop.snr
        } else if let stats = batchStats {
            return stats.avg
        } else {
            return hop.snr
        }
    }

    private var snrQuality: SNRQuality { SNRQuality(snr: displaySNR) }

    private var signalLevel: Double { snrQuality.barLevel }

    private var signalColor: Color { snrQuality.color }

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                // Node identifier
                if hop.isStartNode {
                    Text(hop.resolvedName ?? L10n.Contacts.Contacts.Results.Hop.myDevice)
                    Text(L10n.Contacts.Contacts.Results.Hop.started)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else if hop.isEndNode {
                    Text(hop.resolvedName ?? L10n.Contacts.Contacts.Results.Hop.myDevice)
                        .foregroundStyle(.green)
                    Text(L10n.Contacts.Contacts.Results.Hop.received)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else if let hashDisplay = hop.hashDisplayString {
                    HStack {
                        Text(hashDisplay)
                            .font(.body.monospaced())
                            .foregroundStyle(.secondary)
                        if let name = hop.resolvedName {
                            Text(name)
                        }
                    }
                    Text(L10n.Contacts.Contacts.Results.Hop.repeated)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                // SNR display - batch mode shows avg with range, single shows plain SNR
                if !hop.isStartNode {
                    if let stats = batchStats {
                        let snrFormat = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(1))
                        Text(L10n.Contacts.Contacts.Results.Hop.avgSNR(
                            stats.avg.formatted(snrFormat),
                            stats.min.formatted(snrFormat),
                            stats.max.formatted(snrFormat)
                        ))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(L10n.Contacts.Contacts.Results.Hop.avgSNRLabel(
                                stats.avg.formatted(snrFormat),
                                stats.min.formatted(snrFormat),
                                stats.max.formatted(snrFormat)
                            ))
                    } else {
                        Text(L10n.Contacts.Contacts.Results.Hop.snr(hop.snr.formatted(.number.precision(.fractionLength(2)))))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer()

            // Signal strength indicator (not for start node - it didn't receive)
            if !hop.isStartNode {
                Image(systemName: "cellularbars", variableValue: signalLevel)
                    .foregroundStyle(signalColor)
                    .font(.title2)
            }
        }
        .padding(.vertical, 4)
    }
}
