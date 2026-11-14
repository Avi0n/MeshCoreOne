import Foundation

extension MCOImageV4Codec {
  /// Embeds the original compressed raster without its packet nonce or repeated profile nibble.
  public static func wrappingV3Body(_ body: Data) throws -> Data {
    let image = try MCOImageV3Codec.decodeBody(body)
    let field = MCOImageV4Parser.Field.self
    let mixedMode = 1
    let rasterExtension = 14
    let headerBits = 4
    var writer = MCOImageBitWriter()
    writer.write(mixedMode, field.modeBits)
    writer.write(image.profile, field.profileBits)
    writeRasterDimensions(image.width, image.height, writer: &writer)
    writer.write(image.transparentColor == nil ? 0 : 1, 1)
    if image.transparentColor != nil { writer.write(0, 1) }
    writer.write(MCOImageV4Parser.Opcode.extended.rawValue, field.opcodeBits)
    writer.write(rasterExtension, field.opcodeBits)
    for size in [image.width, image.height] {
      let margin = min(field.maximumOverscan, max(1, (size + field.overscanDivisor - 1) / field.overscanDivisor))
      writer.write(margin, MCOImageV4Parser.bits(size + 2 * margin - 1))
    }
    let bytes = Array(body)
    writer.write(Int(bytes[1]) >> headerBits, headerBits)
    writer.compact(bytes.count - 2)
    for byte in bytes.dropFirst(2) { writer.write(Int(byte), field.byteBits) }
    writer.write(MCOImageV4Parser.Opcode.end.rawValue, field.opcodeBits)
    return Data([bytes[0]]) + writer.data
  }

  private static func writeRasterDimensions(_ width: Int, _ height: Int, writer: inout MCOImageBitWriter) {
    let field = MCOImageV4Parser.Field.self
    let squareMode = 0, smallMode = 1, mediumMode = 2, extendedMode = 3
    if width == height && width <= field.mediumDimensionMaximum {
      writer.write(squareMode, field.modeBits)
      writer.write(width - 1, field.mediumDimensionBits)
    } else if max(width, height) <= field.smallDimensionMaximum {
      writer.write(smallMode, field.modeBits)
      writer.write(width - 1, field.smallDimensionBits)
      writer.write(height - 1, field.smallDimensionBits)
    } else if max(width, height) <= field.mediumDimensionMaximum {
      writer.write(mediumMode, field.modeBits)
      writer.write(width - 1, field.mediumDimensionBits)
      writer.write(height - 1, field.mediumDimensionBits)
    } else {
      writer.write(extendedMode, field.modeBits)
      writer.write(width == height ? 0 : 1, 1)
      writer.write(width - 1, field.byteBits)
      if width != height { writer.write(height - 1, field.byteBits) }
    }
  }
}
