import MapKit
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "MapPins")

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

    // Output callbacks
    let onPointTap: ((MapPoint, CGPoint) -> Void)?
    let onMapTap: ((CLLocationCoordinate2D) -> Void)?
    let onCameraRegionChange: ((MKCoordinateRegion) -> Void)?

    var isStyleLoaded: Binding<Bool> = .constant(true)

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = context.coordinator.mapView
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = showsUserLocation
        mapView.showsScale = showsScale
        mapView.showsCompass = true

        if !isInteractive {
            mapView.isScrollEnabled = false
            mapView.isZoomEnabled = false
            mapView.isRotateEnabled = false
            mapView.isPitchEnabled = false
            mapView.showsCompass = false
        }

        mapView.register(MC1AnnotationView.self, forAnnotationViewWithReuseIdentifier: MC1AnnotationView.reuseID)
        mapView.register(MC1ClusterAnnotationView.self, forAnnotationViewWithReuseIdentifier: MC1ClusterAnnotationView.reuseID)

        // Warm sprite cache on first interactive map creation
        PinSpriteRenderer.warmCache()

        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        tap.delegate = context.coordinator
        mapView.addGestureRecognizer(tap)

        // Fire isStyleLoaded immediately — MapKit has no async style load
        isStyleLoaded.wrappedValue = true

        return mapView
    }

    static func dismantleUIView(_ mapView: MKMapView, coordinator: Coordinator) {
        coordinator.pendingRegionTask?.cancel()
        mapView.delegate = nil
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.isUpdatingFromSwiftUI = true
        defer { coordinator.isUpdatingFromSwiftUI = false }

        coordinator.onPointTap = onPointTap
        coordinator.onMapTap = onMapTap
        coordinator.onCameraRegionChange = onCameraRegionChange
        coordinator.currentPoints = points
        coordinator.currentLines = lines

        // Map type
        let newMapType = mapStyle.mapType
        if mapView.mapType != newMapType {
            mapView.mapType = newMapType
        }

        if mapView.showsUserLocation != showsUserLocation {
            mapView.showsUserLocation = showsUserLocation
        }

        if isInteractive {
            mapView.isRotateEnabled = !isNorthLocked
            if isNorthLocked && mapView.camera.heading != 0 {
                let camera = mapView.camera.copy() as! MKMapCamera
                camera.heading = 0
                mapView.setCamera(camera, animated: true)
            }
        }

        // Refresh annotations when showLabels changes (force full redraw)
        if coordinator.currentShowLabels != showLabels {
            coordinator.currentShowLabels = showLabels
            coordinator.lastAppliedPoints = []
        }

        if coordinator.lastAppliedPoints != points {
            coordinator.updateAnnotations(mapView: mapView, showLabels: showLabels)
            coordinator.lastAppliedPoints = points
        }

        if coordinator.lastAppliedLines != lines {
            coordinator.updateOverlays(mapView: mapView)
            coordinator.lastAppliedLines = lines
        }

        updateCameraRegion(in: mapView, coordinator: coordinator)
    }

    private func updateCameraRegion(in mapView: MKMapView, coordinator: Coordinator) {
        guard let region = cameraRegion else { return }
        guard cameraRegionVersion != coordinator.lastAppliedRegionVersion else { return }
        guard CLLocationCoordinate2DIsValid(region.center) else {
            coordinator.lastAppliedRegionVersion = cameraRegionVersion
            return
        }

        let animated = coordinator.lastAppliedRegionVersion > 0
        coordinator.lastAppliedRegionVersion = cameraRegionVersion

        var padding = cameraEdgePadding
        if let sheetFraction = cameraBottomSheetFraction, sheetFraction > 0 {
            let stableHeight = mapView.window?.bounds.height ?? mapView.bounds.height
            padding.bottom = max(padding.bottom, stableHeight * sheetFraction)
        }

        if padding == .zero {
            mapView.setRegion(region, animated: animated)
        } else {
            let tlPoint = MKMapPoint(CLLocationCoordinate2D(
                latitude: region.center.latitude + region.span.latitudeDelta / 2,
                longitude: region.center.longitude - region.span.longitudeDelta / 2
            ))
            let brPoint = MKMapPoint(CLLocationCoordinate2D(
                latitude: region.center.latitude - region.span.latitudeDelta / 2,
                longitude: region.center.longitude + region.span.longitudeDelta / 2
            ))
            let rect = MKMapRect(
                x: min(tlPoint.x, brPoint.x),
                y: min(tlPoint.y, brPoint.y),
                width: abs(brPoint.x - tlPoint.x),
                height: abs(brPoint.y - tlPoint.y)
            )
            mapView.setVisibleMapRect(rect, edgePadding: padding, animated: animated)
        }
    }
}

