import SwiftUI

/// Canvas-based terrain profile visualization with Fresnel zone
struct TerrainProfileCanvas: View {
    let elevationProfile: [ElevationSample]

    /// Profile samples for primary segment (A→B or A→R when repeater active)
    let profileSamples: [ProfileSample]

    /// Profile samples for R→B segment (empty when no repeater)
    var profileSamplesRB: [ProfileSample] = []

    // Optional repeater parameters
    var repeaterPathFraction: Double?
    var repeaterHeight: Double?

    /// Callback to update repeater position during drag
    var onRepeaterDrag: ((Double) -> Void)?

    /// Callback to report repeater marker center position (for tooltip positioning)
    var onRepeaterMarkerPosition: ((CGPoint) -> Void)?

    /// Segment distances for off-path repeater visualization (nil when on-path or no repeater)
    var segmentARDistanceMeters: Double?
    var segmentRBDistanceMeters: Double?

    /// Whether the repeater is off the direct A→B path
    private var isOffPath: Bool {
        segmentARDistanceMeters != nil && segmentRBDistanceMeters != nil
    }

    // Track drag state
    @State private var isDragging = false
    @State private var canvasSize: CGSize = .zero

    // Nudge animation state (one-time affordance when repeater first added)
    @State private var hasAnimatedNudge = false
    @State private var nudgeOffset: CGFloat = 0

    // Repeater marker center (for tooltip positioning)
    @State private var repeaterMarkerCenter: CGPoint?

    // MARK: - Colors (Colorblind-Safe)

    private let terrainFill = Color(red: 0.76, green: 0.70, blue: 0.60)
    private let terrainStroke = Color(red: 0.55, green: 0.47, blue: 0.36)
    private let fresnelOuter = Color.teal.opacity(0.25)
    private let fresnelInner = Color.teal.opacity(0.50)
    private let fresnelObstructed = Color.red.opacity(0.7)
    private let fresnelBoundary = Color.teal.opacity(0.6)
    private let losLineColor = Color.primary
    private let gridColor = Color.gray.opacity(0.3)
    private let repeaterColor = Color.purple

    // MARK: - Layout Constants

    private let padding = EdgeInsets(top: 24, leading: 45, bottom: 28, trailing: 16)
    private let chartHeight: CGFloat = 200

    // MARK: - Computed Properties

    private var xRange: ClosedRange<Double> {
        guard let last = elevationProfile.last else { return 0...1 }
        return 0...max(1, last.distanceFromAMeters)
    }

    private var yRange: ClosedRange<Double> {
        let allSamples = profileSamples + profileSamplesRB
        guard !allSamples.isEmpty else { return 0...100 }

        var minY = Double.infinity
        var maxY = -Double.infinity

        for sample in allSamples {
            minY = min(minY, sample.yTerrain)
            maxY = max(maxY, sample.yTop)
        }

        guard minY.isFinite, maxY.isFinite, maxY > minY else { return 0...100 }

        let range = maxY - minY
        return (minY - range * 0.1)...(maxY + range * 0.2)
    }

    /// Calculated repeater marker center position in canvas coordinates
    private var calculatedMarkerCenter: CGPoint? {
        guard canvasSize != .zero,
              let junctionSample = profileSamples.last,
              repeaterPathFraction != nil else { return nil }

        let coords = ChartCoordinateSpace(
            canvasSize: canvasSize,
            padding: padding,
            xRange: xRange,
            yRange: yRange
        )

        let basePoint = coords.point(x: junctionSample.x, y: junctionSample.yLOS)
        return CGPoint(x: basePoint.x + nudgeOffset, y: basePoint.y)
    }

    // MARK: - Body

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if elevationProfile.isEmpty {
                emptyState
            } else {
                chartCanvas
                legendView
                if isOffPath {
                    indirectRouteLabel
                }
                attributionText
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView(
            L10n.Tools.Tools.LineOfSight.noData,
            systemImage: "chart.xyaxis.line",
            description: Text(L10n.Tools.Tools.LineOfSight.selectTwoPoints)
        )
        .frame(height: chartHeight)
    }

