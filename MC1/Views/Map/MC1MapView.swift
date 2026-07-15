import MapKit
import MapLibre
import ObjectiveC
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "MapPins")

// MARK: - MapLibre Metal scale fix

/// Workaround for a MapLibre bug where `MLNEffectiveScaleFactorForView`
/// computes `nativeBounds.width / bounds.width` — a ratio that breaks in
/// landscape because `nativeBounds` is fixed while `bounds` rotates.
/// We intercept both `setDrawableSize:` and `setContentScaleFactor:` on
/// MapLibre's internal Metal UIView so the wrong scale is never stored.
/// Upstream issue: https://github.com/maplibre/maplibre-native/issues/3214
private enum MetalLayerScaleFix {
  @MainActor
  static func apply(to mapView: MLNMapView) {
    guard let metalView = findMetalView(in: mapView) else { return }

    let selector = NSSelectorFromString("setDrawableSize:")
    guard metalView.responds(to: selector) else { return }

    guard let originalClass: AnyClass = object_getClass(metalView) else { return }
    let name = "_MC1FixedScale_\(NSStringFromClass(originalClass))"

    let fixedClass: AnyClass
    if let existing = objc_getClass(name) as? AnyClass {
      fixedClass = existing
    } else {
      guard let subclass = objc_allocateClassPair(originalClass, name, 0) else { return }
      addDrawableSizeOverride(to: subclass, originalClass: originalClass)
      addContentScaleFactorOverride(to: subclass, originalClass: originalClass)
      objc_registerClassPair(subclass)
      fixedClass = subclass
    }

    object_setClass(metalView, fixedClass)
  }

  @MainActor
  private static func findMetalView(in view: UIView) -> UIView? {
    for subview in view.subviews where subview.layer is CAMetalLayer {
      return subview
    }
    return nil
  }

  @MainActor
  private static func findMapView(from metalView: UIView) -> MLNMapView? {
    var parent: UIView? = metalView.superview
    while let v = parent, !(v is MLNMapView) {
      parent = v.superview
    }
    return parent as? MLNMapView
  }

  private static func addDrawableSizeOverride(
    to subclass: AnyClass,
    originalClass: AnyClass
  ) {
    let selector = NSSelectorFromString("setDrawableSize:")
    guard let original = class_getInstanceMethod(originalClass, selector) else { return }
    let originalIMP = method_getImplementation(original)
    typealias SetDrawableSizeFn = @convention(c) @Sendable (AnyObject, Selector, CGSize) -> Void
    let callOriginal = unsafeBitCast(originalIMP, to: SetDrawableSizeFn.self)

    let block: @convention(block) (UIView, CGSize) -> Void = { metalView, proposedSize in
      dispatchPrecondition(condition: .onQueue(.main))
      MainActor.assumeIsolated {
        guard let mapView = findMapView(from: metalView),
              mapView.bounds.size.width > 0,
              mapView.bounds.size.height > 0,
              let screen = mapView.window?.screen else {
          callOriginal(metalView, selector, proposedSize)
          return
        }

        let correctScale = screen.nativeScale
        let correctSize = CGSize(
          width: mapView.bounds.width * correctScale,
          height: mapView.bounds.height * correctScale
        )

        if let layer = metalView.layer as? CAMetalLayer,
           layer.drawableSize == correctSize {
          return
        }

        callOriginal(metalView, selector, correctSize)
      }
    }

    let imp = imp_implementationWithBlock(block)
    class_addMethod(subclass, selector, imp, method_getTypeEncoding(original))
  }

