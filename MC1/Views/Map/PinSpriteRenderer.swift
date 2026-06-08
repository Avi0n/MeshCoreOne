import UIKit

@MainActor
enum PinSpriteRenderer {
    static let standardHeight: CGFloat = 43 // 36 (circle) + 10 (triangle) - 3 (overlap)
    static let labelSpritePrefix = "label-"

    private static var cachedImages: [String: UIImage] = [:]
    private static var cachedDroppedPin: UIImage?

    /// Pre-renders all base sprites into the cache.
    static func warmCache() {
        guard cachedImages.isEmpty else { return }
        for spec in allSpecs {
            cachedImages[spec.name] = render(spec)
        }
    }

    /// Returns the pin image for the given MapPoint.
    static func pinImage(for point: MapPoint) -> UIImage {
        let name = spriteName(for: point)
        if let cached = cachedImages[name] { return cached }

        if name.hasPrefix("pin-repeater-ring-white-hop-"),
           let hopString = name.split(separator: "-").last,
           let hop = Int(hopString),
           (1...20).contains(hop),
           let spec = allSpecs.first(where: { $0.name == "pin-repeater-ring-white" }) {
            let image = render(spec, hopIndex: hop)
            cachedImages[name] = image
            return image
        }

        if let spec = allSpecs.first(where: { $0.name == name }) {
            let image = render(spec)
            cachedImages[name] = image
            return image
        }
        return UIImage()
    }

    /// Returns a label pill image for the given text string.
    static func labelImage(for text: String) -> UIImage {
        let key = "\(labelSpritePrefix)\(text)"
        if let cached = cachedImages[key] { return cached }
        let image = renderLabelSprite(text: text)
        cachedImages[key] = image
        return image
    }

    /// The dropped-pin sprite, rendered once and reused.
    static func droppedPinSprite() -> UIImage {
        if let cached = cachedDroppedPin { return cached }
        guard let spec = allSpecs.first(where: { $0.name == "pin-dropped" }) else {
            return UIImage()
        }
        let image = render(spec)
        cachedDroppedPin = image
        return image
    }

    // MARK: - Sprite name

    static func spriteName(for point: MapPoint) -> String {
        switch point.pinStyle {
        case .contactChat: "pin-chat"
        case .contactRepeater: "pin-repeater"
        case .contactRoom: "pin-room"
        case .repeater: "pin-repeater"
        case .repeaterRingBlue: "pin-repeater-ring-blue"
        case .repeaterRingGreen: "pin-repeater-ring-green"
        case .repeaterRingWhite:
            if let hop = point.hopIndex {
                "pin-repeater-ring-white-hop-\(min(hop, 20))"
            } else {
                "pin-repeater-ring-white"
            }
        case .pointA: "pin-point-a"
        case .pointB: "pin-point-b"
        case .crosshair: "pin-crosshair"
        case .obstruction: "pin-obstruction"
        case .droppedPin: "pin-dropped"
        case .badge: "pin-badge"
        }
    }

    // MARK: - Sprite specifications

    private enum RenderStyle {
        case standard
        case crosshair
        case obstruction
    }

    private struct SpriteSpec {
        let name: String
        let circleColor: UIColor
        let iconName: String?
        let text: String?
        let ringColor: UIColor?
        let renderStyle: RenderStyle
    }

