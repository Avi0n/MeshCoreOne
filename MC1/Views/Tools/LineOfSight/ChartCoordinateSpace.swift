import SwiftUI

/// Transforms data coordinates (meters) to canvas pixel coordinates
struct ChartCoordinateSpace {
    let canvasSize: CGSize
    let padding: EdgeInsets
    let xRange: ClosedRange<Double>  // meters
    let yRange: ClosedRange<Double>  // meters

    private var plotWidth: CGFloat {
        canvasSize.width - padding.leading - padding.trailing
    }

    private var plotHeight: CGFloat {
        canvasSize.height - padding.top - padding.bottom
    }

    /// Convert x data value (meters) to pixel x coordinate
    func xPixel(_ xMeters: Double) -> CGFloat {
        let fraction = (xMeters - xRange.lowerBound) / (xRange.upperBound - xRange.lowerBound)
        return padding.leading + fraction * plotWidth
    }

    /// Convert y data value (meters) to pixel y coordinate (inverted for Canvas)
    func yPixel(_ yMeters: Double) -> CGFloat {
        let fraction = (yMeters - yRange.lowerBound) / (yRange.upperBound - yRange.lowerBound)
        // Invert: Canvas origin is top-left, data origin is bottom-left
        return canvasSize.height - padding.bottom - fraction * plotHeight
    }

    /// Convert data point (meters) to canvas pixel point
    func point(x: Double, y: Double) -> CGPoint {
        CGPoint(x: xPixel(x), y: yPixel(y))
    }

    /// Format x value (meters) as km string for axis labels
    func xLabel(_ xMeters: Double) -> String {
        (xMeters / 1000).formatted(.number.precision(.fractionLength(1)))
    }
}
