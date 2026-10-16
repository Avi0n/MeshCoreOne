import MC1Services
import SwiftUI

struct NodeDiscoveryRowView: View {
  let result: NodeDiscoveryResult
  var isAdded = false
  var isAdding = false
  var onAdd: (() -> Void)?

  var body: some View {
    HStack {
      avatarView
        .padding(.trailing, 4)

      VStack(alignment: .leading, spacing: 2) {
        Text(result.name)
          .font(.body)
          .bold()
          .lineLimit(1)

        hexLine

        MetricsLine(result: result)
      }

      if let onAdd {
        Spacer()
        if isAdded {
          Button(L10n.Contacts.Contacts.Discovery.added) {}
            .buttonStyle(.bordered)
            .disabled(true)
            .accessibilityLabel(L10n.Contacts.Contacts.Discovery.addedAccessibility)
        } else {
          Button(L10n.Contacts.Contacts.Discovery.add) {
            onAdd()
          }
          .buttonStyle(.borderedProminent)
          .disabled(isAdding)
        }
      }
    }
    .padding(.vertical, 4)
  }

  @ViewBuilder
  private var avatarView: some View {
    if result.scanFilter == .repeaters {
      NodeAvatar(publicKey: result.publicKey, role: .repeater, size: 40)
    } else {
      ZStack {
        Circle().fill(.gray.opacity(0.3))
        Image(systemName: "sensor")
          .font(.body.weight(.semibold))
          .foregroundStyle(.secondary)
      }
      .frame(width: 40, height: 40)
    }
  }

  private var hexLine: some View {
    let hex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    return Text(hex)
      .font(.caption)
      .monospaced()
      .foregroundStyle(.tertiary)
      .lineLimit(1)
      .truncationMode(.middle)
  }
}

// MARK: - Metrics Line

extension NodeDiscoveryRowView {
  private struct MetricsLine: View {
    let result: NodeDiscoveryResult

    var body: some View {
      let nodeTypeLabel = result.scanFilter == .repeaters
        ? L10n.Tools.Tools.NodeDiscovery.typeRepeater
        : L10n.Tools.Tools.NodeDiscovery.typeSensor

      let snrDown = L10n.Tools.Tools.NodeDiscovery.snrDown(
        result.snr.formatted(.number.precision(.fractionLength(1)))
      )
      let snrUp = L10n.Tools.Tools.NodeDiscovery.snrUp(
        result.snrIn.formatted(.number.precision(.fractionLength(1)))
      )
      let rssiText = L10n.Tools.Tools.NodeDiscovery.rssi(
        result.rssi.formatted()
      )

      Text("\(nodeTypeLabel) · \(snrDown) \(snrUp) · \(rssiText)")
        .font(.caption)
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
  }
}
