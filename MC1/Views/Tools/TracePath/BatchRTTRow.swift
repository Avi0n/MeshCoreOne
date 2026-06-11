import SwiftUI
import MC1Services

/// Row displaying batch RTT statistics (average, min, max)
struct BatchRTTRow: View {
    @Bindable var viewModel: TracePathViewModel

    var body: some View {
        if let avg = viewModel.averageRTT,
           let min = viewModel.minRTT,
           let max = viewModel.maxRTT {
            HStack {
                Text(L10n.Contacts.Contacts.Results.avgRoundTrip)
                    .foregroundStyle(.secondary)
                Spacer()
                VStack(alignment: .trailing) {
                    Text("\(avg) ms")
                        .font(.body.monospacedDigit())
                    Text("(\(min) – \(max))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(L10n.Contacts.Contacts.Results.avgRTTLabel(avg, min, max))
        }
    }
}
