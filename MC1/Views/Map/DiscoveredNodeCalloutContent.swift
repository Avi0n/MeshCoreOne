import MC1Services
import SwiftUI

/// Popover callout for a discovered (not yet added) map pin.
struct DiscoveredNodeCalloutContent: View {
  @Environment(\.appState) private var appState
  let node: DiscoveredNodeDTO
  let isAdding: Bool
  let onDetail: () -> Void
  let onAdd: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      (Text(idPrefixHex)
        .monospaced()
        .foregroundStyle(.secondary)
        + Text(" \(node.name)"))
        .font(.headline)
        .accessibilityLabel("\(L10n.Map.Map.Callout.discovered), \(node.name)")

      HStack(spacing: 6) {
        Image(systemName: node.nodeType.iconSystemName)
          .foregroundStyle(node.nodeType.displayColor)
        Text(typeDisplayName)
          .font(.subheadline)
          .foregroundStyle(.secondary)
        Text("·")
          .font(.subheadline)
          .foregroundStyle(.secondary)
        Text(L10n.Map.Map.Callout.discovered)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel("\(typeDisplayName), \(L10n.Map.Map.Pin.Accessibility.discovered)")

      Divider()

      VStack(spacing: 6) {
        Button(L10n.Map.Map.Callout.details, systemImage: "info.circle", action: onDetail)
          .buttonStyle(.bordered)
          .accessibilityHint(node.name)

        Button(L10n.Map.Map.Callout.add, systemImage: "plus.circle", action: onAdd)
          .buttonStyle(.bordered)
          .disabled(isAdding)
          .accessibilityHint(node.name)
      }
      .frame(maxWidth: .infinity)
    }
    .padding(12)
    .frame(minWidth: 160)
  }

  private var idPrefixHex: String {
    let hashSize = appState.connectedDevice?.hashSize ?? 1
    return node.publicKey.prefix(hashSize).uppercaseHexString()
  }

  private var typeDisplayName: String {
    switch node.nodeType {
    case .chat:
      L10n.Map.Map.Callout.NodeKind.contact
    case .repeater:
      L10n.Map.Map.Callout.NodeKind.repeater
    case .room:
      L10n.Map.Map.Callout.NodeKind.room
    }
  }
}