// MARK: - Coordinator

extension MC1MapView {
    @MainActor
    class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        let mapView = MKMapView(frame: .zero)

        var onPointTap: ((MapPoint, CGPoint) -> Void)?
        var onMapTap: ((CLLocationCoordinate2D) -> Void)?
        var onCameraRegionChange: ((MKCoordinateRegion) -> Void)?

        var isUpdatingFromSwiftUI = false
        var lastAppliedRegionVersion = 0
        var pendingRegionTask: Task<Void, Never>?
        var currentShowLabels = true
        var currentPoints: [MapPoint] = []
        var currentLines: [MapLine] = []
        var lastAppliedPoints: [MapPoint] = []
        var lastAppliedLines: [MapLine] = []

        // MARK: - MKMapViewDelegate

        func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }

            if let cluster = annotation as? MKClusterAnnotation {
                let view = mapView.dequeueReusableAnnotationView(
                    withIdentifier: MC1ClusterAnnotationView.reuseID,
                    for: cluster
                ) as? MC1ClusterAnnotationView ?? MC1ClusterAnnotationView(annotation: cluster, reuseIdentifier: MC1ClusterAnnotationView.reuseID)
                view.configure(for: cluster)
                return view
            }

            guard let ann = annotation as? MC1MapAnnotation else { return nil }
            let view = mapView.dequeueReusableAnnotationView(
                withIdentifier: MC1AnnotationView.reuseID,
                for: ann
            ) as? MC1AnnotationView ?? MC1AnnotationView(annotation: ann, reuseIdentifier: MC1AnnotationView.reuseID)
            view.configure(for: ann.point, showLabels: currentShowLabels)
            return view
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let casing = overlay as? MC1CasingPolyline {
                return MC1PolylineRenderer.casing(for: casing)
            }
            if let colored = overlay as? MC1ColoredPolyline {
                return MC1PolylineRenderer.colored(for: colored)
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, didSelect annotation: any MKAnnotation) {
            mapView.deselectAnnotation(annotation, animated: false)

            if let cluster = annotation as? MKClusterAnnotation {
                let coords = cluster.memberAnnotations.compactMap { ($0 as? MC1MapAnnotation)?.coordinate }
                if let region = coords.boundingRegion(paddingMultiplier: 2.0) {
                    mapView.setRegion(region, animated: true)
                }
                return
            }

            guard let ann = annotation as? MC1MapAnnotation else { return }
            let pinScreenPos = mapView.convert(ann.coordinate, toPointTo: mapView)
            let calloutAnchor = CGPoint(x: pinScreenPos.x, y: pinScreenPos.y - PinSpriteRenderer.standardHeight)
            onPointTap?(ann.point, calloutAnchor)
        }

        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
            // no-op; fired too frequently during animations
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            guard !isUpdatingFromSwiftUI else { return }
            pendingRegionTask?.cancel()
            pendingRegionTask = Task { [weak self, weak mapView] in
                try? await Task.sleep(for: .milliseconds(50))
                guard !Task.isCancelled, let self, let mapView else { return }
                self.onCameraRegionChange?(mapView.region)
            }
        }

        // MARK: - Gesture recognizer delegate

        nonisolated func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            true
        }

        @objc func handleTap(_ sender: UITapGestureRecognizer) {
            guard sender.state == .ended else { return }
            let point = sender.location(in: mapView)

            // If we hit an annotation view, let MapKit's didSelect handle it
            var hitView: UIView? = mapView.hitTest(point, with: nil)
            while let v = hitView {
                if v is MKAnnotationView { return }
                hitView = v.superview
            }

            let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
            logger.debug("Map tap at \(point.x, privacy: .public), \(point.y, privacy: .public)")
            onMapTap?(coordinate)
        }
    }
}
