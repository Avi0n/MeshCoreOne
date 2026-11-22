import MC1Services

extension PixelPaintDraft {
  static let historyLimit = 100
  private static let neighborOffsets = [(0, -1), (0, 1), (-1, 0), (1, 0)]

  private static var blank: [Int] {
    Array(repeating: background, count: side * side)
  }

  var canUndo: Bool {
    !undoStack.isEmpty
  }

  var canRedo: Bool {
    !redoStack.isEmpty
  }

  var isBlank: Bool {
    pixels.allSatisfy { $0 == Self.background }
  }

  func isOverBudget(for transport: PixelPaintTransport) -> Bool {
    encodedByteCount.map { $0 > transport.byteLimit } ?? false
  }

  func paint(x: Int, y: Int) {
    guard !isSubmitting else { return }
    guard Self.contains(x: x, y: y) else {
      // Re-entering the canvas starts a new segment instead of joining the exit point.
      previousCell = nil
      return
    }
    if strokeStart == nil { strokeStart = pixels }
    let color = tool == .eraser ? Self.background : selectedColor
    let start = previousCell ?? (x, y)
    let steps = max(abs(x - start.x), abs(y - start.y))
    var changed = false
    for step in 0...steps {
      let fraction = steps == 0 ? 0 : Double(step) / Double(steps)
      let column = start.x + Int((Double(x - start.x) * fraction).rounded())
      let row = start.y + Int((Double(y - start.y) * fraction).rounded())
      let index = row * Self.side + column
      // Equal-value writes still notify every `pixels` observer.
      if pixels[index] != color {
        pixels[index] = color
        changed = true
      }
    }
    if changed { paintedCellRevision &+= 1 }
    previousCell = (x, y)
  }

  func endStroke() {
    if let strokeStart, strokeStart != pixels {
      record(strokeStart)
    }
    previousCell = nil
    strokeStart = nil
  }

  func fill(x: Int, y: Int) {
    guard !isSubmitting, Self.contains(x: x, y: y) else { return }
    endStroke()
    let target = pixels[y * Self.side + x]
    guard target != selectedColor else { return }
    var filled = pixels
    var pending = [(x, y)]
    while let (column, row) = pending.popLast() {
      let index = row * Self.side + column
      guard filled[index] == target else { continue }
      filled[index] = selectedColor
      for (dx, dy) in Self.neighborOffsets where Self.contains(x: column + dx, y: row + dy) {
        pending.append((column + dx, row + dy))
      }
    }
    record(pixels)
    pixels = filled
  }

  func undo() {
    guard !isSubmitting else { return }
    endStroke()
    guard let previous = undoStack.popLast() else { return }
    redoStack.append(pixels)
    pixels = previous
  }

  func redo() {
    guard !isSubmitting else { return }
    endStroke()
    guard let next = redoStack.popLast() else { return }
    undoStack.append(pixels)
    pixels = next
  }

  func clear() {
    guard !isSubmitting else { return }
    endStroke()
    guard !isBlank else { return }
    record(pixels)
    pixels = Self.blank
  }

  /// Drops the open stroke without committing it, so a retired drawing never reaches the history.
  func resetPixels() {
    previousCell = nil
    strokeStart = nil
    pixels = Self.blank
    undoStack.removeAll()
    redoStack.removeAll()
    tool = .brush
    encodedByteCount = nil
  }

  func measure(
    transport: PixelPaintTransport,
    senderName: String,
    encode: @Sendable (MCOImageRaster, PixelPaintTransport) async throws -> String = encodePixelDrawing
  ) async {
    let measured = pixels
    let count: Int?
    do {
      let text = try await encode(image, transport)
      count = try transport.encodedByteCount(text: text, senderName: senderName)
    } catch {
      count = nil
    }
    guard !Task.isCancelled, measured == pixels else { return }
    encodedByteCount = count
  }

  private func record(_ previous: [Int]) {
    undoStack.append(previous)
    if undoStack.count > Self.historyLimit {
      undoStack.removeFirst(undoStack.count - Self.historyLimit)
    }
    redoStack.removeAll()
  }

  private static func contains(x: Int, y: Int) -> Bool {
    (0..<side).contains(x) && (0..<side).contains(y)
  }
}
