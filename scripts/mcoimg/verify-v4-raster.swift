import Foundation

@main
struct VerifyV4Raster {
  static func main() throws {
    let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
    let document = try JSONSerialization.jsonObject(with: input) as! [String: Any]
    var fixtures = document["fixtures"] as! [[String: Any]]
    for (width, height) in [(1, 1), (32, 31), (33, 32), (64, 64), (65, 65), (256, 1), (1, 256)] {
      let image = MCOImageRaster(width: width, height: height, profile: 2, pixels: Array(repeating: 0, count: width * height))
      fixtures.append(["name": "dimensions-\(width)x\(height)",
                       "bodyBase64": try MCOImageV3Codec.encode(image, nonce: 42).base64EncodedString()])
    }
    var output: [[String: Any]] = []
    for fixture in fixtures {
      let source = Data(base64Encoded: fixture["bodyBase64"] as! String)!
      let image = try MCOImageV3Codec.decodeBody(source)
      let body = try MCOImageV4Codec.wrappingV3Body(source)
      let decoded = try MCOImageV4Codec.decodeBody(body)
      let background = image.transparentColor == nil ? MCOImagePalette.white(profile: image.profile) : nil
      guard decoded.width == image.width, decoded.height == image.height, decoded.profile == image.profile,
            decoded.backgroundColor == background,
            decoded.figures == [.raster(origin: .init(x: 0, y: 0), image: image)], body.first == source.first else {
        fatalError("Swift mixed raster differs: \(fixture["name"]!)")
      }
      let app = Data([MCOImageCodec.v4Subtype]) + body
      output.append(["name": fixture["name"]!, "width": image.width, "height": image.height,
                     "profile": image.profile, "pixels": image.pixels,
                     "transparentColor": image.transparentColor as Any? ?? NSNull(),
                     "nonce": Int(source.first!), "sourceV3BodyBase64": source.base64EncodedString(),
                     "bodyBase64": body.base64EncodedString(), "appPayloadBase64": app.base64EncodedString(),
                     "text": try MCOImageCodec.text(fromAppPayload: app)])
    }
    try JSONSerialization.data(withJSONObject: ["fixtures": output], options: [.prettyPrinted, .sortedKeys])
      .write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
    print("Verified \(output.count) Swift v4 mixed raster wraps")
  }
}