  private static func addContentScaleFactorOverride(
    to subclass: AnyClass,
    originalClass: AnyClass
  ) {
    let selector = NSSelectorFromString("setContentScaleFactor:")
    guard let original = class_getInstanceMethod(originalClass, selector) else { return }
    let originalIMP = method_getImplementation(original)
    typealias SetScaleFn = @convention(c) @Sendable (AnyObject, Selector, CGFloat) -> Void
    let callOriginal = unsafeBitCast(originalIMP, to: SetScaleFn.self)

    let block: @convention(block) (UIView, CGFloat) -> Void = { metalView, _ in
      dispatchPrecondition(condition: .onQueue(.main))
      MainActor.assumeIsolated {
        guard let mapView = findMapView(from: metalView),
              let screen = mapView.window?.screen else {
          return
        }

        let correctScale = screen.nativeScale
        if metalView.contentScaleFactor == correctScale {
          return
        }

        callOriginal(metalView, selector, correctScale)
      }
    }

    let imp = imp_implementationWithBlock(block)
    class_addMethod(subclass, selector, imp, method_getTypeEncoding(original))
  }
}

/// Applies the isa-swizzle once the view is attached to a window.
private final class ScaledMLNMapView: MLNMapView {
  override func didMoveToWindow() {
    super.didMoveToWindow()
    guard window != nil else { return }
    MetalLayerScaleFix.apply(to: self)
  }
}

struct MC1MapView: UIViewRepresentable {
  // Data
  let points: [MapPoint]
  let lines: [MapLine]
  let mapStyle: MapStyleSelection
  let isDarkMode: Bool
  var isOffline: Bool = false

  // Configuration
  let showLabels: Bool
  let showsUserLocation: Bool
  let isInteractive: Bool
  let showsScale: Bool
  var isNorthLocked: Bool = false

  // Camera
  @Binding var cameraRegion: MKCoordinateRegion?
  let cameraRegionVersion: Int
  var cameraEdgePadding: UIEdgeInsets = .zero
  var cameraBottomSheetFraction: CGFloat?

  // Programmatic selection: the id of the point to select, plus a version
  // counter bumped to (re)fire it, mirroring the camera region + version idiom.
  // The coordinator projects the point and routes it through `onPointTap`, so a
  // programmatic selection is the same code path as a user tap.
  var selectionRequestID: UUID?
  var selectionRequestVersion: Int = 0

  // Output callbacks
  let onPointTap: ((MapPoint, CGPoint) -> Void)?
  let onMapTap: ((CLLocationCoordinate2D) -> Void)?
  var onMapLongPress: ((CLLocationCoordinate2D) -> Void)?
  let onCameraRegionChange: ((MKCoordinateRegion) -> Void)?

  /// Optional features
  var isStyleLoaded: Binding<Bool> = .constant(true)

  /// Reports whether the camera is currently centered on the user's location.
  var isCenteredOnUser: Binding<Bool> = .constant(false)

  func makeCoordinator() -> Coordinator {
    Coordinator()
  }