    private static let allSpecs: [SpriteSpec] = [
        SpriteSpec(name: "pin-chat", circleColor: UIColor(red: 204/255, green: 122/255, blue: 92/255, alpha: 1),
                   iconName: "person.fill", text: nil, ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-room", circleColor: UIColor(red: 1, green: 136/255, blue: 0, alpha: 1),
                   iconName: "person.3.fill", text: nil, ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater-ring-blue", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .systemBlue, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater-ring-green", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .systemGreen, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater-ring-white", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .white, renderStyle: .standard),
        SpriteSpec(name: "pin-point-a", circleColor: .systemBlue,
                   iconName: nil, text: "A", ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-point-b", circleColor: .systemGreen,
                   iconName: nil, text: "B", ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-crosshair", circleColor: .systemPurple,
                   iconName: nil, text: "R", ringColor: nil, renderStyle: .crosshair),
        SpriteSpec(name: "pin-obstruction", circleColor: .systemRed,
                   iconName: nil, text: nil, ringColor: nil, renderStyle: .obstruction),
        SpriteSpec(name: "pin-dropped", circleColor: .systemPink,
                   iconName: "mappin", text: nil, ringColor: nil, renderStyle: .standard),
    ]

    // MARK: - Rendering

    private static func render(_ spec: SpriteSpec, hopIndex: Int? = nil) -> UIImage {
        switch spec.renderStyle {
        case .crosshair: return renderCrosshair(spec)
        case .obstruction: return renderObstruction()
        case .standard: break
        }

        let circleSize: CGFloat = 36
        let iconSize: CGFloat = 16
        let triangleSize: CGFloat = 10
        let ringPadding: CGFloat = spec.ringColor != nil ? 4 : 0
        let ringSize: CGFloat = spec.ringColor != nil ? 44 : 0
        let totalWidth = max(circleSize, ringSize)
        let totalHeight = circleSize + triangleSize - 3 + ringPadding

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalWidth, height: totalHeight), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let centerX = totalWidth / 2

            if let ringColor = spec.ringColor {
                let ringRect = CGRect(x: centerX - ringSize / 2, y: ringPadding, width: ringSize, height: ringSize)
                ringColor.setStroke()
                cgContext.setLineWidth(3)
                cgContext.strokeEllipse(in: ringRect.insetBy(dx: 1.5, dy: 1.5))
            }

            cgContext.saveGState()
            cgContext.setShadow(offset: CGSize(width: 0, height: 2), blur: 4, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            let circleRect = CGRect(x: centerX - circleSize / 2, y: ringPadding, width: circleSize, height: circleSize)
            spec.circleColor.setFill()
            cgContext.fillEllipse(in: circleRect)
            cgContext.restoreGState()

            spec.circleColor.setFill()
            cgContext.fillEllipse(in: circleRect)

            if let iconName = spec.iconName {
                let config = UIImage.SymbolConfiguration(pointSize: iconSize, weight: .regular)
                if let icon = UIImage(systemName: iconName, withConfiguration: config)?.withTintColor(.white, renderingMode: .alwaysOriginal) {
                    let iconRect = CGRect(
                        x: centerX - icon.size.width / 2,
                        y: circleRect.midY - icon.size.height / 2,
                        width: icon.size.width,
                        height: icon.size.height
                    )
                    icon.draw(in: iconRect)
                }
            } else if let text = spec.text {
                let font = UIFont.systemFont(ofSize: 14, weight: .bold)
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
                let size = (text as NSString).size(withAttributes: attrs)
                let textRect = CGRect(
                    x: centerX - size.width / 2,
                    y: circleRect.midY - size.height / 2,
                    width: size.width,
                    height: size.height
                )
                (text as NSString).draw(in: textRect, withAttributes: attrs)
            }

            let triangleTop = circleRect.maxY - 3
            let path = UIBezierPath()
            path.move(to: CGPoint(x: centerX - triangleSize / 2, y: triangleTop))
            path.addLine(to: CGPoint(x: centerX + triangleSize / 2, y: triangleTop))
            path.addLine(to: CGPoint(x: centerX, y: triangleTop + triangleSize))
            path.close()
            spec.circleColor.setFill()
            path.fill()

            if let hopIndex, spec.ringColor != nil {
                let badgeSize: CGFloat = 18
                let badgeX = circleRect.maxX + 4 - badgeSize
                let badgeY = circleRect.minY
                let badgeRect = CGRect(x: badgeX, y: badgeY, width: badgeSize, height: badgeSize)

                UIColor.systemBlue.setFill()
                cgContext.fillEllipse(in: badgeRect)

                let text = "\(hopIndex)"
                let font = UIFont.systemFont(ofSize: 11, weight: .bold)
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
                let textSize = (text as NSString).size(withAttributes: attrs)
                let textRect = CGRect(
                    x: badgeRect.midX - textSize.width / 2,
                    y: badgeRect.midY - textSize.height / 2,
                    width: textSize.width,
                    height: textSize.height
                )
                (text as NSString).draw(in: textRect, withAttributes: attrs)
            }
        }
    }

    // MARK: - Label sprite

    private static func renderLabelSprite(text: String) -> UIImage {
        let font = UIFont.systemFont(ofSize: 12, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.black]
        let textSize = (text as NSString).size(withAttributes: attrs)

        let horizontalPadding: CGFloat = 6
        let verticalPadding: CGFloat = 4
        let cornerRadius: CGFloat = 4
        let shadowPadding: CGFloat = 1

        let pillWidth = textSize.width + horizontalPadding * 2
        let pillHeight = textSize.height + verticalPadding * 2
        let totalWidth = pillWidth + shadowPadding * 2
        let totalHeight = pillHeight + shadowPadding * 2

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalWidth, height: totalHeight), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let pillRect = CGRect(x: shadowPadding, y: shadowPadding, width: pillWidth, height: pillHeight)
            let pillPath = UIBezierPath(roundedRect: pillRect, cornerRadius: cornerRadius)

            cgContext.saveGState()
            cgContext.setShadow(offset: CGSize(width: 0, height: 0.5), blur: 1,
                                color: UIColor.black.withAlphaComponent(0.15).cgColor)
            UIColor.white.setFill()
            pillPath.fill()
            cgContext.restoreGState()

