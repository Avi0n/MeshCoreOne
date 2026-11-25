import Foundation

/// A complete decoded image or a terminal format result; damaged streams never paint partially.
public enum MCOImageContent: Sendable, Hashable {
  case raster(MCOImageRaster)
  case document(MCOImageV4Document)
  case unsupported
  case damaged
}