    private var chartCanvas: some View {
        Canvas { context, size in
            let coords = ChartCoordinateSpace(
                canvasSize: size,
                padding: padding,
                xRange: xRange,
                yRange: yRange
            )

            drawGrid(context: context, coords: coords)
            drawFresnelZone(context: context, coords: coords)
            drawFresnelBoundary(context: context, coords: coords)
            drawTerrain(context: context, coords: coords)
            drawObstructions(context: context, coords: coords)
            drawLOSLine(context: context, coords: coords)
            drawEndpointMarkers(context: context, coords: coords)

            // Draw vertical separator at R junction for off-path repeaters (behind R marker)
            if let arDistance = segmentARDistanceMeters, isOffPath {
                drawJunctionSeparator(context: context, coords: coords, atDistance: arDistance)
            }

            // Draw repeater marker if present
            if let pathFraction = repeaterPathFraction,
               let height = repeaterHeight,
               elevationProfile.count >= 2 {
                // Interpolate ground elevation at repeater position
                let index = pathFraction * Double(elevationProfile.count - 1)
                let lowerIndex = Int(index)
                let upperIndex = min(lowerIndex + 1, elevationProfile.count - 1)
                let t = index - Double(lowerIndex)
                let groundElevation = elevationProfile[lowerIndex].elevation +
                    t * (elevationProfile[upperIndex].elevation - elevationProfile[lowerIndex].elevation)

                drawRepeaterMarker(
                    context: context,
                    coords: coords,
                    pathFraction: pathFraction,
                    repeaterElevation: groundElevation,
                    height: height,
                    nudgeOffset: nudgeOffset
                )
            }
        }
        .frame(height: chartHeight)
        .onGeometryChange(for: CGSize.self) { proxy in
            proxy.size
        } action: { size in
            canvasSize = size
        }
        .gesture(repeaterDragGesture, including: onRepeaterDrag != nil ? .all : .subviews)
        .onChange(of: repeaterPathFraction, initial: true) { oldValue, newValue in
            // Trigger one-time nudge animation when repeater is first added (on-path only)
            if oldValue == nil, newValue != nil, !hasAnimatedNudge, !isOffPath {
                hasAnimatedNudge = true
                triggerNudgeAnimation()
            }
        }
        .onChange(of: calculatedMarkerCenter) { _, newCenter in
            if let center = newCenter {
                onRepeaterMarkerPosition?(center)
            }
        }
    }

    private var repeaterDragGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard repeaterPathFraction != nil else { return }
                handleDrag(at: value.location)
            }
            .onEnded { _ in
                isDragging = false
            }
    }

    private func handleDrag(at location: CGPoint) {
        // Convert X position to path fraction
        let chartWidth = canvasSize.width - padding.leading - padding.trailing
        let relativeX = (location.x - padding.leading) / chartWidth
        let pathFraction = max(0.05, min(0.95, relativeX))

        if !isDragging {
            isDragging = true
        }

        onRepeaterDrag?(pathFraction)
    }

    /// Performs a one-time horizontal nudge animation to hint at draggability
    private func triggerNudgeAnimation() {
        // Animate: shift right 4pt, then back to original position
        withAnimation(.easeOut(duration: 0.15)) {
            nudgeOffset = 4
        }
        withAnimation(.easeInOut(duration: 0.2).delay(0.15)) {
            nudgeOffset = 0
        }
    }

    private var legendView: some View {
        HStack(spacing: 16) {
            legendItem(color: terrainStroke, label: L10n.Tools.Tools.LineOfSight.Legend.terrain)
            legendItem(color: losLineColor, label: L10n.Tools.Tools.LineOfSight.Legend.los)
            legendItem(color: fresnelOuter, label: L10n.Tools.Tools.LineOfSight.Legend.clear)
            legendItem(color: fresnelObstructed, label: L10n.Tools.Tools.LineOfSight.Legend.obstructed)
            if repeaterPathFraction != nil {
                legendItem(color: repeaterColor, label: L10n.Tools.Tools.LineOfSight.repeater)
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }

    private func legendItem(color: Color, label: String) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text(label)
        }
    }

    private var indirectRouteLabel: some View {
        Text(L10n.Tools.Tools.LineOfSight.indirectRoute)
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    private var attributionText: some View {
        Text(L10n.Tools.Tools.LineOfSight.elevationAttribution)
            .font(.caption2)
            .foregroundStyle(.tertiary)
    }
}

