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
    func render(_ request: MapSnapshotRequest) async -> UIImage? {
        let sprite = PinSpriteRenderer.droppedPinSprite()
        let size = CGSize(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)
        let latitude = request.latitude
        let longitude = request.longitude
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let styleURL = MapStyleSelection.standard.styleURL(
            isDarkMode: request.isDark,
            isOffline: request.isOffline
        )

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
        // The `snapshotter.start(...)` call below retains `snapshotter` for the
        // duration of the underlying GL work — no extra anchor is needed to
        // keep it alive across the `await` suspension.
        let snapshotterRef = SnapshotterRef(snapshotter)

        // `@Sendable` breaks `@MainActor` inheritance from the enclosing
        // `withTaskCancellationHandler` operation closure; without it the
        // runtime executor-isolation assertion (`dispatch_assert_queue_fail`)
        // trips when MapLibre invokes the overlay handler off-main.
        let overlayHandler: @Sendable (MLNMapSnapshotOverlay) -> Void = { overlay in
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
        }

        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<UIImage?, Never>) in
                snapshotter.start(
                    overlayHandler: overlayHandler,
                    completionHandler: { snapshot, _ in
                        continuation.resume(returning: snapshot?.image)
                    }
                )
            }
        } onCancel: { [snapshotterRef] in
            // The cancel handler runs on whichever actor triggered cancellation;
            // hop to the main actor so `MLNMapSnapshotter` (non-`Sendable`) is
            // touched only from its owning context. Cancellation flows back to
            // the awaiter via the `completionHandler` resuming with `nil`.
            Task { @MainActor in snapshotterRef.snapshotter.cancel() }
        }
    }
}

/// `@unchecked Sendable` shuttle so the cancellation closure (which is
/// `@Sendable`) can carry the non-`Sendable` `MLNMapSnapshotter` reference
/// across actors. The closure only reads the property and immediately hops
/// back to the main actor before touching it.
private final class SnapshotterRef: @unchecked Sendable {
    let snapshotter: MLNMapSnapshotter
    init(_ snapshotter: MLNMapSnapshotter) { self.snapshotter = snapshotter }
}
