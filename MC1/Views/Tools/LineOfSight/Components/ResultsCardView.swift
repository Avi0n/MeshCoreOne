import MC1Services
import SwiftUI

/// Expandable card showing analysis results with progressive disclosure
struct ResultsCardView: View {
    let result: PathAnalysisResult
    @Binding var isExpanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.Tools.Tools.LineOfSight.results)
                .font(.headline)

            VStack(alignment: .leading, spacing: 0) {
                // Collapsed summary (always visible)
                collapsedContent

                // Expanded details
                if isExpanded {
                    Divider()
                        .padding(.vertical, 12)

                    expandedContent
                }
            }
            .padding()
        }
    }

    // MARK: - Collapsed Content

    private var collapsedContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack {
                    ClearanceStatusView(
                        status: result.clearanceStatus,
                        clearancePercent: result.worstClearancePercent
                    )

                    Spacer()

                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)

            // Blocked subtitle
            if result.clearanceStatus == .blocked {
                Text(ClearanceStatus.blockedSubtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Divider()

            HStack {
                Text(LOSFormatters.formatDistance(result.distanceMeters))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer()

                Text(LOSFormatters.formatPathLoss(result.totalPathLoss) + " " + L10n.Tools.Tools.LineOfSight.loss)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Expanded Content

    private var expandedContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            pathLossBreakdown
            clearanceDetails
            assumptionsFootnote
        }
    }

    // MARK: - Path Loss Breakdown

    private var pathLossBreakdown: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.Tools.Tools.LineOfSight.pathLossBreakdown)
                .font(.subheadline)
                .bold()

            Grid(alignment: .leading, verticalSpacing: 6) {
                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.freeSpaceLoss)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(LOSFormatters.formatPathLoss(result.freeSpacePathLoss))
                        .monospacedDigit()
                }

                if let diffractionText = LOSFormatters.formatDiffractionLoss(result.peakDiffractionLoss) {
                    GridRow {
                        Text(L10n.Tools.Tools.LineOfSight.diffractionLoss)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(diffractionText)
                            .monospacedDigit()
                    }
                }

                Divider()
                    .gridCellColumns(2)

                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.total)
                        .bold()
                    Spacer()
                    Text(LOSFormatters.formatPathLoss(result.totalPathLoss))
                        .monospacedDigit()
                        .bold()
                }
            }
            .font(.subheadline)
        }
    }

    // MARK: - Clearance Details

    private var clearanceDetails: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.Tools.Tools.LineOfSight.clearance)
                .font(.subheadline)
                .bold()

            Grid(alignment: .leading, verticalSpacing: 6) {
                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.worstClearanceShort)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(LOSFormatters.formatClearancePercent(result.worstClearancePercent))%")
                        .monospacedDigit()
                }

                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.obstructionsFound)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(result.obstructionPoints.count)")
                        .monospacedDigit()
                }
            }
            .font(.subheadline)
        }
    }

    // MARK: - Assumptions Footnote

    private var assumptionsFootnote: some View {
        HStack(spacing: 6) {
            Image(systemName: "info.circle")
                .foregroundStyle(.secondary)

            Text(LOSFormatters.formatAssumptions(
                frequencyMHz: result.frequencyMHz,
                k: result.refractionK
            ))
            .foregroundStyle(.secondary)
        }
        .font(.caption)
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Preview

#Preview("Clear Path") {
    let result = PathAnalysisResult(
        distanceMeters: 12400,
        freeSpacePathLoss: 118.2,
        peakDiffractionLoss: 0,
        totalPathLoss: 118.2,
        clearanceStatus: .clear,
        worstClearancePercent: 92,
        obstructionPoints: [],
        frequencyMHz: 906,
        refractionK: 1.33
    )

    return ResultsCardView(result: result, isExpanded: .constant(false))
        .padding()
}

#Preview("Partial Obstruction - Expanded") {
    let result = PathAnalysisResult(
        distanceMeters: 12400,
        freeSpacePathLoss: 118.2,
        peakDiffractionLoss: 8.4,
        totalPathLoss: 126.6,
        clearanceStatus: .partialObstruction,
        worstClearancePercent: 47,
        obstructionPoints: [
            ObstructionPoint(distanceFromAMeters: 5000, obstructionHeightMeters: 12, fresnelClearancePercent: 47),
            ObstructionPoint(distanceFromAMeters: 7200, obstructionHeightMeters: 8, fresnelClearancePercent: 55)
        ],
        frequencyMHz: 906,
        refractionK: 1.33
    )

    return ResultsCardView(result: result, isExpanded: .constant(true))
        .padding()
}

