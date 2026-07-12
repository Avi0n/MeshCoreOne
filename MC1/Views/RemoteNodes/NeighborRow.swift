import MC1Services
import SwiftUI

struct NeighborRow: View {
  let neighbor: NeighbourInfo
  let displayName: String
  let matchKind: NodeNameMatchKind
  let previousNeighbor: NeighborSnapshotEntry?
  let isNew: Bool

  init(
    neighbor: NeighbourInfo,
    displayName: String,
    matchKind: NodeNameMatchKind,
    previousNeighbor: NeighborSnapshotEntry? = nil,
    isNew: Bool = false
  ) {
    self.neighbor = neighbor
    self.displayName = displayName
    self.matchKind = matchKind
    self.previousNeighbor = previousNeighbor
    self.isNew = isNew
  }

  var body: some View {
    HStack {
      VStack(alignment: .leading, spacing: 2) {
        HStack(spacing: 4) {
          Text(displayName)

          if isNew {
            Text(L10n.RemoteNodes.RemoteNodes.History.new)
              .font(.caption2)
              .bold()
              .foregroundStyle(.green)
          }

          if matchKind == .fallback {
            FallbackMatchIndicatorView(
              accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.possibleMatch,
              accessibilityHint: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation,
              title: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchTitle,
              explanation: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation
            )
          }
        }

        HStack(spacing: 4) {
          Text(firstKeyByte)
            .font(.system(.caption2, design: .monospaced))
          Text("·")
          Text(lastSeenText)
            .font(.caption2)
        }
        .foregroundStyle(.secondary)
      }

      Spacer()

      VStack(alignment: .trailing, spacing: 2) {
        Image(systemName: "cellularbars", variableValue: snrLevel)
          .foregroundStyle(snrColor)

        Text(L10n.RemoteNodes.RemoteNodes.Status.snrFormat(neighbor.snr.formatted(.number.precision(.fractionLength(1)))))
          .font(.caption)
          .foregroundStyle(.secondary)

        if let previous = previousNeighbor {
          let snrDelta = neighbor.snr - previous.snr
          if abs(snrDelta) >= 0.1 {
            StatusDeltaView(delta: snrDelta, higherIsBetter: true, unit: " dB", fractionDigits: 1)
          }
        }
      }
    }
  }

  private var firstKeyByte: String {
    guard let firstByte = neighbor.publicKeyPrefix.first else { return "" }
    return Data([firstByte]).uppercaseHexString()
  }

  private var lastSeenText: String {
    let seconds = neighbor.secondsAgo
    if seconds < 60 {
      return L10n.RemoteNodes.RemoteNodes.Status.secondsAgo(seconds)
    } else if seconds < 3600 {
      return L10n.RemoteNodes.RemoteNodes.Status.minutesAgo(seconds / 60)
    } else {
      return L10n.RemoteNodes.RemoteNodes.Status.hoursAgo(seconds / 3600)
    }
  }

  private var snrQuality: SNRQuality {
    SNRQuality(snr: neighbor.snr)
  }

  private var snrLevel: Double {
    snrQuality.barLevel
  }

  private var snrColor: Color {
    snrQuality.color
  }
}
