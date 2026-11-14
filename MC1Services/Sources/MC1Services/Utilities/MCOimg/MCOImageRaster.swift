/// A decoded MCOimg raster. Dynamic profiles use global512 color references;
/// fixed profiles use indexes within their profile palette.
public struct MCOImageRaster: Sendable, Hashable {
  public let width: Int
  public let height: Int
  public let profile: Int
  public let pixels: [Int]
  public let transparentColor: Int?

  public init(width: Int, height: Int, profile: Int, pixels: [Int], transparentColor: Int? = nil) {
    self.width = width
    self.height = height
    self.profile = profile
    self.pixels = pixels
    self.transparentColor = transparentColor
  }
}
