import MC1Services
import SwiftUI

/// Lightweight read-only detail for a discovered node, with Add to Nodes.
struct DiscoveredNodeDetailSheet: View {
  @Environment(\.dismiss) private var dismiss
  let node: DiscoveredNodeDTO
  let isAdding: Bool
  let onAdd: () -> Void

  var body: some View {
    NavigationStack {
      List {
        Section(L10n.Map.Map.Detail.Section.contactInfo) {
          LabeledContent(L10n.Map.Map.Detail.name, value: node.name)

          LabeledContent(L10n.Map.Map.Detail.type) {
            HStack {
              Image(systemName: node.nodeType.iconSystemName)
              Text(node.nodeType.localizedName)
            }
            .foregroundStyle(node.nodeType.displayColor)
          }

          LabeledContent(L10n.Map.Map.Callout.discovered) {
            Text(L10n.Map.Map.Pin.Accessibility.discovered)
              .foregroundStyle(.secondary)
          }

          LabeledContent(L10n.Map.Map.Detail.lastAdvert) {
            RelativeTimestampText(date: node.lastHeard)
          }

          VStack(alignment: .leading, spacing: 4) {
            Text(L10n.Map.Map.Detail.publicKey)
              .font(.caption)
              .foregroundStyle(.secondary)
            Text(node.publicKey.uppercaseHexString(separator: " "))
              .font(.system(.caption, design: .monospaced))
              .textSelection(.enabled)
          }
        }

        if node.hasLocation {
          Section(L10n.Map.Map.Detail.Section.location) {
            LabeledContent(L10n.Map.Map.Detail.latitude) {
              Text(node.latitude, format: .number.precision(.fractionLength(6)))
            }
            LabeledContent(L10n.Map.Map.Detail.longitude) {
              Text(node.longitude, format: .number.precision(.fractionLength(6)))
            }
          }
        }

        Section {
          Button {
            onAdd()
          } label: {
            Label(L10n.Map.Map.DiscoveredDetail.add, systemImage: "plus.circle.fill")
          }
          .disabled(isAdding)
        }
      }
      .navigationTitle(L10n.Map.Map.DiscoveredDetail.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Map.Map.Common.done) {
            dismiss()
          }
        }
      }
    }
  }
}
