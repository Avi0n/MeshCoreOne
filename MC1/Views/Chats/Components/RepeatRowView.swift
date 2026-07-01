// MC1/Views/Chats/Components/RepeatRowView.swift
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
    HStack(alignment: .top) {
      // Left side: Repeater ID + name, hop count
      VStack(alignment: .leading, spacing: 2) {
        HStack {
          Text(repeatEntry.repeaterHashFormatted)
            .font(.body)
            .foregroundStyle(.secondary)
            .monospaced()
          Text(repeaterName)
            .font(.body)
        }

        Text(hopCountText)
          .font(.caption)
          .foregroundStyle(.secondary)
      }

      Spacer()

      // Right side: Signal bars and metrics
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
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Repeats.Row.accessibility(repeaterName))
    .accessibilityValue(L10n.Chats.Chats.Repeats.Row.accessibilityValue(signalQuality, repeatEntry.snrFormatted, repeatEntry.rssiFormatted))
  }

  // MARK: - Helpers

  private var snrQuality: SNRQuality {
    repeatEntry.snrQuality
  }

  private var signalColor: Color {
    snrQuality.color
  }

  /// Signal quality description for accessibility
  private var signalQuality: String {
    snrQuality.localizedLabel
  }

  /// Hop count text with proper pluralization
  private var hopCountText: String {
    let count = repeatEntry.hopCount
    return count == 1 ? L10n.Chats.Chats.Repeats.Hop.singular : L10n.Chats.Chats.Repeats.Hop.plural(count)
  }

  /// Resolve repeater name from repeaters list or show placeholder
  private var repeaterName: String {
    guard let repeaterHash = repeatEntry.repeaterHash else {
      return L10n.Chats.Chats.Repeats.unknownRepeater
    }

    if let repeater = RepeaterResolver.bestMatch(for: repeaterHash, in: repeaters, userLocation: userLocation) {
      return repeater.resolvableName
    }

    if let node = RepeaterResolver.bestMatch(for: repeaterHash, in: discoveredRepeaters, userLocation: userLocation) {
      return node.resolvableName
    }

    return L10n.Chats.Chats.Repeats.unknownRepeater
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
