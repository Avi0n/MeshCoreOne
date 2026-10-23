import Foundation
@testable import MC1Services
import Testing

@Suite("RadioCLIReply")
struct RadioCLIReplyTests {
  @Test
  func `value lines keep the text after the prompt verbatim`() {
    #expect(RadioCLIReply(text: "> -7") == .value("-7"))
    #expect(RadioCLIReply(text: "> ") == .value(""))
    #expect(RadioCLIReply(text: "> my net ") == .value("my net "))
    #expect(RadioCLIReply(text: "> my net \r\n") == .value("my net "))
    #expect(RadioCLIReply(text: "> -7\r\n") == .value("-7"))
    #expect(
      RadioCLIReply(text: "> wifi.ssid is now x (set wifi.pwd too, then reboot)")
        == .value("wifi.ssid is now x (set wifi.pwd too, then reboot)")
    )
  }

  @Test
  func `OK lines are ok`() {
    #expect(RadioCLIReply(text: "OK - interval rounded to 16") == .ok("OK - interval rounded to 16"))
  }

  @Test
  func `Error ERROR and ERR prefixes are errors`() {
    #expect(RadioCLIReply(text: "Error, must be 0-2") == .error("Error, must be 0-2"))
    #expect(RadioCLIReply(text: "ERROR: af must be 0-9") == .error("ERROR: af must be 0-9"))
    #expect(RadioCLIReply(text: "ERR - repeat now deprecated") == .error("ERR - repeat now deprecated"))
    #expect(RadioCLIReply(text: "Error: unsupported") == .error("Error: unsupported"))
  }

  @Test
  func `Unknown command is its own case and empty text is other`() {
    #expect(RadioCLIReply(text: "Unknown command") == .unknownCommand)
    #expect(RadioCLIReply(text: "") == .other(""))
    #expect(RadioCLIReply(text: "\r\n") == .other(""))
  }
}
