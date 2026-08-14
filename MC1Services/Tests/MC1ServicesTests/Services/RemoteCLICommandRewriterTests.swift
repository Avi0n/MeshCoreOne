import Foundation
@testable import MC1Services
import Testing

@Suite("RemoteCLICommandRewriter")
struct RemoteCLICommandRewriterTests {
  private static let now = Date(timeIntervalSince1970: 1_786_722_487)
  private static let expected = "time 1786722487"

  @Test
  func `clock sync becomes time with the host epoch`() {
    #expect(RemoteCLICommandRewriter.rewrite("clock sync", now: Self.now) == Self.expected)
  }

  @Test(arguments: ["CLOCK SYNC", "  clock   sync  ", "Clock Sync"])
  func `clock sync match is case and whitespace insensitive`(command: String) {
    #expect(RemoteCLICommandRewriter.rewrite(command, now: Self.now) == Self.expected)
  }

  @Test(arguments: ["clock", "time 123", "clock sync extra", "sync_time", "st"])
  func `other commands are left unchanged`(command: String) {
    #expect(RemoteCLICommandRewriter.rewrite(command, now: Self.now) == command)
  }

  @Test
  func `pre epoch dates saturate to zero`() {
    let preEpoch = Date(timeIntervalSince1970: -1000)
    #expect(RemoteCLICommandRewriter.rewrite("clock sync", now: preEpoch) == "time 0")
  }
}
