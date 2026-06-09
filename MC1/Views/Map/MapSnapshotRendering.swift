import UIKit

@MainActor
protocol MapSnapshotRendering {
    /// Renders a static map thumbnail with the dropped pin composited at the
    /// coordinate, or nil on failure. Heavy work runs off-main internally.
    func render(_ request: MapSnapshotRequest) async -> UIImage?
}