  func makeUIView(context: Context) -> MLNMapView {
    let mapView = context.coordinator.mapView
    mapView.delegate = context.coordinator

    mapView.showsUserLocation = showsUserLocation
    mapView.compassViewPosition = .topRight
    mapView.compassViewMargins = CGPoint(x: 8, y: 8)
    mapView.attributionButtonPosition = .bottomLeft
    mapView.attributionButtonMargins = CGPoint(x: 4, y: 30)

    if showsScale {
      mapView.showsScale = true
    }

    if !isInteractive {
      mapView.isScrollEnabled = false
      mapView.isZoomEnabled = false
      mapView.isRotateEnabled = false
      mapView.isPitchEnabled = false
      mapView.compassView.isHidden = true
    }

    // Disable quick-zoom (tap-then-hold-drag) gesture
    mapView.gestureRecognizers?
      .compactMap { $0 as? UILongPressGestureRecognizer }
      .filter { $0.numberOfTapsRequired == 1 && $0.minimumPressDuration == 0 }
      .forEach { $0.isEnabled = false }

    // Tap gesture for feature queries
    let tap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleTap(_:))
    )
    tap.delegate = context.coordinator
    mapView.addGestureRecognizer(tap)

    // Long-press gesture for dropping a pin at the pressed coordinate
    let longPress = UILongPressGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleLongPress(_:))
    )
    longPress.delegate = context.coordinator
    mapView.addGestureRecognizer(longPress)

    return mapView
  }

  static func dismantleUIView(_ mapView: MLNMapView, coordinator: Coordinator) {
    coordinator.pendingRegionTask?.cancel()
    mapView.delegate = nil
  }

  func updateUIView(_ mapView: MLNMapView, context: Context) {
    let coordinator = context.coordinator
    coordinator.isUpdatingFromSwiftUI = true
    defer { coordinator.isUpdatingFromSwiftUI = false }

    // Refresh callbacks
    coordinator.onPointTap = onPointTap
    coordinator.onMapTap = onMapTap
    coordinator.onMapLongPress = onMapLongPress
    coordinator.onCameraRegionChange = onCameraRegionChange
    coordinator.setIsStyleLoaded = { isStyleLoaded.wrappedValue = $0 }
    coordinator.setIsCenteredOnUser = { isCenteredOnUser.wrappedValue = $0 }
    coordinator.currentPoints = points
    coordinator.currentLines = lines
    // Set before the styleURL below: a theme switch changes the styleURL and triggers
    // a reload, so didFinishLoading -> renderAll must already see the new theme.
    coordinator.currentIsDarkMode = isDarkMode

    // Style URL change — compare against our tracked value, not mapView.styleURL
    // which MapLibre may transiently nil during layout/rotation.
    let newStyleURL = mapStyle.styleURL(isDarkMode: isDarkMode, isOffline: isOffline)
    if coordinator.lastAppliedStyleURL != newStyleURL {
      coordinator.lastAppliedStyleURL = newStyleURL
      coordinator.isStyleLoaded = false
      mapView.styleURL = newStyleURL
    }
    coordinator.currentMapStyle = mapStyle

    // User location
    if mapView.showsUserLocation != showsUserLocation {
      mapView.showsUserLocation = showsUserLocation
    }

    // North lock
    if isInteractive {
      mapView.isRotateEnabled = !isNorthLocked
      if isNorthLocked, mapView.direction != 0 {
        mapView.setDirection(0, animated: true)
      }
    }

    // Update data layers (only when style is loaded and not mid-gesture).
    // Compare against lastApplied* so updates arriving during a gesture
    // are applied once the gesture ends.
    if coordinator.isStyleLoaded, !coordinator.isUserInteracting {
      if coordinator.lastAppliedMapStyle != mapStyle {
        coordinator.updateRasterLayerVisibility(mapView: mapView)
        coordinator.lastAppliedMapStyle = mapStyle
      }
      if coordinator.lastAppliedPoints != points {
        coordinator.updatePointSource(mapView: mapView)
        coordinator.lastAppliedPoints = points
      }
      if coordinator.lastAppliedLines != lines {
        coordinator.updateLineSource(mapView: mapView)
        coordinator.lastAppliedLines = lines
      }
      if coordinator.currentShowLabels != showLabels {
        coordinator.currentShowLabels = showLabels
        coordinator.updateLabelVisibility(mapView: mapView, showLabels: showLabels)
      }
    }

    // Camera region (version-number pattern)
    updateCameraRegion(in: mapView, coordinator: coordinator)

    // Programmatic selection (version-number pattern). Runs once per bump, after the
    // camera update so the projection reads the just-applied camera. Dispatched async
    // so it never mutates SwiftUI state during this representable update, matching how
    // `onCameraRegionChange` defers its write-back.
    if coordinator.isStyleLoaded,
       coordinator.lastAppliedSelectionVersion != selectionRequestVersion {
      coordinator.lastAppliedSelectionVersion = selectionRequestVersion
      if let id = selectionRequestID,
         let point = coordinator.currentPoints.first(where: { $0.id == id }) {
        DispatchQueue.main.async { coordinator.selectPoint(point) }
      }
    }
  }

  /// Maximum absolute latitude MapLibre's `mbgl::LatLng` accepts; it throws an
  /// uncaught `std::domain_error` (aborting the app) for any value beyond ±90.
  private static let latitudeLimit = 90.0

  private func updateCameraRegion(in mapView: MLNMapView, coordinator: Coordinator) {
    guard let region = cameraRegion else { return }
    guard cameraRegionVersion != coordinator.lastAppliedRegionVersion else { return }

    guard CLLocationCoordinate2DIsValid(region.center) else {
      coordinator.lastAppliedRegionVersion = cameraRegionVersion
      return
    }

    // Corners are center ± span/2, so a non-finite span makes MapLibre's LatLng
    // constructor throw and abort the process — and the latitude clamp below can't
    // catch it because Swift's max/min propagate NaN. Skip the update when non-finite.
    guard region.span.latitudeDelta.isFinite,
          region.span.longitudeDelta.isFinite else {
      coordinator.lastAppliedRegionVersion = cameraRegionVersion
      return
    }

    let isInflated = mapView.window.map { mapView.bounds.height > $0.bounds.height * 1.5 } ?? false
    let animated = coordinator.lastAppliedRegionVersion > 0 && !isInflated
    coordinator.lastAppliedRegionVersion = cameraRegionVersion

    // Clamp latitude so a near-pole center can't push a corner past ±90 (another
    // LatLng abort). Longitude is left unclamped because MapLibre wraps it.
    let limit = Self.latitudeLimit
    let bounds = MLNCoordinateBounds(
      sw: CLLocationCoordinate2D(
        latitude: max(-limit, region.center.latitude - region.span.latitudeDelta / 2),
        longitude: region.center.longitude - region.span.longitudeDelta / 2
      ),
      ne: CLLocationCoordinate2D(
        latitude: min(limit, region.center.latitude + region.span.latitudeDelta / 2),
        longitude: region.center.longitude + region.span.longitudeDelta / 2
      )
    )
    var padding = cameraEdgePadding
    if let sheetFraction = cameraBottomSheetFraction {
      let insets = mapView.safeAreaInsets
      padding.top = max(padding.top, insets.top + 20)
      padding.left = max(padding.left, insets.left + 20)
      if sheetFraction > 0 {
        let stableHeight = mapView.window?.bounds.height ?? mapView.bounds.height
        padding.bottom = max(padding.bottom, stableHeight * sheetFraction)
      }
    }

    if let windowSize = mapView.window?.bounds.size,
       mapView.bounds.height > windowSize.height * 1.5 {
      let centerLat = (bounds.sw.latitude + bounds.ne.latitude) / 2
      let centerLon = (bounds.sw.longitude + bounds.ne.longitude) / 2
      let latSpanMeters = abs(bounds.ne.latitude - bounds.sw.latitude) * 111_000
      let lonSpanMeters = abs(bounds.ne.longitude - bounds.sw.longitude) * 111_000
        * cos(centerLat * .pi / 180)

      let usableWidth = max(1, Double(windowSize.width) - Double(padding.left + padding.right))
      let usableHeight = max(1, Double(windowSize.height) - Double(padding.top + padding.bottom))

      let mppForLat = latSpanMeters / usableHeight
      let mppForLon = lonSpanMeters / usableWidth
      let requiredMPP = max(mppForLat, mppForLon)

      let currentMPP = mapView.metersPerPoint(atLatitude: centerLat)
      let targetZoom = mapView.zoomLevel + log2(currentMPP / requiredMPP)

      let pixelOffset = (Double(padding.top) - Double(padding.bottom)) / 2
      let offsetDeg = pixelOffset * requiredMPP / 111_000
      let center = CLLocationCoordinate2D(
        latitude: min(limit, max(-limit, centerLat + offsetDeg)),
        longitude: centerLon
      )

      mapView.setCenter(center, zoomLevel: targetZoom, animated: false)
    } else {
      mapView.setVisibleCoordinateBounds(
        bounds,
        edgePadding: padding,
        animated: animated,
        completionHandler: nil
      )
    }
  }
}

