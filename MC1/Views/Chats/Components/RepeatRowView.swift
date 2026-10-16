import CoreLocation
import MC1Services
import SwiftUI

/// Row displaying a single heard repeat with repeater info and signal quality.
struct RepeatRowView: View {
  let repeatEntry: MessageRepeatDTO
  let repeaters: [ContactDTO]
  let discoveredRepeaters: [DiscoveredNodeDTO]
  let userLocation: CLLocation?

  var body: some View {
    let resolution = repeaterResolution
    HStack(alignment: .top) {
      VStack(alignment: .leading, spacing: 2) {
        HStack(spacing: 6) {
          Text(repeatEntry.repeaterHashFormatted)
            .font(.body)
            .foregroundStyle(.secondary)
            .monospaced()
          Text(resolution.displayName)
            .font(.body)
          if resolution.matchKind == .fallback {
            FallbackMatchIndicatorView()
          }
        }

        Text(hopCountText)
          .font(.caption)
          .foregroundStyle(.secondary)
      }

      Spacer()

      VStack(alignment: .trailing, spacing: 2) {
        Image(systemName: "cellularbars", variableValue: repeatEntry.snrLevel)
          .foregroundStyle(signalColor)

        Text("SNR \(repeatEntry.snrFormatted)")
          .font(.caption)
          .foregroundStyle(.secondary)

        Text("RSSI \(repeatEntry.rssiFormatted)")
          .font(.caption)
          .foregroundStyle(.secondary)
      }
    }
    .padding(.vertical, 4)
    // .combine would swallow FallbackMatchIndicatorView; .contain keeps the popover a rotor stop.
    .accessibilityElement(children: resolution.isFallback ? .contain : .combine)
    .accessibilityLabel(L10n.Chats.Chats.Repeats.Row.accessibility(resolution.displayName))
    .accessibilityValue(L10n.Chats.Chats.Repeats.Row.accessibilityValue(signalQuality, repeatEntry.snrFormatted, repeatEntry.rssiFormatted))
  }

  /// NeighborNameResolver, not RepeaterResolver.bestMatch: only the former reports `.fallback`.
  static func resolution(
    repeaterHash: Data?,
    repeaters: [ContactDTO],
    discoveredRepeaters: [DiscoveredNodeDTO],
    userLocation: CLLocation?
  ) -> NodeNameResolution {
    guard let repeaterHash else {
      return NodeNameResolution(
        displayName: L10n.Chats.Chats.Repeats.unknownRepeater,
        matchKind: .unresolved
      )
    }
    return NeighborNameResolver.resolve(
      for: repeaterHash,
      contacts: repeaters,
      discoveredNodes: discoveredRepeaters,
      userLocation: userLocation
    ) ?? NodeNameResolution(
      displayName: L10n.Chats.Chats.Repeats.unknownRepeater,
      matchKind: .unresolved
    )
  }

  // MARK: - Helpers

  private var repeaterResolution: NodeNameResolution {
    Self.resolution(
      repeaterHash: repeatEntry.repeaterHash,
      repeaters: repeaters,
      discoveredRepeaters: discoveredRepeaters,
      userLocation: userLocation
    )
  }

  private var snrQuality: SNRQuality {
    repeatEntry.snrQuality
  }

  private var signalColor: Color {
    snrQuality.color
  }

  private var signalQuality: String {
    snrQuality.localizedLabel
  }

  private var hopCountText: String {
    let count = repeatEntry.hopCount
    return count == 1 ? L10n.Chats.Chats.Repeats.Hop.singular : L10n.Chats.Chats.Repeats.Hop.plural(count)
  }
}

#Preview {
  List {
    RepeatRowView(
      repeatEntry: MessageRepeatDTO(
        messageID: UUID(),
        receivedAt: Date(),
        pathNodes: Data([0xA3]),
        snr: 6.2,
        rssi: -85,
        rxLogEntryID: nil
      ),
      repeaters: [],
      discoveredRepeaters: [],
      userLocation: nil
    )

    RepeatRowView(
      repeatEntry: MessageRepeatDTO(
        messageID: UUID(),
        receivedAt: Date(),
        pathNodes: Data([0x7F]),
        snr: 2.1,
        rssi: -102,
        rxLogEntryID: nil
      ),
      repeaters: [],
      discoveredRepeaters: [],
      userLocation: nil
    )
  }
}
