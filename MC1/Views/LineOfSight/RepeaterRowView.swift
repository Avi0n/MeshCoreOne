import SwiftUI

struct RepeaterRowView: View {
    var viewModel: LineOfSightViewModel
    @Binding var copyHapticTrigger: Int
    @Binding var editingPoint: PointID?
    let onRelocate: () -> Void

    private var isEditing: Bool { editingPoint == .repeater }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header row
            HStack {
                // Repeater marker (purple)
                Circle()
                    .fill(.purple)
                    .frame(width: 24, height: 24)
                    .overlay {
                        Text("R")
                            .font(.caption)
                            .bold()
                            .foregroundStyle(.white)
                    }

                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.Tools.Tools.LineOfSight.repeater)
                        .font(.subheadline)
                        .lineLimit(1)

                    if let elevation = viewModel.repeaterGroundElevation {
                        let totalHeight = elevation + (viewModel.repeaterPoint?.additionalHeight ?? 0.0)
                        Text(Measurement(
                            value: totalHeight,
                            unit: UnitLength.meters
                        ).formatted())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                PointRowButtonsView(
                    viewModel: viewModel,
                    pointID: .repeater,
                    isEditing: isEditing,
                    copyHapticTrigger: $copyHapticTrigger,
                    editingPoint: $editingPoint,
                    onRelocate: onRelocate,
                    onClear: { viewModel.clearRepeater() }
                )
            }

            // Expanded editor
            if isEditing, let repeaterPoint = viewModel.repeaterPoint {
                Divider()
                RepeaterHeightEditorView(viewModel: viewModel, repeaterPoint: repeaterPoint)
            }
        }
        .padding(12)
        .animation(.easeInOut(duration: 0.2), value: isEditing)
    }
}
