// MC1/Views/Chats/Components/PathHopRowView.swift
import SwiftUI
import MC1Services

/// Type of hop in the message path.
enum PathHopType {
    case sender
    case intermediate(Int)
    case receiver
}

/// Row displaying a single hop in the message path.
struct PathHopRowView: View {
    let hopType: PathHopType
    let nodeName: String
    let nodeID: String?
    let snr: Double?
    let matchKind: NodeNameMatchKind

    init(
        hopType: PathHopType,
        nodeName: String,
        nodeID: String?,
        snr: Double?,
        matchKind: NodeNameMatchKind = .exact
    ) {
        self.hopType = hopType
        self.nodeName = nodeName
        self.nodeID = nodeID
        self.snr = snr
        self.matchKind = matchKind
    }

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if let nodeID {
                        Text(nodeID)
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .monospaced()
                    }

                    Text(nodeName)
                        .font(.body)

                    if matchKind == .fallback {
                        FallbackMatchIndicatorView()
                    }
                }

                Text(hopLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // Show signal info only on receiver (where we have SNR)
            if case .receiver = hopType, let snr {
                VStack(alignment: .trailing, spacing: 2) {
                    Image(systemName: "cellularbars", variableValue: snrQuality.barLevel)
                        .foregroundStyle(snrQuality.color)

                    Text("SNR \(snr, format: .number.precision(.fractionLength(1))) dB")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(hopLabel): \(nodeName)")
        .accessibilityValue(accessibilityValueText)
    }

    private var hopLabel: String {
        switch hopType {
        case .sender:
            return L10n.Chats.Chats.Path.Hop.sender
        case .intermediate(let index):
            return L10n.Chats.Chats.Path.Hop.number(index)
        case .receiver:
            return L10n.Chats.Chats.Path.Receiver.label
        }
    }

    private var accessibilityValueText: String {
        var values: [String] = []
        if case .receiver = hopType, let snr {
            let snrText = snr.formatted(.number.precision(.fractionLength(1)))
            values.append(L10n.Chats.Chats.Path.Hop.signalQuality(signalQualityText, snrText))
        }
        if let nodeID {
            values.append(L10n.Chats.Chats.Path.Hop.nodeId(nodeID))
        }
        return values.joined(separator: ", ")
    }

    private var snrQuality: SNRQuality { SNRQuality(snr: snr) }

    private var signalQualityText: String { snrQuality.localizedLabel }
}

#Preview {
    List {
        PathHopRowView(hopType: .sender, nodeName: "AlphaNode", nodeID: "A3", snr: nil)
        PathHopRowView(hopType: .intermediate(1), nodeName: "RelayNode", nodeID: "7F", snr: nil)
        PathHopRowView(hopType: .receiver, nodeName: "MyDevice", nodeID: nil, snr: 6.2)
    }
}
