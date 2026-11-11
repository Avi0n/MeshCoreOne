@testable import MC1Services
import Testing

@Suite("MECP decode")
struct MECPDecodeTests {
  @Test
  func `empty string, bare prefix, bad severity, and lowercase prefix are nil`() {
    #expect(MECPDecode.decode("") == nil)
    #expect(MECPDecode.decode("MECP/") == nil)
    #expect(MECPDecode.decode("MECP/5/M01") == nil)
    #expect(MECPDecode.decode("mecp/0/M01") == nil)
  }

  @Test
  func `a valid severity is a message when the slash is missing`() throws {
    let bare = try #require(MECPDecode.decode("MECP/0"))
    #expect(bare.severity == .mayday)
    #expect(bare.codes.isEmpty)
    #expect(bare.freetext == nil)

    let skippedDelimiter = try #require(MECPDecode.decode("MECP/0XM01"))
    #expect(skippedDelimiter.codes == ["M01"])
    #expect(skippedDelimiter.freetext == nil)

    let emptyBody = try #require(MECPDecode.decode("MECP/0/"))
    #expect(emptyBody.codes.isEmpty)
    #expect(emptyBody.freetext == nil)
  }

  @Test
  func `codes stop at the first token that is not a code and a later code stays in the tail`() throws {
    let message = try #require(MECPDecode.decode("MECP/1/M09 12pax T03"))
    #expect(message.codes == ["M09"])
    #expect(message.personCount == 12)
    #expect(message.displayTail == "T03")
  }

  @Test
  func `drill and beacon codes are ordinary codes`() throws {
    let drill = try #require(MECPDecode.decode("MECP/0/D01 M01"))
    #expect(drill.codes == ["D01", "M01"])
    #expect(drill.freetext == nil)

    let beacon = try #require(MECPDecode.decode("MECP/0/B01 M01"))
    #expect(beacon.codes == ["B01", "M01"])
    #expect(beacon.freetext == nil)
  }

  @Test
  func `empty tokens are dropped before the code scan`() throws {
    let message = try #require(MECPDecode.decode("MECP/0/M01  M02"))
    #expect(message.codes == ["M01", "M02"])
    #expect(message.freetext == nil)
  }

  @Test
  func `a repeated space before an R03 eta still fills eta and clears the tail`() throws {
    let message = try #require(MECPDecode.decode("MECP/1/R03  15"))
    #expect(message.codes == ["R03"])
    #expect(message.eta == 15)
    #expect(message.displayTail == nil)

    let spaced = try #require(MECPDecode.decode("MECP/1/R03 15"))
    #expect(spaced.eta == 15)
    #expect(spaced.displayTail == nil)

    let kept = try #require(MECPDecode.decode("MECP/1/R03 15 near"))
    #expect(kept.eta == 15)
    #expect(kept.displayTail == "near")
  }

  @Test
  func `a timestamp must be four digits at a token boundary and in range`() throws {
    let punctuated = try #require(MECPDecode.decode("MECP/1/M01 arrived @1430."))
    #expect(punctuated.timestampHHMM == nil)
    #expect(punctuated.displayTail == "arrived @1430.")

    let invalid = try #require(MECPDecode.decode("MECP/3/R01 @2400 @1430"))
    #expect(invalid.timestampHHMM == nil)
    #expect(invalid.displayTail == "@2400 @1430")

    let accepted = try #require(MECPDecode.decode("MECP/0/M01 @1430 @en"))
    #expect(accepted.timestampHHMM == "1430")
    #expect(accepted.displayTail == "@en")
  }

  @Test
  func `decode keeps a string longer than the sender byte budget`() throws {
    #expect(MECPMessage.maxUTF8Bytes == 200)
    let input = "MECP/0/" + String(repeating: "A", count: MECPMessage.maxUTF8Bytes)
    let message = try #require(MECPDecode.decode(input))
    #expect(message.severity == .mayday)
    #expect(message.codes.isEmpty)
    #expect(message.freetext == String(repeating: "A", count: MECPMessage.maxUTF8Bytes))
  }
}
