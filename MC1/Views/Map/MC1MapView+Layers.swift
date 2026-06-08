import MapKit
import MC1Services
import UIKit

// MARK: - Annotation model

final class MC1MapAnnotation: NSObject, MKAnnotation {
    let point: MapPoint
    var coordinate: CLLocationCoordinate2D { point.coordinate }
    var title: String? { point.label }

    init(point: MapPoint) {
        self.point = point
        super.init()
    }
}

// MARK: - Pin annotation view

final class MC1AnnotationView: MKAnnotationView {
    static let reuseID = "mc1-pin"

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        canShowCallout = false
        isEnabled = true
    }

    required init?(coder: NSCoder) { fatalError() }

    func configure(for point: MapPoint, showLabels: Bool) {
        let pinImg = PinSpriteRenderer.pinImage(for: point)
        image = pinImg
        centerOffset = CGPoint(x: 0, y: -pinImg.size.height / 2)
        clusteringIdentifier = point.isClusterable ? "mc1-cluster" : nil

        subviews.filter { $0.tag == 100 }.forEach { $0.removeFromSuperview() }

        if showLabels, let label = point.label {
            let labelImg = PinSpriteRenderer.labelImage(for: label)
            let labelView = UIImageView(image: labelImg)
            labelView.tag = 100
            labelView.frame = CGRect(
                x: (pinImg.size.width - labelImg.size.width) / 2,
                y: -labelImg.size.height - 4,
                width: labelImg.size.width,
                height: labelImg.size.height
            )
            addSubview(labelView)
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        subviews.filter { $0.tag == 100 }.forEach { $0.removeFromSuperview() }
        clusteringIdentifier = nil
    }
}

// MARK: - Cluster annotation view

final class MC1ClusterAnnotationView: MKAnnotationView {
    static let reuseID = "mc1-cluster"

    private let circleLayer = CAShapeLayer()
    private let label = UILabel()

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        canShowCallout = false

        circleLayer.fillColor = UIColor.systemBlue.withAlphaComponent(0.85).cgColor
        circleLayer.strokeColor = UIColor.white.withAlphaComponent(0.8).cgColor
        circleLayer.lineWidth = 2
        layer.addSublayer(circleLayer)

        label.textColor = .white
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        label.textAlignment = .center
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError() }

    func configure(for cluster: MKClusterAnnotation) {
        let count = cluster.memberAnnotations.count
        let diameter: CGFloat = count < 50 ? 36 : count < 100 ? 44 : count < 200 ? 52 : 60
        let radius = diameter / 2

        frame = CGRect(x: 0, y: 0, width: diameter, height: diameter)
        centerOffset = .zero

        let path = UIBezierPath(ovalIn: CGRect(x: 1, y: 1, width: diameter - 2, height: diameter - 2))
        circleLayer.path = path.cgPath
        circleLayer.frame = bounds

        label.frame = bounds
        label.text = "\(count)"
        label.font = .systemFont(ofSize: radius > 20 ? 14 : 12, weight: .semibold)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        label.text = nil
    }
}

// MARK: - Typed polylines

final class MC1CasingPolyline: MKPolyline {
    var lineStyle: MapLine.LineStyle = .los
    var opacity: Double = 1.0
}

final class MC1ColoredPolyline: MKPolyline {
    var lineStyle: MapLine.LineStyle = .los
    var opacity: Double = 1.0
}

// MARK: - Polyline renderer

final class MC1PolylineRenderer: MKPolylineRenderer {

    static func casing(for polyline: MC1CasingPolyline) -> MC1PolylineRenderer {
        let r = MC1PolylineRenderer(polyline: polyline)
        r.strokeColor = UIColor.white.withAlphaComponent(0.8)
        switch polyline.lineStyle {
        case .los:
            r.lineWidth = 6
            r.lineDashPattern = [4, 8]
        case .traceUntraced:
            r.lineWidth = 5
            r.lineDashPattern = [4, 8]
        case .traceWeak, .traceMedium:
            r.lineWidth = 6
            r.lineDashPattern = [4, 8]
        case .traceGood:
            r.lineWidth = 7
        case .messagePath:
            r.lineWidth = 6
        }
        return r
    }

    static func colored(for polyline: MC1ColoredPolyline) -> MC1PolylineRenderer {
        let r = MC1PolylineRenderer(polyline: polyline)
        r.alpha = polyline.opacity
        switch polyline.lineStyle {
        case .los:
            r.strokeColor = .systemBlue
            r.lineWidth = 3
            r.lineDashPattern = [4, 8]
        case .traceUntraced:
            r.strokeColor = .systemGray
            r.lineWidth = 2
            r.lineDashPattern = [4, 8]
        case .traceWeak:
            r.strokeColor = SNRQuality.poor.uiColor
            r.lineWidth = 3
            r.lineDashPattern = [4, 8]
        case .traceMedium:
            r.strokeColor = SNRQuality.fair.uiColor
            r.lineWidth = 3
            r.lineDashPattern = [4, 8]
        case .traceGood:
            r.strokeColor = SNRQuality.good.uiColor
            r.lineWidth = 4
        case .messagePath:
            r.strokeColor = .systemBlue
            r.lineWidth = 3
        }
        return r
    }
}

// MARK: - Coordinator extensions

extension MC1MapView.Coordinator {

    func updateAnnotations(mapView: MKMapView, showLabels: Bool) {
        let existing = mapView.annotations.compactMap { $0 as? MC1MapAnnotation }
        let existingByID = Dictionary(uniqueKeysWithValues: existing.map { ($0.point.id, $0) })
        let newByID = Dictionary(uniqueKeysWithValues: currentPoints.map { ($0.id, $0) })

        let toRemove = existing.filter { newByID[$0.point.id] == nil }
        mapView.removeAnnotations(toRemove)

        var toAdd: [MC1MapAnnotation] = []
        for point in currentPoints {
            if let ann = existingByID[point.id] {
                if ann.point != point {
                    mapView.removeAnnotation(ann)
                    toAdd.append(MC1MapAnnotation(point: point))
                } else if let view = mapView.view(for: ann) as? MC1AnnotationView {
                    view.configure(for: point, showLabels: showLabels)
                }
            } else {
                toAdd.append(MC1MapAnnotation(point: point))
            }
        }
        mapView.addAnnotations(toAdd)
    }

    func updateOverlays(mapView: MKMapView) {
        let toRemove = mapView.overlays.filter { $0 is MC1CasingPolyline || $0 is MC1ColoredPolyline }
        mapView.removeOverlays(toRemove)

        var casings: [MC1CasingPolyline] = []
        var coloreds: [MC1ColoredPolyline] = []

        for line in currentLines where line.coordinates.count >= 2 {
            var coords = line.coordinates
            let casing = MC1CasingPolyline(coordinates: &coords, count: coords.count)
            casing.lineStyle = line.style
            casing.opacity = line.opacity
            casings.append(casing)

            let colored = MC1ColoredPolyline(coordinates: &coords, count: coords.count)
            colored.lineStyle = line.style
            colored.opacity = line.opacity
            coloreds.append(colored)
        }

        mapView.addOverlays(casings)
        mapView.addOverlays(coloreds)
    }
}
