import MapLibre
import UIKit

@MainActor
enum PinSpriteRenderer {
    /// Height of a standard pin sprite in points (circle + triangle pointer).
    /// Used by the map Coordinator to position callout anchors above the pin icon.
    static let standardHeight: CGFloat = 43 // 36 (circle) + 10 (triangle) - 3 (overlap)

    static let labelSpritePrefix = "label-"

    private static var cachedImages: [String: UIImage]?

    /// Single cached dropped-pin sprite for the chat map thumbnail. Distinct from
    /// `cachedImages`, which is only populated after the Map tab loads its GL
    /// style; the chat snapshot path never loads that style.
    private static var cachedDroppedPin: UIImage?

    /// The dropped-pin sprite (systemPink circle + `mappin`), rendered once and
    /// reused. Coordinate-independent, so the chat thumbnail composites the exact
    /// pin the Map tab drops. Must be called on the main actor.
    static func droppedPinSprite() -> UIImage {
        if let cached = cachedDroppedPin { return cached }
        guard let spec = allSpecs.first(where: { $0.name == "pin-dropped" }) else {
            return UIImage()
        }
        let image = render(spec)
        cachedDroppedPin = image
        return image
    }

    /// Registers base pin sprites into the style. Hop-ring variants are rendered
    /// lazily via `renderOnDemand(name:into:)` when MapLibre requests a missing image.
    static func renderAll(into style: MLNStyle) {
        var rendered: [String: UIImage] = [:]
        for spec in allSpecs {
            rendered[spec.name] = render(spec)
        }
        rendered["pin-badge"] = UIGraphicsImageRenderer(
            size: CGSize(width: 1, height: 1), format: .preferred()
        ).image { _ in }
        rendered["pill-bg"] = renderPillBackground()
        cachedImages = rendered

        for (name, image) in rendered {
            style.setImage(image, forName: name)
        }
    }

    /// Renders a hop-ring sprite on demand when MapLibre requests a missing image name.
    /// Returns the rendered image so the caller can pass it back to MapLibre as
    /// the immediate fallback, avoiding a single-frame blink.
    static func renderOnDemand(name: String, into style: MLNStyle) -> UIImage? {
        if let cached = cachedImages?[name] {
            style.setImage(cached, forName: name)
            return cached
        }

        let image: UIImage
        if name.hasPrefix("pin-repeater-ring-white-hop-") {
            guard let hopString = name.split(separator: "-").last,
                  let hop = Int(hopString),
                  (1...20).contains(hop),
                  let ringWhiteSpec = allSpecs.first(where: { $0.name == "pin-repeater-ring-white" }) else {
                return nil
            }
            image = render(ringWhiteSpec, hopIndex: hop)
        } else if name.hasPrefix(labelSpritePrefix) {
            let text = String(name.dropFirst(labelSpritePrefix.count))
            guard !text.isEmpty else { return nil }
            image = renderLabelSprite(text: text)
        } else {
            return nil
        }

        cachedImages?[name] = image
        style.setImage(image, forName: name)
        return image
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
        let iconName: String?    // SF Symbol name
        let text: String?        // e.g. "A", "B" for point pins
        let ringColor: UIColor?  // selection ring
        let renderStyle: RenderStyle
    }

