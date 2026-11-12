import ActivityKit
import SwiftUI
import WidgetKit

struct LockScreenView: View {
  let context: ActivityViewContext<MeshStatusAttributes>

  var body: some View {
    HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.identitySpacing) {
      antenna
      VStack(alignment: .leading, spacing: LiveActivityLayout.rowSpacing) {
        Text(context.attributes.deviceName)
          .bold()
          .lineLimit(1)
          .truncationMode(.tail)
        if context.state.showsBattery {
          BatteryLabel(percent: context.state.batteryPercent)
        }
      }
      // minWidth lets the name truncate before the metric column.
      .frame(minWidth: 0, alignment: .leading)

      Spacer(minLength: LiveActivityLayout.identitySpacing)

      VStack(alignment: .trailing, spacing: LiveActivityLayout.rowSpacing) {
        primaryMetric
        if context.state.showsUnread {
          PacketRateLabel(rate: context.displayedPacketRate, style: .secondary)
        }
      }
      .layoutPriority(1)
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

  @ViewBuilder
  private var primaryMetric: some View {
    if context.state.showsUnread {
      UnreadCountLabel(count: context.state.unreadCount, style: .lockScreen)
    } else if context.state.isConnected {
      PacketRateLabel(rate: context.displayedPacketRate, style: .lockScreen)
    } else {
      Text("Disconnected")
        .foregroundStyle(.orange)
    }
  }
}

// MARK: - Subviews

enum LiveActivityLayout {
  /// Shared antenna-to-name gap, so the expanded battery starts under the name.
  static let identitySpacing: CGFloat = 8
  static let rowSpacing: CGFloat = 2
  static let symbolSpacing: CGFloat = 2
  static let rateUnitSpacing: CGFloat = 4
}

enum PacketRateStyle {
  case lockScreen
  case expanded
  /// Drawn smaller than the unread label, on the row beneath it.
  case secondary

  var numberFont: Font {
    switch self {
    case .lockScreen: .caption
    case .expanded: .title3.bold()
    case .secondary: .caption2
    }
  }

  var unitFont: Font {
    switch self {
    case .lockScreen, .expanded: .caption
    case .secondary: .caption2
    }
  }

  var unitSpacing: CGFloat {
    switch self {
    case .lockScreen, .expanded: LiveActivityLayout.rateUnitSpacing
    case .secondary: LiveActivityLayout.symbolSpacing
    }
  }
}

struct PacketRateLabel: View {
  let rate: Int
  let style: PacketRateStyle

  var body: some View {
    HStack(alignment: .firstTextBaseline, spacing: style.unitSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.symbolSpacing) {
        Image(systemName: "arrow.down")
          .font(style.unitFont)
          .accessibilityHidden(true)
        Text("\(rate)")
          .font(style.numberFont)
          .monospacedDigit()
          .contentTransition(.numericText())
      }
      Text("packets/min")
        .font(style.unitFont)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .accessibilityHidden(true)
    }
    .foregroundStyle(style == .secondary ? .secondary : .primary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Text("\(rate) packets per minute"))
  }
}

enum UnreadLabelStyle {
  case lockScreen
  case expanded

  var font: Font {
    switch self {
    case .lockScreen: .body
    case .expanded: .headline
    }
  }
}

struct UnreadCountLabel: View {
  let count: Int
  let style: UnreadLabelStyle

  var body: some View {
    HStack(spacing: LiveActivityLayout.symbolSpacing) {
      Image(systemName: "envelope.badge")
        .imageScale(.small)
        .accessibilityHidden(true)
      Text("\(count) unread")
        .contentTransition(.numericText())
    }
    .font(style.font)
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
}

extension ActivityViewContext where Attributes == MeshStatusAttributes {
  /// Stale content shows 0 so the last rate does not stay on screen.
  var displayedPacketRate: Int {
    isStale ? 0 : state.packetsPerMinute
  }
}
