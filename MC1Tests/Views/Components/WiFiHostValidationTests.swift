import Foundation
@testable import MC1
import Testing

@Suite("WiFi host validation")
struct WiFiHostValidationTests {
  @Test(arguments: [
    ("192.168.1.50", true),
    ("radio.local", true),
    ("example.com", true),
    ("repeater", true),
    ("  radio.local  ", true),
    ("", false),
    ("999.999.999.999", false),
    ("192.168.1", false),
    ("http://radio.local", false),
  ])
  func `classifies host`(_ host: String, _ expected: Bool) {
    #expect(WiFiAddressFields.isValidHost(host) == expected)
  }
}
