import SwiftUI

struct PointsSummarySectionView: View {
    var viewModel: LineOfSightViewModel
    @Binding var copyHapticTrigger: Int
    @Binding var editingPoint: PointID?
    let onRelocate: () -> Void

    private var isRelocating: Bool { viewModel.relocatingPoint != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header with optional cancel button
            HStack {
                Text(L10n.Tools.Tools.LineOfSight.points)
                    .font(.headline)

                Spacer()

                if isRelocating {
                    Button(L10n.Tools.Tools.LineOfSight.cancel) {
                        viewModel.relocatingPoint = nil
                    }
                    .liquidGlassSecondaryButtonStyle()
                    .controlSize(.small)
                }
            }

            // Show relocating message OR point rows
            if let relocatingPoint = viewModel.relocatingPoint {
                relocatingMessageView(for: relocatingPoint)
            } else {
                // Point A row
                PointRowView(
                    viewModel: viewModel,
                    label: "A",
                    color: .blue,
                    point: viewModel.pointA,
                    pointID: .pointA,
                    copyHapticTrigger: $copyHapticTrigger,
                    editingPoint: $editingPoint,
                    onRelocate: onRelocate,
                    onClear: { viewModel.clearPointA() }
                )

                // Repeater row (placeholder or full, positioned between A and B)
                // Inline check for repeaterPoint to ensure SwiftUI properly tracks the dependency
                if let repeater = viewModel.repeaterPoint {
                    RepeaterRowView(
                        viewModel: viewModel,
                        copyHapticTrigger: $copyHapticTrigger,
                        editingPoint: $editingPoint,
                        onRelocate: onRelocate
                    )
                        .id("repeater-\(repeater.coordinate.latitude)-\(repeater.coordinate.longitude)")
                } else if viewModel.shouldShowRepeaterPlaceholder {
                    AddRepeaterRowView {
                        viewModel.addRepeater()
                        viewModel.analyzeWithRepeater()
                    }
                }

                // Point B row
                PointRowView(
                    viewModel: viewModel,
                    label: "B",
                    color: .green,
                    point: viewModel.pointB,
                    pointID: .pointB,
                    copyHapticTrigger: $copyHapticTrigger,
                    editingPoint: $editingPoint,
                    onRelocate: onRelocate,
                    onClear: { viewModel.clearPointB() }
                )

                if viewModel.pointA == nil || viewModel.pointB == nil {
                    Text(L10n.Tools.Tools.LineOfSight.selectPointsHint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if viewModel.elevationFetchFailed {
                    Label(
                        L10n.Tools.Tools.LineOfSight.elevationUnavailable,
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.caption)
                    .foregroundStyle(.orange)
                }
            }
        }
    }

    @ViewBuilder
    private func relocatingMessageView(for pointID: PointID) -> some View {
        let pointName: String = switch pointID {
        case .pointA: L10n.Tools.Tools.LineOfSight.pointA
        case .pointB: L10n.Tools.Tools.LineOfSight.pointB
        case .repeater: L10n.Tools.Tools.LineOfSight.repeater
        }

        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.Tools.Tools.LineOfSight.relocating(pointName))
                .font(.subheadline)
                .bold()

            Text(L10n.Tools.Tools.LineOfSight.tapMapInstruction)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(L10n.Tools.Tools.LineOfSight.relocating(pointName)) \(L10n.Tools.Tools.LineOfSight.tapMapInstruction)")
    }
}