    private static let allSpecs: [SpriteSpec] = [
        // Main map contacts
        SpriteSpec(name: "pin-chat", circleColor: UIColor(red: 204 / 255, green: 122 / 255, blue: 92 / 255, alpha: 1),
                   iconName: "person.fill", text: nil, ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-room", circleColor: UIColor(red: 1, green: 136 / 255, blue: 0, alpha: 1),
                   iconName: "person.3.fill", text: nil, ringColor: nil, renderStyle: .standard),

        // LOS/TracePath repeater states
        SpriteSpec(name: "pin-repeater-ring-blue", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .systemBlue, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater-ring-green", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .systemGreen, renderStyle: .standard),
        SpriteSpec(name: "pin-repeater-ring-white", circleColor: .systemCyan,
                   iconName: "antenna.radiowaves.left.and.right", text: nil, ringColor: .white, renderStyle: .standard),

        // LOS point pins
        SpriteSpec(name: "pin-point-a", circleColor: .systemBlue,
                   iconName: nil, text: "A", ringColor: nil, renderStyle: .standard),
        SpriteSpec(name: "pin-point-b", circleColor: .systemGreen,
                   iconName: nil, text: "B", ringColor: nil, renderStyle: .standard),

        // LOS crosshair target
        SpriteSpec(name: "pin-crosshair", circleColor: .systemPurple,
                   iconName: nil, text: "R", ringColor: nil, renderStyle: .crosshair),

        // LOS obstruction marker
        SpriteSpec(name: "pin-obstruction", circleColor: .systemRed,
                   iconName: nil, text: nil, ringColor: nil, renderStyle: .obstruction),

        // Chat-dropped coordinate pin
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

            // Selection ring, centered on the circle head so it encircles the
            // round portion rather than the triangle tail.
            if let ringColor = spec.ringColor {
                let circleCenterY = ringPadding + circleSize / 2
                let ringRect = CGRect(
                    x: centerX - ringSize / 2,
                    y: circleCenterY - ringSize / 2,
                    width: ringSize,
                    height: ringSize
                )
                ringColor.setStroke()
                cgContext.setLineWidth(3)
                cgContext.strokeEllipse(in: ringRect.insetBy(dx: 1.5, dy: 1.5))
            }

            // Circle shadow
            cgContext.saveGState()
            cgContext.setShadow(offset: CGSize(width: 0, height: 2), blur: 4, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            let circleRect = CGRect(
                x: centerX - circleSize / 2,
                y: ringPadding,
                width: circleSize,
                height: circleSize
            )
            spec.circleColor.setFill()
            cgContext.fillEllipse(in: circleRect)
            cgContext.restoreGState()

            // Circle (again without shadow for crisp edge)
            spec.circleColor.setFill()
            cgContext.fillEllipse(in: circleRect)

            // Icon or text
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

            // Triangle pointer
            let triangleTop = circleRect.maxY - 3
            let path = UIBezierPath()
            path.move(to: CGPoint(x: centerX - triangleSize / 2, y: triangleTop))
            path.addLine(to: CGPoint(x: centerX + triangleSize / 2, y: triangleTop))
            path.addLine(to: CGPoint(x: centerX, y: triangleTop + triangleSize))
            path.close()
            spec.circleColor.setFill()
            path.fill()

            // Hop badge overlay (ring pins only)
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

    // MARK: - Pill sprites

    /// Semi-transparent stretchable pill for stats badges.
    /// Registered as a resizable image so MapLibre's `iconTextFit` can stretch
    /// the flat center while preserving the rounded caps.
    private static func renderPillBackground() -> UIImage {
        let cornerRadius: CGFloat = 4
        let size: CGFloat = 2 * cornerRadius + 2
        let shadowPadding: CGFloat = 1
        let totalSize = size + shadowPadding * 2
        let capInset = cornerRadius + shadowPadding

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalSize, height: totalSize), format: .preferred())
        let image = renderer.image { ctx in
            let cgContext = ctx.cgContext
            let pillRect = CGRect(x: shadowPadding, y: shadowPadding, width: size, height: size)
            let pillPath = UIBezierPath(roundedRect: pillRect, cornerRadius: cornerRadius)

            // Shadow pass
            cgContext.saveGState()
            cgContext.setShadow(
                offset: CGSize(width: 0, height: 0.5),
                blur: 1,
                color: UIColor.black.withAlphaComponent(0.15).cgColor
            )
            UIColor.white.setFill()
            pillPath.fill()
            cgContext.restoreGState()

            // Light fill for readability in both light and dark mode
            UIColor.white.withAlphaComponent(0.85).setFill()
            pillPath.fill()
        }

        return image.resizableImage(
            withCapInsets: UIEdgeInsets(top: capInset, left: capInset, bottom: capInset, right: capInset),
            resizingMode: .stretch
        )
    }

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

        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: totalWidth, height: totalHeight),
            format: .preferred()
        )
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let pillRect = CGRect(x: shadowPadding, y: shadowPadding, width: pillWidth, height: pillHeight)
            let pillPath = UIBezierPath(roundedRect: pillRect, cornerRadius: cornerRadius)

            cgContext.saveGState()
            cgContext.setShadow(
                offset: CGSize(width: 0, height: 0.5),
                blur: 1,
                color: UIColor.black.withAlphaComponent(0.15).cgColor
            )
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
        let casingWidth: CGFloat = 6
        let strokeWidth: CGFloat = 2.5

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalSize, height: totalSize), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let center = CGPoint(x: totalSize / 2, y: totalSize / 2)

            // Draw white casing (thick white stroke behind the red X)
            cgContext.setStrokeColor(UIColor.white.cgColor)
            cgContext.setLineWidth(casingWidth)
            cgContext.setLineCap(.round)

            cgContext.move(to: CGPoint(x: center.x - armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x + armLength, y: center.y + armLength))
            cgContext.move(to: CGPoint(x: center.x + armLength, y: center.y - armLength))
            cgContext.addLine(to: CGPoint(x: center.x - armLength, y: center.y + armLength))
            cgContext.strokePath()

            // Draw red X on top
            cgContext.setStrokeColor(UIColor.systemRed.cgColor)
            cgContext.setLineWidth(strokeWidth)
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
        // Top padding so the crosshair center sits at the image's vertical midpoint
        let topPadding = badgeHeight + badgeGap
        let totalHeight = topPadding + size + badgeGap + badgeHeight

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: totalHeight), format: .preferred())
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            let center = CGPoint(x: size / 2, y: topPadding + size / 2)

            // White casing behind crosshair lines
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

            // Crosshair lines
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

            // "R" badge
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
