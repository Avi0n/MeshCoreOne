public struct MCOImageV4Style: Sendable, Hashable {
  public var fillColor: Int?
  public var strokeColor: Int?
  public var strokeWidth: Int

  public init(fillColor: Int?, strokeColor: Int?, strokeWidth: Int) {
    self.fillColor = fillColor
    self.strokeColor = strokeColor
    self.strokeWidth = strokeWidth
  }
}
