import Foundation
@testable import MC1Services
import Testing

struct MCOImageV3CodecTests {
  private static let profileNames = [
    "mono", "master4", "master8", "master16", "master32", "master64", "grayscale16", "grayscale32", "grayscale8",
    "dynamicGlobal8", "dynamicGlobal16", "dynamicGlobal32", "dynamicGlobal64", "dynamicGlobal128", "dynamicGlobal256", "dynamicGlobal512"
  ]

  private struct Fixtures: Decodable {
    let fixtures: [Fixture]
  }

  private struct Fixture: Decodable {
    let name: String
    let bodyBase64: String
    let text: String
    let width: Int
    let height: Int
    let paletteProfile: String
    let pixels: [Int]
    let transparentColor: Int?

    var image: MCOImageRaster {
      MCOImageRaster(width: width, height: height, profile: MCOImageV3CodecTests.profileNames.firstIndex(of: paletteProfile)!,
                     pixels: pixels, transparentColor: transparentColor)
    }
  }

  private func fixtures() throws -> [Fixture] {
    let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .appendingPathComponent("../../../scripts/mcoimg/fixtures/v3-upstream-decoder.json").standardizedFileURL
    return try JSONDecoder().decode(Fixtures.self, from: Data(contentsOf: file)).fixtures
  }

  @Test func decodesAllUpstreamContainersAndAlgorithms() throws {
    let fixtures = try fixtures()
    #expect(fixtures.count == 39)
    for fixture in fixtures {
      let body = try #require(Data(base64Encoded: fixture.bodyBase64))
      #expect(try MCOImageV3Codec.decodeBody(body) == fixture.image, "\(fixture.name)")
      #expect(try MCOImageV3Codec.decodeBodyWithoutPacketNonce(Data(body.dropFirst())) == fixture.image, "\(fixture.name)")
      #expect(try MCOImageBase91.decode(String(fixture.text.dropFirst(4))) == Data([0x13]) + body, "\(fixture.name)")
    }
  }

  @Test func encodingPreservesPixelsAcrossProfilesAndNonces() throws {
    for profile in 0..<MCOImagePalette.profileCount {
      let refs = MCOImagePalette.profileReferences(profile)
      let pixels = (0..<256).map { refs[($0 % 16 + $0 / 16) % refs.count] }
      let image = MCOImageRaster(width: 16, height: 16, profile: profile, pixels: pixels, transparentColor: refs.first)
      let first = try MCOImageV3Codec.encode(image, nonce: 0)
      let second = try MCOImageV3Codec.encode(image, nonce: 255)
      #expect(first.first == 0)
      #expect(second.first == 255)
      #expect(try MCOImageV3Codec.decodeBody(first) == image)
      #expect(try MCOImageV3Codec.decodeBody(second) == image)
    }
  }

  @Test func truncationAndTrailingBytesDoNotRenderPartialImages() throws {
    for fixture in try fixtures() {
      let body = try #require(Data(base64Encoded: fixture.bodyBase64))
      for count in 0..<body.count {
        #expect(throws: MCOImageCodecError.self) { try MCOImageV3Codec.decodeBody(body.prefix(count)) }
      }
      #expect(throws: MCOImageCodecError.damaged) { try MCOImageV3Codec.decodeBody(body + Data([0])) }
    }
  }

  @Test func distinguishesUnknownAlgorithmsFromDamagedGeometryAndPadding() throws {
    #expect(throws: MCOImageCodecError.unsupported) { try MCOImageV3Codec.decodeBody(Data([0, 0, 0, 31])) }
    var noncanonical = MCOImageBitWriter()
    noncanonical.write(0, 8)
    noncanonical.write(0, 8)
    noncanonical.write(1, 2)
    noncanonical.write(3, 5)
    noncanonical.write(3, 5)
    noncanonical.write(192, 8)
    #expect(throws: MCOImageCodecError.damaged) { try MCOImageV3Codec.decodeBody(noncanonical.data) }
    let solid = MCOImageRaster(width: 1, height: 1, profile: 15, pixels: [511])
    var corruptPadding = try MCOImageV3Codec.encode(solid, nonce: 0)
    corruptPadding[corruptPadding.count - 1] |= 128
    #expect(throws: MCOImageCodecError.damaged) { try MCOImageV3Codec.decodeBody(corruptPadding) }
  }

  @Test func rejectsInvalidPixelReferencesAndDimensions() {
    for image in [
      MCOImageRaster(width: 0, height: 1, profile: 0, pixels: []),
      MCOImageRaster(width: 257, height: 1, profile: 0, pixels: Array(repeating: 0, count: 257)),
      MCOImageRaster(width: 1, height: 1, profile: 16, pixels: [0]),
      MCOImageRaster(width: 1, height: 1, profile: 9, pixels: [1]),
      MCOImageRaster(width: 2, height: 2, profile: 0, pixels: [0])
    ] {
      #expect(throws: MCOImageCodecError.damaged) { try MCOImageV3Codec.encode(image, nonce: 0) }
    }
    #expect(throws: MCOImageCodecError.damaged) { try MCOImageBase91.decode("A\\") }
  }

  @Test func dynamicProfilesUseGlobal512ReferencesAndExactPaletteColors() throws {
    for profile in 0..<MCOImagePalette.profileCount {
      let references = MCOImagePalette.profileReferences(profile)
      #expect(MCOImagePalette.colors(profile: profile).count == references.count)
      for (stored, ref) in references.enumerated() {
        #expect(try MCOImagePalette.ref(fromStored: stored, profile: profile) == ref)
        #expect(try MCOImagePalette.stored(fromRef: ref, profile: profile) == stored)
      }
      #expect(MCOImagePalette.color(MCOImagePalette.white(profile: profile), profile: profile) == 0xFFFFFF)
      #expect(MCOImagePalette.color(MCOImagePalette.black(profile: profile), profile: profile) == 0)
    }
    #expect(MCOImagePalette.colors(profile: 9) == MCOImagePalette.colors(profile: 2))
    #expect(MCOImagePalette.colors(profile: 10) == MCOImagePalette.colors(profile: 3))
    #expect(MCOImagePalette.colors(profile: 11) == MCOImagePalette.colors(profile: 4))
    #expect(MCOImagePalette.colors(profile: 12) == MCOImagePalette.colors(profile: 5))
  }
}
