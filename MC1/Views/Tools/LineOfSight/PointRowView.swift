import SwiftUI

struct PointRowView: View {
    var viewModel: LineOfSightViewModel
    let label: String
    let color: Color
    let point: SelectedPoint?
    let pointID: PointID
    @Binding var copyHapticTrigger: Int
    @Binding var editingPoint: PointID?
    let onRelocate: () -> Void
    let onClear: () -> Void

    private var isEditing: Bool { editingPoint == pointID }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header row (always visible)
            HStack {
                // Point marker
                Circle()
                    .fill(point != nil ? color : .gray.opacity(0.3))
                    .frame(width: 24, height: 24)
                    .overlay {
                        Text(label)
                            .font(.caption)
                            .bold()
                            .foregroundStyle(.white)
                    }

                // Point info
                if let point {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(point.displayName)
                            .font(.subheadline)
                            .lineLimit(1)

                        if point.isLoadingElevation {
                            HStack(spacing: 4) {
                                ProgressView()
                                    .controlSize(.mini)
                                Text(L10n.Tools.Tools.LineOfSight.loadingElevation)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } else if let elevation = point.groundElevation {
                            Text(Measurement(
                                value: elevation + point.additionalHeight,
                                unit: UnitLength.meters
                            ).formatted())
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Spacer()

                    PointRowButtonsView(
                        viewModel: viewModel,
                        pointID: pointID,
                        isEditing: isEditing,
                        copyHapticTrigger: $copyHapticTrigger,
                        editingPoint: $editingPoint,
                        onRelocate: onRelocate,
                        onClear: onClear
                    )
                } else {
                    Text(L10n.Tools.Tools.LineOfSight.notSelected)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Spacer()
                }
            }

            // Expanded editor (when editing)
            if isEditing, let point {
                Divider()

                PointHeightEditorView(viewModel: viewModel, point: point, pointID: pointID)
            }
        }
        .padding(12)
        .animation(.easeInOut(duration: 0.2), value: isEditing)
    }
}
