import ActivityKit
import SwiftUI
import WidgetKit

struct MeshStatusLiveActivity: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: MeshStatusAttributes.self) { context in
      LockScreenView(context: context)
        .activityBackgroundTint(Color(red: 51 / 255, green: 102 / 255, blue: 136 / 255).opacity(0.4)) // slate blue (#336688)
    } dynamicIsland: { context in
      let rate = context.displayedPacketRate
      return DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
          HStack(spacing: LiveActivityLayout.identitySpacing) {
            Image(systemName: context.state.antennaIconName)
              .foregroundStyle(context.state.isConnected ? .green : .orange)
              .accessibilityHidden(true)
            Text(context.attributes.deviceName)
              .bold()
              .lineLimit(1)
              .truncationMode(.tail)
          }
          .font(.headline)
          .accessibilityElement(children: .combine)
        }
        DynamicIslandExpandedRegion(.trailing) {
          if context.state.showsUnread {
            UnreadCountLabel(count: context.state.unreadCount, style: .expanded)
          } else if context.state.isConnected {
            PacketRateLabel(rate: rate, style: .expanded)
          } else {
            Text("Disconnected")
              .font(.caption)
              .foregroundStyle(.orange)
          }
        }
        DynamicIslandExpandedRegion(.bottom) {
          if context.state.showsBattery || context.state.showsUnread {
            ExpandedDetailRow(context: context, rate: rate)
          }
        }
      } compactLeading: {
        if #available(iOS 27, *) {
          AdaptiveConnectionMark(
            systemName: context.state.antennaIconName,
            isConnected: context.state.isConnected
          )
        } else {
          ConnectionMark(
            systemName: context.state.antennaIconName,
            isConnected: context.state.isConnected
          )
        }
      } compactTrailing: {
        if context.state.isConnected {
          if #available(iOS 27, *) {
            AdaptiveCompactRate(rate: rate)
          } else {
            CompactLabeledRate(rate: rate)
          }
        } else {
          Text("—")
        }
      } minimal: {
        if context.state.isConnected {
          CompactDigitRate(rate: rate)
        } else {
          ConnectionMark(
            systemName: context.state.antennaIconName,
            isConnected: false
          )
        }
      }
      .widgetURL(URL(string: "meshcoreone://status"))
    }
  }
}

/// Battery under the radio name, and the packet rate once unread takes the top row.
private struct ExpandedDetailRow: View {
  let context: ActivityViewContext<MeshStatusAttributes>
  let rate: Int

  var body: some View {
    HStack(alignment: .firstTextBaseline, spacing: LiveActivityLayout.identitySpacing) {
      if context.state.showsBattery {
        // Reserves the antenna width so the battery starts under the name.
        Image(systemName: context.state.antennaIconName)
          .font(.headline)
          .hidden()
          .accessibilityHidden(true)
        BatteryLabel(percent: context.state.batteryPercent)
      }
      Spacer(minLength: 0)
      if context.state.showsUnread {
        PacketRateLabel(rate: rate, style: .secondary)
          .layoutPriority(1)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}

/// Limited-width compact views are laid out in a 28pt circle.
private enum CompactIslandMetrics {
  static let limitedSymbolWidth: CGFloat = 22
  static let limitedSymbolHeight: CGFloat = 14
  /// Three semibold digits at this size stay inside the 28pt limited-width circle.
  static let limitedRatePointSize: CGFloat = 13
  static let limitedRateMinimumScale: CGFloat = 0.7
}

private struct ConnectionMark: View {
  let systemName: String
  let isConnected: Bool
  var limitedWidth = false

  var body: some View {
    symbol
      .foregroundStyle(isConnected ? .green : .orange)
  }

  @ViewBuilder
  private var symbol: some View {
    if limitedWidth {
      Image(systemName: systemName)
        .resizable()
        .scaledToFit()
        .frame(
          width: CompactIslandMetrics.limitedSymbolWidth,
          height: CompactIslandMetrics.limitedSymbolHeight
        )
    } else {
      Image(systemName: systemName)
    }
  }
}

@available(iOS 27, *)
private struct AdaptiveConnectionMark: View {
  @Environment(\.isDynamicIslandLimitedInWidth) private var isDynamicIslandLimitedInWidth
  let systemName: String
  let isConnected: Bool

  var body: some View {
    ConnectionMark(
      systemName: systemName,
      isConnected: isConnected,
      limitedWidth: isDynamicIslandLimitedInWidth
    )
  }
}

private struct CompactLabeledRate: View {
  let rate: Int

  var body: some View {
    Text("↓\(rate)/m")
      .monospacedDigit()
      .liveActivityInPlaceUpdate(rate: rate)
      .accessibilityLabel(Text("\(rate) packets per minute"))
  }
}

private struct CompactDigitRate: View {
  let rate: Int

  var body: some View {
    Text("\(rate)")
      .font(.system(size: CompactIslandMetrics.limitedRatePointSize, weight: .semibold))
      .monospacedDigit()
      .lineLimit(1)
      .minimumScaleFactor(CompactIslandMetrics.limitedRateMinimumScale)
      .liveActivityInPlaceUpdate(rate: rate)
      .accessibilityLabel(Text("\(rate) packets per minute"))
  }
}

@available(iOS 27, *)
private struct AdaptiveCompactRate: View {
  @Environment(\.isDynamicIslandLimitedInWidth) private var isDynamicIslandLimitedInWidth
  let rate: Int

  var body: some View {
    if isDynamicIslandLimitedInWidth {
      CompactDigitRate(rate: rate)
        .transition(.identity)
    } else {
      CompactLabeledRate(rate: rate)
        .transition(.identity)
    }
  }
}
