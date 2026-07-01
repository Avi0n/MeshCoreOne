import MC1Services
import SwiftUI

/// Section displaying trace result hops, RTT info, distance, and save action
struct TraceResultsSectionView: View {
  @Environment(\.appTheme) private var theme
  let result: TraceResult
  @Bindable var viewModel: TracePathViewModel
  @Binding var saveHapticTrigger: Int
  @Binding var showingDistanceInfo: Bool

  var body: some View {
    Section {
      if result.success {
        ForEach(Array(result.hops.enumerated()), id: \.element.id) { index, hop in
          TraceResultHopRow(
            hop: hop,
            hopIndex: index,
            batchStats: viewModel.batchEnabled ? viewModel.hopStats(at: index) : nil,
            latestSNR: viewModel.batchEnabled ? viewModel.latestHopSNR(at: index) : nil,
            isBatchInProgress: viewModel.isBatchInProgress
          )
        }

        // Batch status row (progress or completion)
        if viewModel.batchEnabled, viewModel.isBatchInProgress || viewModel.isBatchComplete {
          HStack {
            if viewModel.isBatchComplete {
              Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
              Text(L10n.Contacts.Contacts.Results.batchSuccess(viewModel.successCount, viewModel.batchSize))
                .foregroundStyle(.secondary)
            } else {
              ProgressView()
                .controlSize(.small)
              Text(L10n.Contacts.Contacts.Results.batchProgress(viewModel.currentTraceIndex, viewModel.batchSize))
                .foregroundStyle(.secondary)
            }
            Spacer()
          }
          .padding(.vertical, 4)
          .accessibilityElement(children: .combine)
          .accessibilityLabel(
            viewModel.isBatchComplete
              ? L10n.Contacts.Contacts.Results.batchCompleteLabel(viewModel.successCount, viewModel.batchSize)
              : L10n.Contacts.Contacts.Results.batchProgressLabel(viewModel.currentTraceIndex, viewModel.batchSize)
          )
          .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
        }

        // Duration row with batch or single display
        if viewModel.batchEnabled, viewModel.successCount > 0 {
          BatchRTTRow(viewModel: viewModel)
        } else if viewModel.isRunningSavedPath, let previous = viewModel.previousRun {
          ComparisonRowView(currentMs: result.durationMs, previousRun: previous, viewModel: viewModel)
        } else {
          HStack {
            Text(L10n.Contacts.Contacts.PathDetail.roundTrip)
              .foregroundStyle(.secondary)
            Spacer()
            Text("\(result.durationMs) ms")
              .font(.body.monospacedDigit())
          }
        }

        // Total distance row
        TotalDistanceRow(viewModel: viewModel, result: result, showingDistanceInfo: $showingDistanceInfo)

        // Save path action (only for successful traces when not running a saved path)
        if !viewModel.isRunningSavedPath {
          SavePathRowView(viewModel: viewModel, saveHapticTrigger: $saveHapticTrigger)
        }
      } else if let error = result.errorMessage {
        Label(error, systemImage: "exclamationmark.triangle")
          .foregroundStyle(.orange)
      }
    }
    .themedRowBackground(theme)
  }
}
