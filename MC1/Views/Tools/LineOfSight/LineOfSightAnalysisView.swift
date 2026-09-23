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
}

// MARK: - Frequency Input Row

/// Extracted so `@FocusState` is local; parent-declared focus does not work in sheet content.
struct FrequencyInputRow: View {
  @Bindable var viewModel: LineOfSightViewModel
  @FocusState private var isFocused: Bool
  @State private var text: String = ""

  var body: some View {
    HStack {
      Label(L10n.Tools.Tools.LineOfSight.frequency, systemImage: "antenna.radiowaves.left.and.right")
        .foregroundStyle(.secondary)
      Spacer()
      TextField(L10n.Tools.Tools.LineOfSight.mhz, text: $text)
        .keyboardType(.decimalPad)
        .multilineTextAlignment(.trailing)
        .frame(width: 80)
        .focused($isFocused)
        .onChange(of: text) { _, newValue in
          let replaced = newValue.replacing(",", with: ".")
          if replaced != newValue {
            text = replaced
          }
        }
        .onChange(of: isFocused) { _, focused in
          if focused {
            text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
          } else {
            commitEdit()
          }
        }

      Text(L10n.Tools.Tools.LineOfSight.mhz)
        .foregroundStyle(.secondary)

      if isFocused {
        Button {
          commitEdit()
          isFocused = false
        } label: {
          Image(systemName: "checkmark.circle.fill")
            .foregroundStyle(.green)
            .font(.title2)
        }
        .buttonStyle(.plain)
      }
    }
    .onAppear {
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
    }
  }

  private func commitEdit() {
    guard let parsed = viewModel.parseFrequency(text) else {
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
      return
    }
    viewModel.frequencyMHz = parsed
    viewModel.commitFrequencyChange()
  }
}

// MARK: - Analyze Button

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
