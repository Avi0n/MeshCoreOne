import SwiftUI

struct TerrainProfileSectionView: View {
    var viewModel: LineOfSightViewModel
    @Binding var showDragHint: Bool
    @Binding var repeaterMarkerCenter: CGPoint?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(L10n.Tools.Tools.LineOfSight.terrainProfile)
                    .font(.headline)

                Spacer()

                Label(
                    L10n.Tools.Tools.LineOfSight.earthCurvature(LOSFormatters.formatKFactor(viewModel.refractionK)),
                    systemImage: "globe"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            TerrainProfileCanvas(
                elevationProfile: viewModel.terrainElevationProfile,
                profileSamples: viewModel.profileSamples,
                profileSamplesRB: viewModel.profileSamplesRB,
                // Show repeater marker for both on-path and off-path
                repeaterPathFraction: viewModel.repeaterVisualizationPathFraction,
                repeaterHeight: viewModel.repeaterPoint.map { $0.additionalHeight },
                // Only enable drag for on-path repeaters
                onRepeaterDrag: viewModel.repeaterPoint?.isOnPath == true ? { pathFraction in
                    viewModel.updateRepeaterPosition(pathFraction: pathFraction)
                    viewModel.analyzeWithRepeater()
                } : nil,
                onRepeaterMarkerPosition: { center in
                    repeaterMarkerCenter = center
                },
                // Off-path segment distances for separator and labels
                segmentARDistanceMeters: viewModel.segmentARDistanceMeters,
                segmentRBDistanceMeters: viewModel.segmentRBDistanceMeters
            )
            .overlay {
                if showDragHint, let center = repeaterMarkerCenter {
                    Text(L10n.Tools.Tools.LineOfSight.dragToAdjust)
                        .font(.caption)
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.regularMaterial, in: .capsule)
                        .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
                        .transition(.opacity.combined(with: .scale))
                        .position(x: center.x, y: center.y + 30)
                }
            }
        }
    }
}
