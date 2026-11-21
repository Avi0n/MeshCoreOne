import Foundation

@main
struct VerifyV3 {
  static let profileNames = [
    "mono", "master4", "master8", "master16", "master32", "master64", "grayscale16", "grayscale32", "grayscale8",
    "dynamicGlobal8", "dynamicGlobal16", "dynamicGlobal32", "dynamicGlobal64", "dynamicGlobal128", "dynamicGlobal256", "dynamicGlobal512"
  ]

  static func generatedFixture(_ name: String, width: Int, height: Int, profile: Int, pixels: [Int], nonce: UInt8) throws -> [String: Any] {
    let image = MCOImageRaster(width: width, height: height, profile: profile, pixels: pixels)
    let body = try MCOImageV3Codec.encode(image, nonce: nonce)
    return ["name": name, "width": width, "height": height, "paletteProfile": profileNames[profile], "pixels": pixels,
            "transparentColor": NSNull(), "bodyBase64": body.base64EncodedString(), "text": "im3:" + MCOImageBase91.encode(Data([0x13]) + body)]
  }

  static func main() throws {
    let fixtureURL = URL(fileURLWithPath: CommandLine.arguments[1])
    let document = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as! [String: Any]
    var fixtures = document["fixtures"] as! [[String: Any]]
    var painted = Array(repeating: 0, count: 16 * 16)
    for y in 3...12 {
      for x in 3...12 where y == 3 || y == 12 || x == 3 || x == 12 { painted[y * 16 + x] = 6 }
    }
    for y in 6...9 { for x in 6...9 { painted[y * 16 + x] = 3 } }
    fixtures.append(try generatedFixture("painted16", width: 16, height: 16, profile: 2, pixels: painted, nonce: 0))
    let dense = (0..<(16 * 16)).map { (($0 % 16) * 17 + ($0 / 16) * 29 + (($0 % 16) * ($0 / 16))) % 64 }
    fixtures.append(try generatedFixture("dense16", width: 16, height: 16, profile: 5, pixels: dense, nonce: 255))
    for profile in 0..<16 {
      let refs = MCOImagePalette.profileReferences(profile)
      let pixels = (0..<12).map { refs[($0 * 7 + $0 / 4) % refs.count] }
      for nonce: UInt8 in [0, 255] {
        fixtures.append(try generatedFixture("profile-\(profile)-nonce-\(nonce)", width: 4, height: 3, profile: profile, pixels: pixels, nonce: nonce))
      }
    }
    var generated: [[String: Any]] = []
    for fixture in fixtures {
      let name = fixture["name"] as! String
      let body = Data(base64Encoded: fixture["bodyBase64"] as! String)!
      let profile = profileNames.firstIndex(of: fixture["paletteProfile"] as! String)!
      let expected = MCOImageRaster(width: fixture["width"] as! Int, height: fixture["height"] as! Int,
                                    profile: profile, pixels: fixture["pixels"] as! [Int], transparentColor: fixture["transparentColor"] as? Int)
      do {
        let actual = try MCOImageV3Codec.decodeBody(body)
        guard actual == expected else { fatalError("Fixture differs: \(name)") }
        guard try MCOImageBase91.decode(String((fixture["text"] as! String).dropFirst(4))) == Data([0x13]) + body else {
          fatalError("Base91 differs: \(name)")
        }
        let encoded = try MCOImageV3Codec.encode(expected, nonce: body.first!)
        guard try MCOImageV3Codec.decodeBody(encoded) == expected else { fatalError("Roundtrip differs: \(name)") }
        var output = fixture
        output["name"] = "swift-\(name)"
        output["bodyBase64"] = encoded.base64EncodedString()
        let app = Data([0x13]) + encoded
        output["appPayloadBase64"] = app.base64EncodedString()
        output["text"] = "im3:" + MCOImageBase91.encode(app)
        output.removeValue(forKey: "container")
        output.removeValue(forKey: "algorithm")
        generated.append(output)
      } catch { fatalError("Fixture \(name): \(error)") }
    }
    if CommandLine.arguments.count > 2 {
      try JSONSerialization.data(withJSONObject: ["schema": 1, "generator": "scripts/mcoimg/verify-v3.swift", "fixtures": generated], options: [.prettyPrinted, .sortedKeys])
        .write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
    }
    print("Verified 39 upstream v3 fixtures and \(fixtures.count) Swift encoder roundtrips")
  }
}
