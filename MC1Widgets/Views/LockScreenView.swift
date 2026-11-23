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
        if context.state.showsSecondaryRate {
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
    if context.state.showsFailedSend, let failed = context.state.failedSends {
      FailedSendLabel(summary: failed, style: .lockScreen)
    } else if context.state.showsUnread {
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
  let rate: Int?
  let style: PacketRateStyle

  var body: some View {
    HStack(alignment: .firstTextBaseline, spacing: style.unitSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.symbolSpacing) {
        Image(systemName: "arrow.down")
          .font(style.unitFont)
          .accessibilityHidden(true)
        Text(rate.map(String.init) ?? "—")
          .font(style.numberFont)
          .monospacedDigit()
          .liveActivityInPlaceUpdate(rate: rate)
      }
      Text("packets/min")
        .font(style.unitFont)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .accessibilityHidden(true)
    }
    .foregroundStyle(style == .secondary ? .secondary : .primary)
    .animation(nil, value: rate)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(rate.map { Text("\($0) packets per minute") } ?? Text("Packet rate paused while locked"))
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

enum FailedSendLabelStyle {
  case lockScreen
  case expanded

  var font: Font {
    switch self {
    case .lockScreen: .body
    case .expanded: .headline
    }
  }
}

/// A failed DM nobody acknowledged: red envelope, "No ACK", and the repeaters heard
/// forwarding it (the repeat symbol appears only when at least one was).
struct FailedSendLabel: View {
  let summary: FailedSendSummary
  let style: FailedSendLabelStyle

  var body: some View {
    HStack(spacing: LiveActivityLayout.symbolSpacing) {
      Image(systemName: "envelope.fill")
        .imageScale(.small)
        .foregroundStyle(.red)
        .accessibilityHidden(true)
      Text("No ACK")
      if summary.repeatsHeard > 0 {
        Image(systemName: "repeat")
          .imageScale(.small)
          .accessibilityHidden(true)
        Text("\(summary.repeatsHeard)")
          .monospacedDigit()
      }
    }
    .font(style.font)
    .fontWeight(.regular)
    .foregroundStyle(.primary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Text("Message not acknowledged"))
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

  /// A failed send or unread count takes the top row, so the packet rate moves beneath it.
  var showsSecondaryRate: Bool {
    showsFailedSend || showsUnread
  }
}

extension View {
  /// Holds the rate glyphs in place. Live Activity text transitions animate them on each update.
  func liveActivityInPlaceUpdate(rate: Int?) -> some View {
    contentTransition(.identity)
      .animation(nil, value: rate)
      .transition(.identity)
  }
}

extension ActivityViewContext where Attributes == MeshStatusAttributes {
  /// `nil` keeps the rate paused while locked; stale active rates fall back to 0.
  var displayedPacketRate: Int? {
    guard let rate = state.packetsPerMinute else { return nil }
    return isStale ? 0 : rate
  }
}
