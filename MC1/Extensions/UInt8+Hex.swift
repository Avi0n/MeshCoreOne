import Foundation

extension UInt8 {
  /// Two-character uppercase hex string (e.g., "0A", "FF")
  var hexString: String {
    let hex = String(self, radix: 16, uppercase: true)
    return hex.count < 2 ? "0" + hex : hex
  }
}
