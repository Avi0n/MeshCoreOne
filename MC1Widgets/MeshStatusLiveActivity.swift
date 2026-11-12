import ActivityKit
import SwiftUI
import WidgetKit

struct MeshStatusLiveActivity: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: MeshStatusAttributes.self) { context in
      LockScreenView(context: context)
        .activityBackgroundTint(Color(red: 51 / 255, green: 102 / 255, blue: 136 / 255).opacity(0.4)) // slate blue (#336688)
    } dynamicIsland: { context in
      DynamicIsland {
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
            UnreadCountLabel(count: context.state.unreadCount, prominent: true)
          } else if context.state.isConnected {
            PacketRateLabel(
              packetsPerMinute: context.state.packetsPerMinute,
              isStale: context.isStale,
              prominent: true
            )
          } else {
            Text("Disconnected")
              .font(.caption)
              .foregroundStyle(.orange)
          }
        }
        DynamicIslandExpandedRegion(.bottom) {
          if context.state.showsSecondRow {
            LiveActivityDetailRow(context: context) {
              Image(systemName: context.state.antennaIconName)
                .font(.headline)
                .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
          }
        }
      } compactLeading: {
        CompactConnectionMark(
          systemName: context.state.antennaIconName,
          isConnected: context.state.isConnected
        )
      } compactTrailing: {
        if context.state.isConnected {
          CompactPacketRate(rate: context.isStale ? 0 : context.state.packetsPerMinute)
        } else {
          Text("—")
        }
      } minimal: {
        if context.state.isConnected {
          let rate = context.isStale ? 0 : context.state.packetsPerMinute
          Text("\(rate)")
            .font(.caption2)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(CompactIslandMetrics.limitedRateMinimumScale)
            .contentTransition(.numericText())
            .accessibilityLabel(Text("\(rate) packets per minute"))
        } else {
          Image(systemName: context.state.antennaIconName)
            .foregroundStyle(.orange)
        }
      }
      .widgetURL(URL(string: "meshcoreone://status"))
    }
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

private struct CompactConnectionMark: View {
  let systemName: String
  let isConnected: Bool

  var body: some View {
    if #available(iOS 27, *) {
      LimitedWidthConnectionMark(systemName: systemName, isConnected: isConnected)
    } else {
      connectionImage
    }
  }

  private var connectionImage: some View {
    Image(systemName: systemName)
      .foregroundStyle(isConnected ? .green : .orange)
  }
}

@available(iOS 27, *)
private struct LimitedWidthConnectionMark: View {
  @Environment(\.isDynamicIslandLimitedInWidth) private var isDynamicIslandLimitedInWidth
  let systemName: String
  let isConnected: Bool

  var body: some View {
    if isDynamicIslandLimitedInWidth {
      Image(systemName: systemName)
        .resizable()
        .scaledToFit()
        .frame(width: CompactIslandMetrics.limitedSymbolWidth, height: CompactIslandMetrics.limitedSymbolHeight)
        .foregroundStyle(isConnected ? .green : .orange)
    } else {
      Image(systemName: systemName)
        .foregroundStyle(isConnected ? .green : .orange)
    }
  }
}

private struct CompactPacketRate: View {
  let rate: Int

  var body: some View {
    if #available(iOS 27, *) {
      LimitedWidthPacketRate(rate: rate)
    } else {
      CompactLabeledRate(rate: rate)
    }
  }
}

private struct CompactLabeledRate: View {
  let rate: Int

  var body: some View {
    Text("↓\(rate)/m")
      .monospacedDigit()
      .contentTransition(.numericText())
      .accessibilityLabel(Text("\(rate) packets per minute"))
  }
}

@available(iOS 27, *)
private struct LimitedWidthPacketRate: View {
  @Environment(\.isDynamicIslandLimitedInWidth) private var isDynamicIslandLimitedInWidth
  let rate: Int

  var body: some View {
    if isDynamicIslandLimitedInWidth {
      Text("\(rate)")
        .font(.system(size: CompactIslandMetrics.limitedRatePointSize, weight: .semibold))
        .monospacedDigit()
        .lineLimit(1)
        .minimumScaleFactor(CompactIslandMetrics.limitedRateMinimumScale)
        .contentTransition(.numericText())
        .accessibilityLabel(Text("\(rate) packets per minute"))
    } else {
      CompactLabeledRate(rate: rate)
    }
  }
}
