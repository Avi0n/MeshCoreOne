import MC1Services
import Observation

/// Pixels belong to the conversation composer, so presenting or closing the editor never replaces them.
@Observable
@MainActor
final class PixelPaintDraft {
  static let side = 16
  static let profile = 2
  static let background = 0
  static let initialBrush = 2

  var pixels = Array(repeating: background, count: side * side)
  var selectedColor = initialBrush
  var isStaged = false
  var isSubmitting = false
  var errorMessage: String?
  private var previousCell: (x: Int, y: Int)?

  var image: MCOImageRaster {
    MCOImageRaster(width: Self.side, height: Self.side, profile: Self.profile, pixels: pixels)
  }

  func paint(x: Int, y: Int) {
    guard !isSubmitting, (0..<Self.side).contains(x), (0..<Self.side).contains(y) else { return }
    let start = previousCell ?? (x, y)
    let steps = max(abs(x - start.x), abs(y - start.y))
    if steps == 0 {
      pixels[y * Self.side + x] = selectedColor
    } else {
      for step in 0...steps {
        let column = start.x + Int((Double(x - start.x) * Double(step) / Double(steps)).rounded())
        let row = start.y + Int((Double(y - start.y) * Double(step) / Double(steps)).rounded())
        pixels[row * Self.side + column] = selectedColor
      }
    }
    previousCell = (x, y)
  }

  func endStroke() { previousCell = nil }

  func clear() {
    pixels = Array(repeating: Self.background, count: Self.side * Self.side)
    endStroke()
  }

  func remove() {
    clear()
    isStaged = false
    errorMessage = nil
  }
}