#Preview("Blocked - Expanded") {
    let result = PathAnalysisResult(
        distanceMeters: 8500,
        freeSpacePathLoss: 112.5,
        peakDiffractionLoss: 22.3,
        totalPathLoss: 134.8,
        clearanceStatus: .blocked,
        worstClearancePercent: -15,
        obstructionPoints: [
            ObstructionPoint(distanceFromAMeters: 4200, obstructionHeightMeters: 35, fresnelClearancePercent: -15)
        ],
        frequencyMHz: 915,
        refractionK: 1.33
    )

    return ResultsCardView(result: result, isExpanded: .constant(true))
        .padding()
}

// MARK: - Relay Results Card View

/// Card view for relay path analysis results showing dual-segment analysis
struct RelayResultsCardView: View {
    let result: RelayPathAnalysisResult
    @Binding var isExpanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.Tools.Tools.LineOfSight.results)
                .font(.headline)

            VStack(alignment: .leading, spacing: 0) {
                collapsedContent

                if isExpanded {
                    Divider()
                        .padding(.vertical, 12)
                    expandedContent
                }
            }
            .padding()
        }
    }

    // MARK: - Collapsed Content

    private var collapsedContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack {
                    ClearanceStatusView(
                        status: result.overallStatus,
                        clearancePercent: min(
                            result.segmentAR.worstClearancePercent,
                            result.segmentRB.worstClearancePercent
                        )
                    )
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)

            Divider()

            // Segment summary
            VStack(alignment: .leading, spacing: 4) {
                segmentRow(segment: result.segmentAR)
                segmentRow(segment: result.segmentRB)
            }

            Divider()

            HStack {
                Text("\(L10n.Tools.Tools.LineOfSight.total): \(LOSFormatters.formatDistance(result.totalDistanceMeters))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            }
        }
    }

    private func segmentRow(segment: SegmentAnalysisResult) -> some View {
        HStack {
            Circle()
                .fill(segment.clearanceStatus.color)
                .frame(width: 8, height: 8)

            Text("\(segment.startLabel) \u{2192} \(segment.endLabel)")
                .font(.caption)

            Text(segment.clearanceStatus.localizedName)
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()

            Text(LOSFormatters.formatDistance(segment.distanceMeters))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Expanded Content

    private var expandedContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            segmentDetailView(segment: result.segmentAR)
            segmentDetailView(segment: result.segmentRB)
        }
    }

    private func segmentDetailView(segment: SegmentAnalysisResult) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(segment.startLabel) \u{2192} \(segment.endLabel)")
                .font(.subheadline)
                .bold()

            Grid(alignment: .leading, verticalSpacing: 4) {
                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.status)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(segment.clearanceStatus.localizedName)
                }
                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.distance)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(LOSFormatters.formatDistance(segment.distanceMeters))
                }
                GridRow {
                    Text(L10n.Tools.Tools.LineOfSight.worstClearanceShort)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(LOSFormatters.formatClearancePercent(segment.worstClearancePercent))%")
                }
            }
            .font(.caption)
        }
        .padding()
    }
}

// MARK: - Relay Results Previews

#Preview("Relay - Clear Path") {
    let result = RelayPathAnalysisResult(
        segmentAR: SegmentAnalysisResult(
            startLabel: "A",
            endLabel: "R",
            clearanceStatus: .clear,
            distanceMeters: 5200,
            worstClearancePercent: 85
        ),
        segmentRB: SegmentAnalysisResult(
            startLabel: "R",
            endLabel: "B",
            clearanceStatus: .clear,
            distanceMeters: 7200,
            worstClearancePercent: 92
        )
    )

    return RelayResultsCardView(result: result, isExpanded: .constant(false))
        .padding()
}

#Preview("Relay - Mixed Clearance - Expanded") {
    let result = RelayPathAnalysisResult(
        segmentAR: SegmentAnalysisResult(
            startLabel: "A",
            endLabel: "R",
            clearanceStatus: .clear,
            distanceMeters: 5200,
            worstClearancePercent: 85
        ),
        segmentRB: SegmentAnalysisResult(
            startLabel: "R",
            endLabel: "B",
            clearanceStatus: .partialObstruction,
            distanceMeters: 7200,
            worstClearancePercent: 45
        )
    )

    return RelayResultsCardView(result: result, isExpanded: .constant(true))
        .padding()
}
