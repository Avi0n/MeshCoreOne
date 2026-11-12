import ActivityKit
import SwiftUI
import WidgetKit

struct LockScreenView: View {
  let context: ActivityViewContext<MeshStatusAttributes>

  var body: some View {
    VStack(alignment: .leading, spacing: LiveActivityLayout.rowSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.identitySpacing) {
        antenna
        Text(context.attributes.deviceName)
          .bold()
          .lineLimit(1)
          .truncationMode(.tail)

        Spacer(minLength: LiveActivityLayout.identitySpacing)

        if context.state.showsUnread {
          UnreadCountLabel(count: context.state.unreadCount)
            .layoutPriority(1)
        } else if context.state.isConnected {
          PacketRateLabel(packetsPerMinute: context.state.packetsPerMinute, isStale: context.isStale)
            .layoutPriority(1)
        } else {
          Text("Disconnected")
            .foregroundStyle(.orange)
        }
      }

      if context.state.showsSecondRow {
        LiveActivityDetailRow(context: context) {
          antenna
        }
      }
    }
    .padding()
    .accessibilityElement(children: .combine)
    .widgetURL(URL(string: "meshcoreone://status"))
  }

  private var antenna: some View {
    Image(systemName: context.state.antennaIconName)
      .foregroundStyle(context.state.isConnected ? .green : .orange)
      .accessibilityHidden(true)
  }
}

// MARK: - Subviews

enum LiveActivityLayout {
  /// Matches the antenna-to-name gap so the battery lines up under the name.
  static let identitySpacing: CGFloat = 8
  static let rowSpacing: CGFloat = 2
  static let symbolSpacing: CGFloat = 2
  static let rateUnitSpacing: CGFloat = 4
}

/// Second row: battery under the radio name, and the packet rate once unread takes the top row.
struct LiveActivityDetailRow<Indent: View>: View {
  let context: ActivityViewContext<MeshStatusAttributes>
  private let indent: Indent

  init(context: ActivityViewContext<MeshStatusAttributes>, @ViewBuilder indent: () -> Indent) {
    self.context = context
    self.indent = indent()
  }

  var body: some View {
    HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.identitySpacing) {
      if context.state.showsBattery {
        // Reserves the antenna width so the battery starts under the name.
        indent
          .hidden()
        BatteryLabel(percent: context.state.batteryPercent)
      }
      Spacer(minLength: 0)
      if context.state.showsUnread {
        PacketRateLabel(
          packetsPerMinute: context.state.packetsPerMinute,
          isStale: context.isStale,
          demoted: true
        )
        .layoutPriority(1)
      }
    }
  }
}

struct PacketRateLabel: View {
  let packetsPerMinute: Int
  var isStale: Bool = false
  var prominent: Bool = false
  /// Second-row rate under the unread label, drawn smaller than that label.
  var demoted: Bool = false

  private var displayRate: Int {
    isStale ? 0 : packetsPerMinute
  }

  private var numberFont: Font {
    if demoted { return .caption2 }
    if prominent { return .title3.bold() }
    return .caption
  }

  private var unitFont: Font {
    demoted ? .caption2 : .caption
  }

  var body: some View {
    HStack(
      alignment: .firstTextBaseline,
      spacing: demoted ? LiveActivityLayout.symbolSpacing : LiveActivityLayout.rateUnitSpacing
    ) {
      HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.symbolSpacing) {
        Image(systemName: "arrow.down")
          .font(unitFont)
          .accessibilityHidden(true)
        Text("\(displayRate)")
          .font(numberFont)
          .monospacedDigit()
          .contentTransition(.numericText())
      }
      Text("packets/min")
        .font(unitFont)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .accessibilityHidden(true)
    }
    .foregroundStyle(demoted ? .secondary : .primary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Text("\(displayRate) packets per minute"))
  }
}

struct UnreadCountLabel: View {
  let count: Int
  var prominent: Bool = false

  var body: some View {
    HStack(spacing: LiveActivityLayout.symbolSpacing) {
      Image(systemName: "envelope.badge")
        .imageScale(.small)
        .accessibilityHidden(true)
      Text("\(count) unread")
        .contentTransition(.numericText())
    }
    .font(prominent ? .headline : .body)
    .fontWeight(.regular)
    .foregroundStyle(.primary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Text("\(count) unread messages"))
  }
}

struct BatteryLabel: View {
  let percent: Int?

  var body: some View {
    if let percent {
      HStack(spacing: LiveActivityLayout.symbolSpacing) {
        Image(systemName: batteryIconName(for: percent))
          .accessibilityHidden(true)
        Text("\(percent)%")
          .monospacedDigit()
      }
      .font(.caption)
      .foregroundStyle(.primary)
      .accessibilityLabel(Text("Battery \(percent) percent"))
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

extension MeshStatusAttributes.ContentState {
  var showsBattery: Bool {
    isConnected && batteryPercent != nil
  }

  var showsUnread: Bool {
    isConnected && unreadCount > 0
  }

  var showsSecondRow: Bool {
    showsBattery || showsUnread
  }
}
