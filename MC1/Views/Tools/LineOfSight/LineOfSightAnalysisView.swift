import MC1Services
import SwiftUI

/// Analysis controls and results for one Line of Sight workspace. The workspace
/// owns editor identity, expansion, and sheet state; this view only renders them.
struct LineOfSightAnalysisView: View {
  @Bindable var viewModel: LineOfSightViewModel
  var showsExpandedResults: Bool
  @Binding var copyHapticTrigger: Int
  @Binding var editingPoint: PointID?
  @Binding var isResultsExpanded: Bool
  @Binding var isRFSettingsExpanded: Bool
  @Binding var showDragHint: Bool
  @Binding var repeaterMarkerCenter: CGPoint?
  var onRelocate: () -> Void
  var onAnalyze: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      PointsSummarySectionView(
        viewModel: viewModel,
        copyHapticTrigger: $copyHapticTrigger,
        editingPoint: $editingPoint,
        onRelocate: onRelocate
      )

      if viewModel.canAnalyze, !hasAnalysisResult {
        analyzeButton
        RFSettingsSectionView(viewModel: viewModel, isRFSettingsExpanded: $isRFSettingsExpanded)
      }

      if case let .result(result) = viewModel.analysisStatus {
        analyzeButton
        ResultsCardView(result: result, isExpanded: $isResultsExpanded)
        expandedResults
      }

      if case let .relayResult(result) = viewModel.analysisStatus {
        analyzeButton
        RelayResultsCardView(result: result, isExpanded: $isResultsExpanded)
        expandedResults
      }

      if case let .error(message) = viewModel.analysisStatus {
        AnalysisErrorView(
          message: message,
          hasRepeater: viewModel.repeaterPoint != nil,
          onRetry: {
            if viewModel.repeaterPoint != nil {
              viewModel.analyzeWithRepeater()
            } else {
              viewModel.analyze()
            }
          }
        )
      }
    }
    .padding()
    .controlSize(.small)
  }

  @ViewBuilder
  private var expandedResults: some View {
    if showsExpandedResults {
      TerrainProfileSectionView(
        viewModel: viewModel,
        showDragHint: $showDragHint,
        repeaterMarkerCenter: $repeaterMarkerCenter
      )
      RFSettingsSectionView(viewModel: viewModel, isRFSettingsExpanded: $isRFSettingsExpanded)
    }
  }

  private var analyzeButton: some View {
    AnalyzeButton(viewModel: viewModel, onAnalyze: onAnalyze)
  }

  private var hasAnalysisResult: Bool {
    if case .result = viewModel.analysisStatus { return true }
    if case .relayResult = viewModel.analysisStatus { return true }
    return false
  }

  private struct AnalyzeButton: View {
    var viewModel: LineOfSightViewModel
    let onAnalyze: () -> Void

    var body: some View {
      Button {
        viewModel.shouldAutoZoomOnNextResult = true
        onAnalyze()
        if viewModel.repeaterPoint != nil {
          viewModel.analyzeWithRepeater()
        } else {
          viewModel.analyze()
        }
      } label: {
        if viewModel.isAnalyzing {
          HStack {
            ProgressView()
              .controlSize(.small)
            Text(L10n.Tools.Tools.LineOfSight.analyzing)
          }
          .frame(maxWidth: .infinity)
        } else {
          Label(L10n.Tools.Tools.LineOfSight.analyze, systemImage: "waveform.path")
            .frame(maxWidth: .infinity)
        }
      }
      .liquidGlassProminentButtonStyle()
      .controlSize(.large)
      .disabled(viewModel.isAnalyzing)
    }
  }
}
