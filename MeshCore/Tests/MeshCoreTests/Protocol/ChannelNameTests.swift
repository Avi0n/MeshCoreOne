import Foundation
@testable import MeshCore
import Testing

@Suite("Channel Name")
struct ChannelNameTests {
  @Test(arguments: [
    (String(repeating: "a", count: 28) + "😀", String(repeating: "a", count: 28)),
    (String(repeating: "a", count: 27) + "😀", String(repeating: "a", count: 27) + "😀")
  ])
  func `name survives the firmware C string copy`(name: String, expected: String) {
    let secret = Data(repeating: 0xAA, count: 16)
    let packet = PacketBuilder.setChannel(index: 2, name: name, secret: secret)
    let storedName = Data(packet[2..<33].prefix(while: { $0 != 0 })).paddedOrTruncated(to: 32)
    var response = Data([2])
    response.append(storedName)
    response.append(secret)

    #expect(packet.count == 50)
    #expect(packet[33] == 0)
    #expect(String(data: storedName.prefix(while: { $0 != 0 }), encoding: .utf8) == expected)
    guard case let .channelInfo(channel) = Parsers.ChannelInfo.parse(response) else {
      Issue.record("Expected channelInfo")
      return
    }
    #expect(channel.name == expected)
    #expect(channel.secret == secret)
  }
}
