import Foundation

enum MCOTextCodec {
  static func decode(_ data: Data, bitLength: Int) throws -> String {
    var decoder = try MCOTextDecoder(data, bitLength: bitLength)
    return try decoder.decode()
  }
}
