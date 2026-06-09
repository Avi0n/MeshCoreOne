import ActivityKit
import SwiftUI
import WidgetKit

struct LockScreenView: View {
    let context: ActivityViewContext<MeshStatusAttributes>

    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                Image(systemName: context.state.antennaIconName)
                    .foregroundStyle(context.state.isConnected ? .green : .orange)
                    .accessibilityHidden(true)

                Text(context.attributes.deviceName)
                    .bold()
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer()

                if context.state.isConnected {
                    PacketRateLabel(packetsPerMinute: context.state.packetsPerMinute, isStale: context.isStale)
                    BatteryLabel(percent: context.state.batteryPercent)
                } else {
                    Text("Disconnected")
                        .foregroundStyle(.orange)
                }
            }

            if context.state.isConnected, context.state.unreadCount > 0 {
                HStack {
                    Spacer()
                    Image(systemName: "envelope.badge")
                        .accessibilityHidden(true)
                    Text("\(context.state.unreadCount) unread")
                        .contentTransition(.numericText())
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(context.state.unreadCount) unread messages")
            }

        }
        .padding()
        .accessibilityElement(children: .combine)
        .widgetURL(URL(string: "meshcoreone://status"))
    }
}

// MARK: - Subviews

struct PacketRateLabel: View {
    let packetsPerMinute: Int
    var isStale: Bool = false

    private var displayRate: Int { isStale ? 0 : packetsPerMinute }

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: "arrow.down")
                .font(.caption2)
                .accessibilityHidden(true)
            Text("\(displayRate)/m")
                .monospacedDigit()
                .contentTransition(.numericText())
        }
        .font(.caption)
        .foregroundStyle(.primary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(displayRate) packets per minute")
    }
}

struct BatteryLabel: View {
    let percent: Int?

    var body: some View {
        if let percent {
            HStack(spacing: 2) {
                Image(systemName: batteryIconName(for: percent))
                    .accessibilityHidden(true)
                Text("\(percent)%")
                    .monospacedDigit()
            }
            .font(.caption)
            .foregroundStyle(.primary)
            .accessibilityLabel("Battery \(percent) percent")
        }
    }

    private func batteryIconName(for percent: Int) -> String {
        switch percent {
        case 88...100: "battery.100"
        case 63..<88: "battery.75"
        case 38..<63: "battery.50"
        case 13..<38: "battery.25"
        default: "battery.0"
        }
    }
}
