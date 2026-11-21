public struct MCOImageV4Point: Sendable, Hashable {
  public let x: Int
  public let y: Int

  public init(x: Int, y: Int) {
    self.x = x
    self.y = y
  }

  func translated(x: Int, y: Int) -> Self {
    Self(x: self.x + x, y: self.y + y)
  }
}
