import SwiftUI

/// A single read-only metadata row in a message actions sheet: a label with an
/// optional leading icon. Shared across the chat and room actions sheets.
struct ActionInfoRow: View {
    let text: String
    var icon: String?

    var body: some View {
        HStack {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(.secondary)
            }
            Text(text)
            Spacer()
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }
}
