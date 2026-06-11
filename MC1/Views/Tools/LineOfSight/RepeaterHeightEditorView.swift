import SwiftUI

struct RepeaterHeightEditorView: View {
    var viewModel: LineOfSightViewModel
    let repeaterPoint: RepeaterPoint

    var body: some View {
        HeightEditorGrid(
            groundElevation: viewModel.repeaterGroundElevation,
            additionalHeight: Binding(
                get: { repeaterPoint.additionalHeight },
                set: { viewModel.updateRepeaterHeight(meters: $0) }
            ),
            range: 0.0...200.0,
            onHeightChanged: { viewModel.analyzeWithRepeater() }
        )
    }
}
