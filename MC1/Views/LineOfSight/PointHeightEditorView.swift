import SwiftUI

struct PointHeightEditorView: View {
    var viewModel: LineOfSightViewModel
    let point: SelectedPoint
    let pointID: PointID

    var body: some View {
        HeightEditorGrid(
            groundElevation: point.groundElevation,
            additionalHeight: Binding(
                get: { point.additionalHeight },
                set: { viewModel.updateAdditionalHeight(for: pointID, meters: $0) }
            ),
            range: 0.0...200.0
        )
    }
}