            UIColor.white.withAlphaComponent(0.85).setFill()
            pillPath.fill()

            let textRect = CGRect(
                x: shadowPadding + (pillWidth - textSize.width) / 2,
                y: shadowPadding + (pillHeight - textSize.height) / 2,
                width: textSize.width,
                height: textSize.height
            )
            (text as NSString).draw(in: textRect, withAttributes: attrs)
        }
    }

    private static func renderObstruction() -> UIImage {
        let size: CGFloat = 20
        let padding: CGFloat = 3
        let totalSize = size + padding * 2
        let armLength: CGFloat = size / 2 - 1

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalSize, height: totalSize), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let center = CGPoint(x: totalSize / 2, y: totalSize / 2)

            cgContext.setStrokeColor(UIColor.white.cgColor)
            cgContext.setLineWidth(6)
            cgContext.setLineCap(.round)
            cgContext.move(to: CGPoint(x: center.x - armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x + armLength, y: center.y + armLength))
            cgContext.move(to: CGPoint(x: center.x + armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x - armLength, y: center.y + armLength))
            cgContext.strokePath()

            cgContext.setStrokeColor(UIColor.systemRed.cgColor)
            cgContext.setLineWidth(2.5)
            cgContext.setLineCap(.round)
            cgContext.move(to: CGPoint(x: center.x - armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x + armLength, y: center.y + armLength))
            cgContext.move(to: CGPoint(x: center.x + armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x - armLength, y: center.y + armLength))
            cgContext.strokePath()
        }
    }

    private static func renderCrosshair(_ spec: SpriteSpec) -> UIImage {
        let casingWidth: CGFloat = 6
        let capInset = casingWidth / 2
        let size: CGFloat = 44 + capInset * 2
        let gapRadius: CGFloat = 4
        let outerRadius: CGFloat = 22
        let badgeHeight: CGFloat = 20
        let badgeGap: CGFloat = 2
        let topPadding = badgeHeight + badgeGap
        let totalHeight = topPadding + size + badgeGap + badgeHeight

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: totalHeight), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let center = CGPoint(x: size / 2, y: topPadding + size / 2)

            cgContext.setStrokeColor(UIColor.white.cgColor)
            cgContext.setLineWidth(6)
            cgContext.setLineCap(.round)
            cgContext.move(to: CGPoint(x: center.x, y: center.y - outerRadius))
            cgContext.addLine(to: CGPoint(x: center.x, y: center.y - gapRadius))
            cgContext.move(to: CGPoint(x: center.x, y: center.y + gapRadius))
            cgContext.addLine(to: CGPoint(x: center.x, y: center.y + outerRadius))
            cgContext.move(to: CGPoint(x: center.x - outerRadius, y: center.y))
            cgContext.addLine(to: CGPoint(x: center.x - gapRadius, y: center.y))
            cgContext.move(to: CGPoint(x: center.x + gapRadius, y: center.y))
            cgContext.addLine(to: CGPoint(x: center.x + outerRadius, y: center.y))
            cgContext.strokePath()

            cgContext.setStrokeColor(UIColor.systemPurple.cgColor)
            cgContext.setLineWidth(2)
            cgContext.setLineCap(.round)
            cgContext.move(to: CGPoint(x: center.x, y: center.y - outerRadius))
            cgContext.addLine(to: CGPoint(x: center.x, y: center.y - gapRadius))
            cgContext.move(to: CGPoint(x: center.x, y: center.y + gapRadius))
            cgContext.addLine(to: CGPoint(x: center.x, y: center.y + outerRadius))
            cgContext.move(to: CGPoint(x: center.x - outerRadius, y: center.y))
            cgContext.addLine(to: CGPoint(x: center.x - gapRadius, y: center.y))
            cgContext.move(to: CGPoint(x: center.x + gapRadius, y: center.y))
            cgContext.addLine(to: CGPoint(x: center.x + outerRadius, y: center.y))
            cgContext.strokePath()

            let badgeWidth: CGFloat = 20
            let badgeRect = CGRect(x: center.x - badgeWidth / 2, y: topPadding + size + badgeGap, width: badgeWidth, height: badgeHeight)
            let badgePath = UIBezierPath(roundedRect: badgeRect, cornerRadius: 9)
            UIColor.systemPurple.setFill()
            badgePath.fill()

            let font = UIFont.systemFont(ofSize: 11, weight: .bold)
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
            let textSize = ("R" as NSString).size(withAttributes: attrs)
            let textRect = CGRect(
                x: badgeRect.midX - textSize.width / 2,
                y: badgeRect.midY - textSize.height / 2,
                width: textSize.width,
                height: textSize.height
            )
            ("R" as NSString).draw(in: textRect, withAttributes: attrs)
        }
    }
}
