import SwiftUI

struct TracePathHopRow: View {
    let hop: PathHop
    let hopNumber: Int

    var body: some View {
        VStack(alignment: .leading) {
            if let name = hop.resolvedName {
                Text(name)
                Text(hop.hashHex)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            } else {
                Text(hop.hashHex)
                    .font(.body.monospaced())
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Contacts.Contacts.Trace.List.hopLabel(hopNumber, hop.resolvedName ?? hop.hashHex))
        .accessibilityHint(L10n.Contacts.Contacts.Trace.List.hopHint)
    }
}
