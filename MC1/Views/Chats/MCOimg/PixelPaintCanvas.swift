import MC1Services
import SwiftUI

struct PixelPaintCanvas: View {
  let draft: PixelPaintDraft
  let showsGrid: Bool
  @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled
  @State private var canvasWidth: CGFloat = 0
  @State private var cursorColumn = PixelPaintDraft.side / 2
  @State private var cursorRow = PixelPaintDraft.side / 2
  @State private var hasFilledThisGesture = false
  private static let gridOpacity = 0.25
  private static let gridLineWidth: CGFloat = 0.5
  private static let cursorOutlineWidth: CGFloat = 2
  private static let side = PixelPaintDraft.side

  var body: some View {
    Canvas { context, size in
      let cell = size.width / CGFloat(Self.side)
      var pixels = context
      pixels.scaleBy(x: cell, y: cell)
      MCOImageRenderer.raster(draft.image, origin: .zero, context: &pixels)
      if showsGrid {
        context.stroke(Self.grid(cell: cell, length: size.width),
                       with: .color(.primary.opacity(Self.gridOpacity)), lineWidth: Self.gridLineWidth)
      }
      if voiceOverEnabled {
        let cursor = CGRect(x: CGFloat(cursorColumn) * cell, y: CGFloat(cursorRow) * cell, width: cell, height: cell)
        context.stroke(Path(cursor), with: .color(.accentColor), lineWidth: Self.cursorOutlineWidth)
      }
    }
    .aspectRatio(1, contentMode: .fit)
    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
      // Hit testing uses cell edges, matching `MCOImageRenderer` raster origins.
      guard canvasWidth > 0 else { return }
      apply(x: Int(floor(value.location.x / canvasWidth * Double(Self.side))),
            y: Int(floor(value.location.y / canvasWidth * Double(Self.side))))
    }.onEnded { _ in
      hasFilledThisGesture = false
      draft.endStroke()
    })
    .allowsHitTesting(!draft.isSubmitting)
    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { canvasWidth = $0 }
    .sensoryFeedback(.impact(weight: .light), trigger: draft.paintedCellRevision)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Chats.Chats.Mcoimg.canvas)
    .accessibilityValue(L10n.Chats.Chats.Mcoimg.cursorPosition(
      cursorColumn + 1, cursorRow + 1,
      PixelPaintDraft.colorName(draft.pixels[cursorRow * Self.side + cursorColumn])
    ))
    .accessibilityHint(L10n.Chats.Chats.Mcoimg.canvasHint)
    .accessibilityIdentifier("pixelPaintCanvas")
    .accessibilityAdjustableAction { direction in
      switch direction {
      case .increment: stepCursor(by: 1)
      case .decrement: stepCursor(by: -1)
      @unknown default: break
      }
    }
    .accessibilityAction(named: L10n.Chats.Chats.Mcoimg.moveUp) { moveCursor(columns: 0, rows: -1) }
    .accessibilityAction(named: L10n.Chats.Chats.Mcoimg.moveDown) { moveCursor(columns: 0, rows: 1) }
    .accessibilityAction(named: L10n.Chats.Chats.Mcoimg.moveLeft) { moveCursor(columns: -1, rows: 0) }
    .accessibilityAction(named: L10n.Chats.Chats.Mcoimg.moveRight) { moveCursor(columns: 1, rows: 0) }
    .accessibilityAction { paintAtCursor() }
  }

  private func apply(x: Int, y: Int) {
    if draft.tool == .fill {
      guard !hasFilledThisGesture else { return }
      hasFilledThisGesture = true
      draft.fill(x: x, y: y)
    } else {
      draft.paint(x: x, y: y)
    }
  }

  private func stepCursor(by delta: Int) {
    let last = Self.side * Self.side - 1
    let index = min(max(cursorRow * Self.side + cursorColumn + delta, 0), last)
    cursorRow = index / Self.side
    cursorColumn = index % Self.side
  }

  private func moveCursor(columns: Int, rows: Int) {
    cursorColumn = min(max(cursorColumn + columns, 0), Self.side - 1)
    cursorRow = min(max(cursorRow + rows, 0), Self.side - 1)
  }

  private func paintAtCursor() {
    guard !draft.isSubmitting else { return }
    draft.endStroke()
    apply(x: cursorColumn, y: cursorRow)
    hasFilledThisGesture = false
    draft.endStroke()
  }

  private static func grid(cell: CGFloat, length: CGFloat) -> Path {
    var path = Path()
    for line in 0...side {
      let offset = CGFloat(line) * cell
      path.move(to: CGPoint(x: offset, y: 0))
      path.addLine(to: CGPoint(x: offset, y: length))
      path.move(to: CGPoint(x: 0, y: offset))
      path.addLine(to: CGPoint(x: length, y: offset))
    }
    return path
  }
}
