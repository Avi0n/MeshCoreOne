import CoreLocation
import MapLibre
import UIKit

/// Renders static `MLNMapSnapshotter` thumbnails, compositing pin sprites (and,
/// for paths, a polyline) over the base map. `@MainActor`: `MLNMapSnapshotter` is non-`Sendable`,
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

    // `@Sendable` breaks `@MainActor` inheritance from the enclosing
    // `withTaskCancellationHandler` operation closure; without it the
    // runtime executor-isolation assertion (`dispatch_assert_queue_fail`)
    // trips when MapLibre invokes the overlay handler off-main.
    let overlayHandler: @Sendable (MLNMapSnapshotOverlay) -> Void = { overlay in
      let point = overlay.point(
        for: CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
      )
      UIGraphicsPushContext(overlay.context)
      Self.draw(sprite: sprite, at: point)
      UIGraphicsPopContext()
    }

    return await start(MLNMapSnapshotter(options: options), overlayHandler: overlayHandler)
  }

  /// Renders a static thumbnail of a plotted location path: the polyline plus its
  /// pins, framed to the path's bounding region. A single-point path falls back
  /// to the standard centered single-pin render.
  func render(points: [MapPoint], line: MapLine?, isDark: Bool, isOffline: Bool) async -> UIImage? {
    guard let first = points.first else { return nil }
    let coordinates = points.map(\.coordinate)
    guard coordinates.count > 1, let region = coordinates.boundingRegion() else {
      return await render(MapSnapshotRequest(
        latitude: first.coordinate.latitude,
        longitude: first.coordinate.longitude,
        isDark: isDark,
        isOffline: isOffline
      ))
    }

    let pins = points.map { point in
      (coordinate: point.coordinate, sprite: PinSpriteRenderer.snapshotSprite(named: Self.spriteName(for: point.pinStyle)))
    }
    let lineCoordinates = line.map(\.coordinates)
    let casingColor = UIColor.white.withAlphaComponent(Self.pathCasingOpacity)

    let camera = MLNMapCamera()
    let options = MLNMapSnapshotOptions(
      styleURL: MapStyleSelection.standard.styleURL(isDarkMode: isDark, isOffline: isOffline),
      camera: camera,
      size: CGSize(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)
    )
    // A non-empty `coordinateBounds` overrides the camera's center and altitude,
    // framing the whole path instead of a fixed zoom.
    options.coordinateBounds = region.toMLNCoordinateBounds()
    options.showsLogo = false
    options.showsAttribution = false

    let overlayHandler: @Sendable (MLNMapSnapshotOverlay) -> Void = { overlay in
      UIGraphicsPushContext(overlay.context)
      if let lineCoordinates, lineCoordinates.count > 1 {
        // Mirrors the live map's `.messagePath` layers: a white casing stroked
        // under a solid blue line, round joins and caps, no dashes.
        let context = overlay.context
        let path = CGMutablePath()
        path.addLines(between: lineCoordinates.map { overlay.point(for: $0) })
        context.setLineJoin(.round)
        context.setLineCap(.round)
        context.addPath(path)
        context.setStrokeColor(casingColor.cgColor)
        context.setLineWidth(Self.pathCasingWidth)
        context.strokePath()
        context.addPath(path)
        context.setStrokeColor(UIColor.systemBlue.cgColor)
        context.setLineWidth(Self.pathLineWidth)
        context.strokePath()
      }
      for pin in pins {
        Self.draw(sprite: pin.sprite, at: overlay.point(for: pin.coordinate))
      }
      UIGraphicsPopContext()
    }

    return await start(MLNMapSnapshotter(options: options), overlayHandler: overlayHandler)
  }

  // MARK: - Path styling

  /// Mirrors the live map's `.messagePath` line layers.
  private nonisolated static let pathCasingOpacity: CGFloat = 0.8
  private nonisolated static let pathCasingWidth: CGFloat = 6
  private nonisolated static let pathLineWidth: CGFloat = 3

  /// Sprite names for the styles `LocationPathMapBuilder` emits; anything else
  /// falls back to the dropped pin.
  private static func spriteName(for style: MapPoint.PinStyle) -> String {
    switch style {
    case .pointA: "pin-point-a"
    case .pointB: "pin-point-b"
    default: "pin-dropped"
    }
  }

  /// Draws a bottom-anchored pin sprite so its tip sits on the coordinate.
  private nonisolated static func draw(sprite: UIImage, at point: CGPoint) {
    sprite.draw(in: CGRect(
      x: point.x - sprite.size.width / 2,
      y: point.y - sprite.size.height,
      width: sprite.size.width,
      height: sprite.size.height
    ))
  }

  private func start(
    _ snapshotter: MLNMapSnapshotter,
    overlayHandler: @escaping @Sendable (MLNMapSnapshotOverlay) -> Void
  ) async -> UIImage? {
    // `snapshotter.start(...)` retains `snapshotter` for the duration of the
    // underlying GL work — no extra anchor is needed to keep it alive across
    // the `await` suspension.
    let snapshotterRef = SnapshotterRef(snapshotter)
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
  init(_ snapshotter: MLNMapSnapshotter) {
    self.snapshotter = snapshotter
  }
}
