import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Connection link Wi-Fi dial")
struct ConnectionLinkWiFiDialTests {
  @Test
  func `a live session keeps its endpoint`() {
    let dial = ConnectionLinkWiFiDial.capture(
      liveTransport: .wifi,
      savedWiFi: .wifi(host: "  radio.local  ", port: 4242, displayName: nil),
      snapshotHost: "10.0.0.8"
    )
    #expect(dial == ConnectionLinkWiFiDial(host: "radio.local", port: 4242))
  }

  @Test
  func `a live session does not replace a missing endpoint with a reported address`() {
    let dial = ConnectionLinkWiFiDial.capture(
      liveTransport: .wifi,
      savedWiFi: .wifi(host: " ", port: 4242, displayName: nil),
      snapshotHost: "10.0.0.8"
    )
    #expect(dial == nil)
  }

  @Test
  func `a saved endpoint wins over a different reported address`() {
    let dial = ConnectionLinkWiFiDial.capture(
      liveTransport: .bluetooth,
      savedWiFi: .wifi(host: "radio.example", port: 4242, displayName: "Proxy"),
      snapshotHost: "10.0.0.8"
    )
    #expect(dial == ConnectionLinkWiFiDial(host: "radio.example", port: 4242))
  }

  @Test
  func `a reported address uses the default port when nothing is saved`() {
    let dial = ConnectionLinkWiFiDial.capture(
      liveTransport: .bluetooth,
      savedWiFi: nil,
      snapshotHost: "  10.0.0.8  "
    )
    #expect(dial == ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort))
  }

  @Test(arguments: [
    ConnectionMethod?.none,
    .wifi(host: " ", port: 4242, displayName: nil),
    .wifi(host: "bad host", port: 4242, displayName: nil),
    .wifi(host: "radio.local", port: 0, displayName: nil),
    .bluetooth(peripheralUUID: UUID(), displayName: nil)
  ])
  func `an unusable saved endpoint is ignored`(_ saved: ConnectionMethod?) {
    let dial = ConnectionLinkWiFiDial.capture(
      liveTransport: .bluetooth,
      savedWiFi: saved,
      snapshotHost: "10.0.0.8"
    )
    #expect(dial == ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort))
  }

  @Test
  func `no usable source returns nil`() {
    #expect(
      ConnectionLinkWiFiDial.capture(
        liveTransport: .bluetooth,
        savedWiFi: .wifi(host: "bad host", port: 4242, displayName: nil),
        snapshotHost: nil
      ) == nil
    )
    #expect(
      ConnectionLinkWiFiDial.capture(
        liveTransport: nil,
        savedWiFi: nil,
        snapshotHost: "   "
      ) == nil
    )
    #expect(
      ConnectionLinkWiFiDial.capture(
        liveTransport: .bluetooth,
        savedWiFi: nil,
        snapshotHost: "999.1.1.1"
      ) == nil
    )
  }
}