// MARK: - Coordinator

extension MC1MapView {
  @MainActor
  class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate, UIGestureRecognizerDelegate {
    /// Non-zero frame avoids MapLibre zero-size Metal init (issue #67).
    let mapView: MLNMapView = ScaledMLNMapView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))

    // Callbacks
    var onPointTap: ((MapPoint, CGPoint) -> Void)?
    var onMapTap: ((CLLocationCoordinate2D) -> Void)?
    var onMapLongPress: ((CLLocationCoordinate2D) -> Void)?
    var onCameraRegionChange: ((MKCoordinateRegion) -> Void)?
    var setIsStyleLoaded: ((Bool) -> Void)?
    var setIsCenteredOnUser: ((Bool) -> Void)?

    // State
    var isUserInteracting = false
    var isUpdatingFromSwiftUI = false
    var isStyleLoaded = false
    var lastAppliedRegionVersion = 0
    var lastAppliedSelectionVersion = 0
    var pendingRegionTask: Task<Void, Never>?
    var currentShowLabels = true
    /// The basemap theme in force, mirrored from the view so `renderAll` can pick the
    /// location-dot recency palette at style-load time. Kept current by `updateUIView`.
    var currentIsDarkMode = false
    var lastAppliedStyleURL: URL?
    var currentMapStyle: MapStyleSelection?
    var lastAppliedMapStyle: MapStyleSelection?
    var currentPoints: [MapPoint] = []
    var currentLines: [MapLine] = []
    var lastAppliedPoints: [MapPoint] = []
    var lastAppliedClusterablePoints: [MapPoint] = []
    var lastAppliedFixedPoints: [MapPoint] = []
    var lastAppliedLines: [MapLine] = []
    var clusterSource: MLNShapeSource?
    var fixedSource: MLNShapeSource?

    // MARK: - Style loading

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
      isStyleLoaded = true
      setIsStyleLoaded?(true)

      // Clear stale source/state references from the previous style.
      // Reset currentShowLabels to the new layer default (visible) so
      // updateUIView detects the mismatch and reapplies the user's preference.
      // A reload rebuilds the raster layers with isVisible == false, so clear
      // lastAppliedMapStyle to force updateUIView to re-apply the selected overlay.
      clusterSource = nil
      fixedSource = nil
      lastAppliedPoints = []
      lastAppliedClusterablePoints = []
      lastAppliedFixedPoints = []
      lastAppliedLines = []
      lastAppliedMapStyle = nil
      currentShowLabels = true

      PinSpriteRenderer.renderAll(into: style, isDarkMode: currentIsDarkMode)
      setupRasterSources(style: style, mapView: mapView)
      setupLineLayers(style: style)

      updatePointSource(mapView: mapView)
      updateLineSource(mapView: mapView)
    }

    func mapView(_ mapView: MLNMapView, didFailToLoadImage imageName: String) -> UIImage? {
      if let style = mapView.style,
         let image = PinSpriteRenderer.renderOnDemand(name: imageName, into: style) {
        return image
      }
      logger.error("didFailToLoadImage: \(imageName)")
      return nil
    }

    // MARK: - Region changes

    private static let userGestureReasons: MLNCameraChangeReason = [
      .gesturePan, .gesturePinch, .gestureZoomIn, .gestureZoomOut,
      .gestureRotate, .gestureTilt, .gestureOneFingerZoom
    ]

    func mapViewRegionIsChanging(_ mapView: MLNMapView) {
      isUserInteracting = true
    }

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
      // A user drag/zoom/rotate moves the camera off the user's location, so clear the
      // centered-on-user flag. Programmatic recenters keep it — the location button sets
      // it back to true when it centers the map.
      guard !reason.isDisjoint(with: Self.userGestureReasons) else { return }
      let report = setIsCenteredOnUser
      DispatchQueue.main.async { report?(false) }
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
      isUserInteracting = false
      guard !isUpdatingFromSwiftUI else { return }

      let isUserGesture = !reason.isDisjoint(with: Self.userGestureReasons)
      guard isUserGesture else { return }

      // Debounce: cancel previous pending write-back
      pendingRegionTask?.cancel()
      pendingRegionTask = Task {
        try? await Task.sleep(for: .milliseconds(50))
        guard !Task.isCancelled else { return }
        let region = mapView.mlnRegion
        self.onCameraRegionChange?(region)
      }
    }

    // MARK: - Gesture recognizer delegate

    nonisolated func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
      true
    }

    // MARK: - Tap handling

    /// Projects a point to its on-screen anchor (lifted above the pin by its style's
    /// callout clearance) and routes it through `onPointTap`, so a programmatic
    /// selection presents the callout exactly like a user tap.
    func selectPoint(_ mapPoint: MapPoint) {
      let pinScreenPos = mapView.convert(mapPoint.coordinate, toPointTo: mapView)
      let calloutAnchor = CGPoint(
        x: pinScreenPos.x,
        y: pinScreenPos.y - PinSpriteRenderer.calloutLift(for: mapPoint.pinStyle)
      )
      onPointTap?(mapPoint, calloutAnchor)
    }

    @objc func handleTap(_ sender: UITapGestureRecognizer) {
      guard sender.state == .ended else { return }
      let point = sender.location(in: mapView)
      let clusterRect = CGRect(x: point.x - 22, y: point.y - 22, width: 44, height: 44)
      logger.debug("handleTap at \(point.x, privacy: .public), \(point.y, privacy: .public)")

      // 1. Check cluster layers
      let clusterFeatures = mapView.visibleFeatures(
        in: clusterRect,
        styleLayerIdentifiers: [MapLayerID.clusterCircles]
      )
      if let cluster = clusterFeatures.first(where: { $0 is MLNPointFeatureCluster }) as? MLNPointFeatureCluster,
         let source = mapView.style?.source(withIdentifier: MapSourceID.points) as? MLNShapeSource {
        let zoom = source.zoomLevel(forExpanding: cluster)
        guard zoom >= 0 else { return }
        mapView.setCenter(cluster.coordinate, zoomLevel: zoom + 2.0, animated: true)
        return
      }

      // 2. Check point and name label layers (both clustered and fixed)
      let pointFeatures = mapView.visibleFeatures(
        at: point,
        styleLayerIdentifiers: [
          MapLayerID.unclusteredIcons, MapLayerID.fixedIcons,
          MapLayerID.nameLabels, MapLayerID.fixedNameLabels
        ]
      )
      logger.debug("pointFeatures: \(pointFeatures.count, privacy: .public), clusterFeatures: \(clusterFeatures.count, privacy: .public)")
      if let feature = pointFeatures.first,
         let idString = feature.attribute(forKey: "pointId") as? String,
         let id = UUID(uuidString: idString),
         let mapPoint = currentPoints.first(where: { $0.id == id }) {
        logger.debug("Matched pin: \(mapPoint.label ?? "unnamed", privacy: .public)")
        selectPoint(mapPoint)
        return
      }

      // 3. Check badge text layers — dismiss any open callout but don't select
      let badgeFeatures = mapView.visibleFeatures(
        at: point,
        styleLayerIdentifiers: [MapLayerID.badgeText, MapLayerID.fixedBadgeText]
      )
      if badgeFeatures.first != nil {
        let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
        onMapTap?(coordinate)
        return
      }

      // 4. Map background tap
      let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
      onMapTap?(coordinate)
    }

    @objc func handleLongPress(_ sender: UILongPressGestureRecognizer) {
      guard sender.state == .began else { return }
      let point = sender.location(in: mapView)
      let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
      onMapLongPress?(coordinate)
    }
  }
}

// MARK: - MLNMapView region helper

extension MLNMapView {
  var mlnRegion: MKCoordinateRegion {
    let bounds = visibleCoordinateBounds
    let center = CLLocationCoordinate2D(
      latitude: (bounds.sw.latitude + bounds.ne.latitude) / 2,
      longitude: (bounds.sw.longitude + bounds.ne.longitude) / 2
    )
    let span = MKCoordinateSpan(
      latitudeDelta: bounds.ne.latitude - bounds.sw.latitude,
      longitudeDelta: bounds.ne.longitude - bounds.sw.longitude
    )
    return MKCoordinateRegion(center: center, span: span)
  }
}
