import Foundation
@testable import MC1Services
import Testing

@Suite("Log redaction")
struct LogRedactionTests {
  @Test
  func `set wifi pwd redacts the value`() {
    let redacted = LogRedaction.cliCommand("set wifi.pwd hunter2")
    #expect(redacted == "set wifi.pwd [REDACTED]")
    #expect(!redacted.contains("hunter2"))
  }

  @Test
  func `set pin redacts the value`() {
    let redacted = LogRedaction.cliCommand("set pin 123456")
    #expect(redacted == "set pin [REDACTED]")
    #expect(!redacted.contains("123456"))
  }

  @Test
  func `get wifi pwd is unchanged`() {
    #expect(LogRedaction.cliCommand("get wifi.pwd") == "get wifi.pwd")
  }
}
