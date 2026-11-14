import Foundation

public enum MCOImageV4Codec {
  public static func decodeBody(_ body: Data) throws -> MCOImageV4Document {
    guard !body.isEmpty else { throw MCOImageCodecError.damaged }
    var parser = MCOImageV4Parser(Data(body.dropFirst()))
    return try parser.decode()
  }
}