// MARK: - Axis Helpers

extension TerrainProfileCanvas {

    /// Calculate a "nice" step value for axis ticks
    /// Returns a step that produces clean numbers like 10, 25, 50, 100, etc.
    private func niceStep(for range: Double, targetDivisions: Int) -> Double {
        guard range > 0, targetDivisions > 0 else { return 1 }

        let roughStep = range / Double(targetDivisions)
        let magnitude = pow(10, floor(log10(roughStep)))
        let normalized = roughStep / magnitude

        // Snap to nice values: 1, 2, 2.5, 5, 10
        let niceNormalized: Double
        if normalized <= 1 {
            niceNormalized = 1
        } else if normalized <= 2 {
            niceNormalized = 2
        } else if normalized <= 2.5 {
            niceNormalized = 2.5
        } else if normalized <= 5 {
            niceNormalized = 5
        } else {
            niceNormalized = 10
        }

        return niceNormalized * magnitude
    }

    /// Generate tick values that start at a nice boundary
    private func tickValues(for range: ClosedRange<Double>, step: Double) -> [Double] {
        guard step > 0 else { return [] }

        let start = ceil(range.lowerBound / step) * step
        var ticks: [Double] = []
        var current = start

        while current <= range.upperBound {
            ticks.append(current)
            current += step
        }

        return ticks
    }
}

// MARK: - Draw Functions

extension TerrainProfileCanvas {

    private func drawGrid(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Calculate nice step values
        let yStep = niceStep(for: yRange.upperBound - yRange.lowerBound, targetDivisions: 4)
        let xStep = niceStep(for: xRange.upperBound - xRange.lowerBound, targetDivisions: 5)

        let yTicks = tickValues(for: yRange, step: yStep)
        let xTicks = tickValues(for: xRange, step: xStep)

        // Draw horizontal grid lines
        let gridPath = Path { path in
            for y in yTicks {
                let startPoint = coords.point(x: xRange.lowerBound, y: y)
                let endPoint = coords.point(x: xRange.upperBound, y: y)
                path.move(to: startPoint)
                path.addLine(to: endPoint)
            }
        }

        context.stroke(
            gridPath,
            with: .color(gridColor),
            style: StrokeStyle(lineWidth: 0.5, dash: [4, 4])
        )

        // Y-axis labels (elevation in meters)
        for (index, y) in yTicks.enumerated() {
            let labelPoint = coords.point(x: xRange.lowerBound, y: y)
            let isLast = index == yTicks.count - 1
            let labelText = isLast ? "\(Int(y)) m" : "\(Int(y))"
            let label = Text(labelText)
                .font(.system(size: 9))
                .foregroundStyle(.secondary)
            context.draw(label, at: CGPoint(x: labelPoint.x - 8, y: labelPoint.y), anchor: .trailing)
        }

        // X-axis labels (distance in km)
        for (index, x) in xTicks.enumerated() {
            let labelPoint = coords.point(x: x, y: yRange.lowerBound)
            let kmValue = x / 1000
            let isLast = index == xTicks.count - 1

            // Format based on step size - use integers for whole km values
            let labelText: String
            if xStep >= 1000 && kmValue.truncatingRemainder(dividingBy: 1) == 0 {
                labelText = isLast ? "\(Int(kmValue)) km" : "\(Int(kmValue))"
            } else {
                labelText = isLast
                    ? "\(kmValue.formatted(.number.precision(.fractionLength(1)))) km"
                    : kmValue.formatted(.number.precision(.fractionLength(1)))
            }

            let label = Text(labelText)
                .font(.system(size: 9))
                .foregroundStyle(.secondary)
            context.draw(label, at: CGPoint(x: labelPoint.x, y: labelPoint.y + 10), anchor: .top)
        }
    }

    private func drawFresnelZone(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Draw primary segment (A→B or A→R)
        drawFresnelForSamples(context: context, coords: coords, samples: profileSamples)

        // Draw secondary segment (R→B) if present
        if !profileSamplesRB.isEmpty {
            drawFresnelForSamples(context: context, coords: coords, samples: profileSamplesRB)
        }
    }

