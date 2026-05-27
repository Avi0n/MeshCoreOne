import CoreLocation
import MapLibre
import UIKit

/// Renders a static `MLNMapSnapshotter` thumbnail and composites the dropped-pin
/// sprite at the coordinate. `@MainActor`: `MLNMapSnapshotter` is non-`Sendable`,
/// its completion fires on the main queue, and the sprite comes from the
/// `@MainActor` `PinSpriteRenderer`. The base map render and the pin composite
/// run off-main (the overlay handler is on a background queue); the main actor is
/// only used briefly to build options and is suspended during the GL work.
@MainActor
final class MapSnapshotRenderer: MapSnapshotRendering {
    /// Holds the in-flight snapshotter alive across the suspension. With
    /// `MapSnapshotStore`'s serial cap there is at most one at a time.
    private var activeSnapshotter: MLNMapSnapshotter?

    func render(_ request: MapSnapshotRequest) async -> UIImage? {
        let sprite = PinSpriteRenderer.droppedPinSprite()
        let size = CGSize(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)
        let latitude = request.latitude
        let longitude = request.longitude
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let styleURL = MapStyleSelection.standard.styleURL(isDarkMode: request.isDark)

        let camera = MLNMapCamera(
            lookingAtCenter: coordinate,
            altitude: 0,
            pitch: 0,
            heading: 0
        )
        let options = MLNMapSnapshotOptions(styleURL: styleURL, camera: camera, size: size)
        options.zoomLevel = MapSnapshotLayout.zoomLevel
        options.showsLogo = false
        // Attribution is suppressed on the thumbnail; the full Map tab the user
        // taps into shows the OSM/MapLibre attribution control.
        options.showsAttribution = false

        let snapshotter = MLNMapSnapshotter(options: options)
        activeSnapshotter = snapshotter
        defer { activeSnapshotter = nil }

        return await withCheckedContinuation { (continuation: CheckedContinuation<UIImage?, Never>) in
            snapshotter.start(
                overlayHandler: { overlay in
                    // Background queue. Composite the pre-rendered sprite directly
                    // into the snapshot context, tip (bottom-center) at the point.
                    let point = overlay.point(
                        for: CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                    )
                    UIGraphicsPushContext(overlay.context)
                    sprite.draw(in: CGRect(
                        x: point.x - sprite.size.width / 2,
                        y: point.y - sprite.size.height,
                        width: sprite.size.width,
                        height: sprite.size.height
                    ))
                    UIGraphicsPopContext()
                },
                completionHandler: { snapshot, _ in
                    continuation.resume(returning: snapshot?.image)
                }
            )
        }
    }
}
