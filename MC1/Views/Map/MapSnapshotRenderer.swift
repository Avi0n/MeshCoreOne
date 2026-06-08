import CoreLocation
import MapKit
import UIKit

@MainActor
final class MapSnapshotRenderer: MapSnapshotRendering {
    func render(_ request: MapSnapshotRequest) async -> UIImage? {
        let sprite = PinSpriteRenderer.droppedPinSprite()
        let coordinate = CLLocationCoordinate2D(latitude: request.latitude, longitude: request.longitude)
        let size = CGSize(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)

        let options = MKMapSnapshotter.Options()
        options.region = MKCoordinateRegion(
            center: coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        )
        options.size = size
        options.mapType = .standard
        options.showsBuildings = true

        let snapshotter = MKMapSnapshotter(options: options)
        let snapshotterRef = SnapshotterRef(snapshotter)

        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<UIImage?, Never>) in
                snapshotter.start { snapshot, error in
                    guard let snapshot, error == nil else {
                        continuation.resume(returning: nil)
                        return
                    }

                    let pinPoint = snapshot.point(for: coordinate)
                    let format = UIGraphicsImageRendererFormat.preferred()
                    format.scale = snapshot.image.scale
                    let composite = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                        snapshot.image.draw(at: .zero)
                        let pinRect = CGRect(
                            x: pinPoint.x - sprite.size.width / 2,
                            y: pinPoint.y - sprite.size.height,
                            width: sprite.size.width,
                            height: sprite.size.height
                        )
                        sprite.draw(in: pinRect)
                    }
                    continuation.resume(returning: composite)
                }
            }
        } onCancel: { [snapshotterRef] in
            snapshotterRef.snapshotter.cancel()
        }
    }
}

/// Shuttles the non-`Sendable` `MKMapSnapshotter` into the `@Sendable` cancel
/// closure without crossing actor boundaries unsafely — the closure only cancels.
private final class SnapshotterRef: @unchecked Sendable {
    let snapshotter: MKMapSnapshotter
    init(_ snapshotter: MKMapSnapshotter) { self.snapshotter = snapshotter }
}
