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
  func `a message needs the slash after the severity and at least one code`() {
    #expect(MECPDecode.decode("MECP/0") == nil)
    #expect(MECPDecode.decode("MECP/0XM01") == nil)
    #expect(MECPDecode.decode("MECP/0/") == nil)
    #expect(MECPDecode.decode("MECP/01/M01") == nil)
    #expect(MECPDecode.decode("MECP/1/t05 x") == nil)
    #expect(MECPDecode.decode("MECP/0/M011") == nil)
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
  func `codes and free text are separated by exactly one space`() {
    #expect(MECPDecode.decode("MECP/0/M01  M02") == nil)
    #expect(MECPDecode.decode("MECP/0/M01\tM02") == nil)
    #expect(MECPDecode.decode("MECP/0/M01\u{A0}help") == nil)
    #expect(MECPDecode.decode("MECP/1/R03  15") == nil)
    #expect(MECPDecode.decode("MECP/1/T05 ") == nil)
    #expect(MECPDecode.decode("MECP/1/T05 help ") == nil)
    #expect(MECPDecode.decode("MECP/1/T05 help\t") == nil)
  }

  @Test
  func `a code-shaped word that is not a whole code starts the free text`() throws {
    let glued = try #require(MECPDecode.decode("MECP/1/T05 T05x"))
    #expect(glued.codes == ["T05"])
    #expect(glued.freetext == "T05x")

    let spaced = try #require(MECPDecode.decode("MECP/1/T05 T05  x"))
    #expect(spaced.codes == ["T05"])
    #expect(spaced.freetext == "T05  x")
  }

  @Test
  func `free text is kept as sent`() throws {
    let message = try #require(MECPDecode.decode("MECP/3/L01 see  you\tthere"))
    #expect(message.freetext == "see  you\tthere")
  }

  @Test
  func `one trailing line ending is removed before matching`() throws {
    for input in ["MECP/2/B03\n", "MECP/2/B03\r", "MECP/2/B03\r\n"] {
      let message = try #require(MECPDecode.decode(input))
      #expect(message.codes == ["B03"])
      #expect(message.freetext == nil)
    }
    let text = try #require(MECPDecode.decode("MECP/3/L01 see you\r\n"))
    #expect(text.freetext == "see you")

    #expect(MECPDecode.decode("MECP/2/B03\n\n") == nil)
    #expect(MECPDecode.decode("MECP/2/B03\n\r") == nil)
    #expect(MECPDecode.decode("MECP/2/B03\r\n\r\n") == nil)
  }

  @Test
  func `a line break inside the message is not MECP`() {
    for lineBreak in ["\n", "\r", "\r\n", "\u{0C}", "\u{85}", "\u{2028}", "\u{2029}"] {
      #expect(MECPDecode.decode("MECP/3/L01 a\(lineBreak)b") == nil)
    }
  }

  @Test
  func `an R03 eta fills eta and clears the tail`() throws {
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
    let input = "MECP/0/M01 " + String(repeating: "A", count: MECPMessage.maxUTF8Bytes)
    let message = try #require(MECPDecode.decode(input))
    #expect(message.severity == .mayday)
    #expect(message.codes == ["M01"])
    #expect(message.freetext == String(repeating: "A", count: MECPMessage.maxUTF8Bytes))
  }
}
