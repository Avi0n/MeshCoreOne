import MC1Services
import SwiftUI

extension MCOImageRenderer {
  private static let lineHeightScale: CGFloat = 1.2
  private static let leftAlignment = 0
  private static let centerAlignment = 1

  /// Fonts and line metrics are client choices; width, alignment, colors and origin come from the document.
  static func drawText(_ text: String, origin: MCOImageV4Point, width: Int?, alignment: Int, fontSize: Int,
                       style: MCOImageV4Style, profile: Int, canvasWidth: Int, context: inout GraphicsContext) {
    let resolvedWidth = width ?? max(1, min(canvasWidth, canvasWidth - origin.x))
    let origin = MCOImageGeometry.point(origin)
    let width = CGFloat(resolvedWidth)
    let lines = wrappedLines(text, width: width, fontSize: fontSize, context: context)
    let lineHeight = CGFloat(fontSize) * lineHeightScale
    if let fill = style.fillColor {
      context.fill(Path(CGRect(x: origin.x, y: origin.y, width: width, height: lineHeight * CGFloat(lines.count))),
                   with: .color(color(fill, profile: profile)))
    }
    guard let stroke = style.strokeColor else { return }
    for (index, line) in lines.enumerated() {
      let resolved = context.resolve(Text(line).font(.system(size: CGFloat(fontSize))).foregroundColor(color(stroke, profile: profile)))
      let measured = resolved.measure(in: CGSize(width: width, height: CGFloat.greatestFiniteMagnitude))
      let offset: CGFloat = switch alignment {
      case leftAlignment: 0
      case centerAlignment: (width - measured.width) / 2
      default: width - measured.width
      }
      context.draw(resolved, at: CGPoint(x: origin.x + offset, y: origin.y + CGFloat(index) * lineHeight), anchor: .topLeading)
    }
  }

  private static func wrappedLines(_ text: String, width: CGFloat, fontSize: Int, context: GraphicsContext) -> [String] {
    var lines: [String] = []
    for paragraph in text.components(separatedBy: "\n") {
      var line = ""
      for character in paragraph {
        let candidate = line + String(character)
        let measured = context.resolve(Text(candidate).font(.system(size: CGFloat(fontSize))))
          .measure(in: CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude))
        if !line.isEmpty, measured.width > width {
          lines.append(line)
          line = String(character)
        } else {
          line = candidate
        }
      }
      lines.append(line)
    }
    return lines
  }
}