    private func drawFresnelForSamples(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample]
    ) {
        guard samples.count >= 2 else { return }

        drawOuterFresnelFill(context: context, coords: coords, samples: samples)
        drawInnerFresnelFill(context: context, coords: coords, samples: samples)
    }

    private func drawObstructions(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Draw obstructions for primary segment
        drawObstructionOverlay(context: context, coords: coords, samples: profileSamples)

        // Draw obstructions for secondary segment if present
        if !profileSamplesRB.isEmpty {
            drawObstructionOverlay(context: context, coords: coords, samples: profileSamplesRB)
        }
    }

    private func drawOuterFresnelFill(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample]
    ) {
        var path = Path()

        // Top edge: left to right
        if let first = samples.first {
            path.move(to: coords.point(x: first.x, y: first.yTop))
        }
        for sample in samples.dropFirst() {
            path.addLine(to: coords.point(x: sample.x, y: sample.yTop))
        }

        // Bottom edge: right to left (clamped to terrain)
        for sample in samples.reversed() {
            path.addLine(to: coords.point(x: sample.x, y: sample.yVisibleBottom))
        }

        path.closeSubpath()
        context.fill(path, with: .color(fresnelOuter))
    }

    private func drawInnerFresnelFill(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample]
    ) {
        var path = Path()

        // Top edge: left to right
        if let first = samples.first {
            path.move(to: coords.point(x: first.x, y: first.yTop60))
        }
        for sample in samples.dropFirst() {
            path.addLine(to: coords.point(x: sample.x, y: sample.yTop60))
        }

        // Bottom edge: right to left (clamped to terrain)
        for sample in samples.reversed() {
            path.addLine(to: coords.point(x: sample.x, y: sample.yVisibleBottom60))
        }

        path.closeSubpath()
        context.fill(path, with: .color(fresnelInner))
    }

    private func drawObstructionOverlay(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample]
    ) {
        // Find contiguous obstructed regions and draw overlay
        var inObstructedRegion = false
        var regionStart = 0

        for (index, sample) in samples.enumerated() {
            if sample.isObstructed && !inObstructedRegion {
                // Start of obstructed region
                inObstructedRegion = true
                regionStart = index
            } else if !sample.isObstructed && inObstructedRegion {
                // End of obstructed region
                drawObstructedRegion(
                    context: context,
                    coords: coords,
                    samples: samples,
                    startIndex: regionStart,
                    endIndex: index - 1
                )
                inObstructedRegion = false
            }
        }

        // Handle region that extends to end
        if inObstructedRegion {
            drawObstructedRegion(
                context: context,
                coords: coords,
                samples: samples,
                startIndex: regionStart,
                endIndex: samples.count - 1
            )
        }
    }

    private func drawObstructedRegion(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample],
        startIndex: Int,
        endIndex: Int
    ) {
        guard startIndex <= endIndex else { return }

        let regionSamples = Array(samples[startIndex...endIndex])
        guard let first = regionSamples.first, let last = regionSamples.last else { return }

        // Ensure a minimum pixel width so single-sample obstructions are visible
        let minWidth: CGFloat = 4
        var leftX = coords.xPixel(first.x)
        var rightX = coords.xPixel(last.x)
        if rightX - leftX < minWidth {
            let center = (leftX + rightX) / 2
            leftX = center - minWidth / 2
            rightX = center + minWidth / 2
        }

        let topY = coords.yPixel(yRange.upperBound)
        let bottomY = coords.yPixel(yRange.lowerBound)

        var path = Path()
        path.move(to: CGPoint(x: leftX, y: topY))
        path.addLine(to: CGPoint(x: rightX, y: topY))
        path.addLine(to: CGPoint(x: rightX, y: bottomY))
        path.addLine(to: CGPoint(x: leftX, y: bottomY))
        path.closeSubpath()

        context.fill(path, with: .color(fresnelObstructed))
    }

    private func drawFresnelBoundary(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Draw boundary for primary segment
        drawFresnelBoundaryForSamples(context: context, coords: coords, samples: profileSamples)

        // Draw boundary for secondary segment if present
        if !profileSamplesRB.isEmpty {
            drawFresnelBoundaryForSamples(context: context, coords: coords, samples: profileSamplesRB)
        }
    }

    private func drawFresnelBoundaryForSamples(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        samples: [ProfileSample]
    ) {
        guard samples.count >= 2 else { return }

        // Top boundary (theoretical ellipse top)
        var topPath = Path()
        if let first = samples.first {
            topPath.move(to: coords.point(x: first.x, y: first.yTop))
        }
        for sample in samples.dropFirst() {
            topPath.addLine(to: coords.point(x: sample.x, y: sample.yTop))
        }

        // Bottom boundary (theoretical ellipse bottom)
        var bottomPath = Path()
        if let first = samples.first {
            bottomPath.move(to: coords.point(x: first.x, y: first.yBottom))
        }
        for sample in samples.dropFirst() {
            bottomPath.addLine(to: coords.point(x: sample.x, y: sample.yBottom))
        }

        let style = StrokeStyle(lineWidth: 1, dash: [4, 3])
        context.stroke(topPath, with: .color(fresnelBoundary), style: style)
        context.stroke(bottomPath, with: .color(fresnelBoundary), style: style)
    }

    private func drawTerrain(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Combine samples for full terrain (avoid duplicate at junction)
        let allSamples: [ProfileSample]
        if profileSamplesRB.isEmpty {
            allSamples = profileSamples
        } else {
            allSamples = profileSamples + profileSamplesRB.dropFirst()
        }

        guard allSamples.count >= 2 else { return }

        // Build terrain fill path
        var fillPath = Path()

        // Start at bottom-left
        let bottomLeft = coords.point(x: xRange.lowerBound, y: yRange.lowerBound)
        fillPath.move(to: bottomLeft)

        // Trace terrain line
        for sample in allSamples {
            fillPath.addLine(to: coords.point(x: sample.x, y: sample.yTerrain))
        }

        // Close at bottom-right and back
        let bottomRight = coords.point(x: xRange.upperBound, y: yRange.lowerBound)
        fillPath.addLine(to: bottomRight)
        fillPath.closeSubpath()

        // Fill terrain
        context.fill(fillPath, with: .color(terrainFill))

        // Build terrain stroke path (just the top edge)
        var strokePath = Path()
        if let first = allSamples.first {
            strokePath.move(to: coords.point(x: first.x, y: first.yTerrain))
        }
        for sample in allSamples.dropFirst() {
            strokePath.addLine(to: coords.point(x: sample.x, y: sample.yTerrain))
        }

        // Stroke terrain outline
        context.stroke(
            strokePath,
            with: .color(terrainStroke),
            style: StrokeStyle(lineWidth: 1.5)
        )
    }

    private func drawLOSLine(context: GraphicsContext, coords: ChartCoordinateSpace) {
        // Draw primary segment LOS line (A→B or A→R)
        if let first = profileSamples.first, let last = profileSamples.last {
            var path = Path()
            path.move(to: coords.point(x: first.x, y: first.yLOS))
            path.addLine(to: coords.point(x: last.x, y: last.yLOS))
            context.stroke(path, with: .color(losLineColor), style: StrokeStyle(lineWidth: 2))
        }

        // Draw secondary segment LOS line (R→B) if present
        if let first = profileSamplesRB.first, let last = profileSamplesRB.last {
            var path = Path()
            path.move(to: coords.point(x: first.x, y: first.yLOS))
            path.addLine(to: coords.point(x: last.x, y: last.yLOS))
            context.stroke(path, with: .color(losLineColor), style: StrokeStyle(lineWidth: 2))
        }
    }

    private func drawEndpointMarkers(context: GraphicsContext, coords: ChartCoordinateSpace) {
        guard let sampleA = profileSamples.first else { return }

        // B is from R→B segment if present, otherwise from primary segment
        let sampleB = profileSamplesRB.last ?? profileSamples.last
        guard let sampleB else { return }

        let markerRadius: CGFloat = 6

        // Point A marker (blue)
        let pointA = coords.point(x: sampleA.x, y: sampleA.yLOS)
        let circleA = Path(ellipseIn: CGRect(
            x: pointA.x - markerRadius,
            y: pointA.y - markerRadius,
            width: markerRadius * 2,
            height: markerRadius * 2
        ))
        context.fill(circleA, with: .color(.blue))

        // Point B marker (green)
        let pointB = coords.point(x: sampleB.x, y: sampleB.yLOS)
        let circleB = Path(ellipseIn: CGRect(
            x: pointB.x - markerRadius,
            y: pointB.y - markerRadius,
            width: markerRadius * 2,
            height: markerRadius * 2
        ))
        context.fill(circleB, with: .color(.green))

        // Labels inside markers (matching repeater style)
        let labelA = Text("A")
            .font(.system(size: 8, weight: .bold))
            .foregroundStyle(.white)
        context.draw(labelA, at: pointA, anchor: .center)

        let labelB = Text("B")
            .font(.system(size: 8, weight: .bold))
            .foregroundStyle(.white)
        context.draw(labelB, at: pointB, anchor: .center)
    }

    private func drawRepeaterMarker(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        pathFraction: Double,
        repeaterElevation: Double,
        height: Double,
        nudgeOffset: CGFloat
    ) {
        // Get the LOS intersection point from profileSamples (junction of A→R and R→B)
        // The repeater marker should be at the LOS line intersection, not ground level
        guard let junctionSample = profileSamples.last else { return }

        let x = junctionSample.x
        let losY = junctionSample.yLOS
        let groundY = junctionSample.yTerrain

        // Draw vertical line from ground to LOS intersection (apply nudge offset)
        let groundPoint = coords.point(x: x, y: groundY)
        let losPoint = coords.point(x: x, y: losY)
        let nudgedGroundPoint = CGPoint(x: groundPoint.x + nudgeOffset, y: groundPoint.y)
        let nudgedLosPoint = CGPoint(x: losPoint.x + nudgeOffset, y: losPoint.y)

        var linePath = Path()
        linePath.move(to: nudgedGroundPoint)
        linePath.addLine(to: nudgedLosPoint)
        context.stroke(linePath, with: .color(repeaterColor), lineWidth: 2)

        // Draw repeater marker circle at LOS intersection (16pt radius = 32pt diameter)
        let markerRadius: CGFloat = 16
        let markerRect = CGRect(
            x: nudgedLosPoint.x - markerRadius,
            y: nudgedLosPoint.y - markerRadius,
            width: markerRadius * 2,
            height: markerRadius * 2
        )
        let markerPath = Circle().path(in: markerRect)

        // Draw shadow first
        context.drawLayer { shadowContext in
            shadowContext.addFilter(.shadow(color: .black.opacity(0.25), radius: 4, x: 0, y: 2))
            shadowContext.fill(markerPath, with: .color(repeaterColor))
        }

        // Draw the actual marker on top
        context.fill(markerPath, with: .color(repeaterColor))

        // Draw "R" label (larger font to match increased marker size)
        let text = Text("R")
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(.white)
        context.draw(text, at: nudgedLosPoint, anchor: .center)
    }

    private func drawJunctionSeparator(
        context: GraphicsContext,
        coords: ChartCoordinateSpace,
        atDistance distance: Double
    ) {
        // Draw vertical dashed line at the junction point
        let topPoint = coords.point(x: distance, y: yRange.upperBound)
        let bottomPoint = coords.point(x: distance, y: yRange.lowerBound)

        var path = Path()
        path.move(to: topPoint)
        path.addLine(to: bottomPoint)

        let style = StrokeStyle(lineWidth: 1.5, dash: [6, 4])
        context.stroke(path, with: .color(.primary.opacity(0.35)), style: style)
    }
}

#Preview("Canvas Profile") {
    let sampleProfile: [ElevationSample] = (0...20).map { i in
        let distance = Double(i) * 500
        let baseElevation = 100.0
        let hillFactor = sin(Double(i) / 20.0 * .pi) * 150

        return ElevationSample(
            coordinate: .init(latitude: 37.7749 + Double(i) * 0.001, longitude: -122.4194),
            elevation: baseElevation + hillFactor,
            distanceFromAMeters: distance
        )
    }

    let samples = FresnelZoneRenderer.buildProfileSamples(
        from: sampleProfile,
        pointAHeight: 10,
        pointBHeight: 15,
        frequencyMHz: 906,
        refractionK: 4.0 / 3.0
    )

    return TerrainProfileCanvas(
        elevationProfile: sampleProfile,
        profileSamples: samples
    )
    .padding()
}
