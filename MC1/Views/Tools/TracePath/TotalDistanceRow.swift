import SwiftUI
import MC1Services

/// Row displaying total path distance with optional info sheet
struct TotalDistanceRow: View {
    @Bindable var viewModel: TracePathViewModel
    let result: TraceResult
    @Binding var showingDistanceInfo: Bool

    var body: some View {
        HStack {
            Text(L10n.Contacts.Contacts.Results.totalDistance)
                .foregroundStyle(.secondary)
            Spacer()

            if let distance = viewModel.totalPathDistance {
                HStack {
                    Text(formatDistance(distance))
                        .font(.body.monospacedDigit())
                    if viewModel.isDistanceUsingFallback {
                        Button(L10n.Contacts.Contacts.Results.distanceInfo, systemImage: "info.circle") {
                            showingDistanceInfo = true
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(L10n.Contacts.Contacts.Results.partialDistanceLabel)
                        .accessibilityHint(L10n.Contacts.Contacts.Results.partialDistanceHint)
                    }
                }
            } else {
                HStack {
                    Text(L10n.Contacts.Contacts.Results.unavailable)
                        .foregroundStyle(.secondary)
                    Button(L10n.Contacts.Contacts.Results.distanceInfo, systemImage: "info.circle") {
                        showingDistanceInfo = true
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(L10n.Contacts.Contacts.Results.distanceUnavailableLabel)
                    .accessibilityHint(L10n.Contacts.Contacts.Results.distanceInfoHint)
                }
            }
        }
        .sheet(isPresented: $showingDistanceInfo) {
            DistanceInfoSheetView(result: result, viewModel: viewModel, showingDistanceInfo: $showingDistanceInfo)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
    }

    private func formatDistance(_ meters: Double) -> String {
        let measurement = Measurement(value: meters, unit: UnitLength.meters)
        return measurement.formatted(.measurement(width: .abbreviated, usage: .road))
    }
}
