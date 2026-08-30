import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Incoming avatar flight")
@MainActor
struct IncomingAvatarFlightTests {
  @Test
  func `same-sender admit starts a flight before the next run loop`() {
    let flight = IncomingAvatarFlight()
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")
    let fromFrame = CGRect(x: 12, y: 500, width: 28, height: 28)

    viewModel.appendMessageIfNew(first)
    flight.reportFrame(fromFrame, for: first.id)
    viewModel.appendMessageIfNew(second)

    #expect(flight.flight?.fromID == first.id)
    #expect(flight.flight?.toID == second.id)
    #expect(flight.isFlying(second.id))
    #expect(flight.drawnFrame(progress: 0)?.origin == fromFrame.origin)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)
  }

  @Test
  func `beginFlight uses the continued cluster-end, not an older cluster`() {
    let flight = IncomingAvatarFlight()
    let a = UUID()
    let b = UUID()
    let c = UUID()
    let d = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: a)
    flight.reportFrame(CGRect(x: 0, y: 400, width: 28, height: 28), for: c)

    flight.beginFlight(from: c, to: d, identity: .initials(name: "Alice"))

    #expect(flight.flight?.fromID == c)
    #expect(flight.flight?.toID == d)
    #expect(flight.isFlying(a) == false)
    #expect(flight.isFlying(b) == false)
  }

  @Test
  func `scrolled-up admit does not start a flight`() {
    let flight = IncomingAvatarFlight()
    flight.isAtBottom = false
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")

    viewModel.appendMessageIfNew(first)
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: first.id)
    viewModel.appendMessageIfNew(second)

    #expect(flight.flight == nil)
    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)
  }

  @Test
  func `missing from-frame does not start a flight`() {
    let flight = IncomingAvatarFlight()
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")

    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)

    #expect(flight.flight == nil)
  }

  @Test
  func `rebake that keeps the tail id does not start a flight`() {
    let flight = IncomingAvatarFlight()
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")

    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)
    flight.complete()
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: second.id)

    viewModel.buildItems()

    #expect(flight.flight == nil)
    #expect(viewModel.items.last?.id == second.id)
  }

  @Test
  func `status preview and photo patches do not start a flight`() {
    let flight = IncomingAvatarFlight()
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: first.id)

    viewModel.timeline.applyStatusUpdate(messageID: first.id, status: .delivered)
    #expect(flight.flight == nil)

    viewModel.timeline.writer?.updateRenderItem(id: first.id) { item in
      item.with(envelope: item.envelope.with(incomingAvatar: .initials(name: "Alice")))
    }
    #expect(flight.flight == nil)

    viewModel.timeline.writer?.updateRenderItem(id: first.id) { item in
      item.with(
        envelope: item.envelope.with(
          incomingAvatar: IncomingAvatarIdentity(
            name: "Alice",
            matchedContactID: UUID(),
            imageRevision: 1
          )
        )
      )
    }
    #expect(flight.flight == nil)
  }

  @Test
  func `removing a cluster-end does not start a flight`() {
    let flight = IncomingAvatarFlight()
    let viewModel = boundViewModel(flight: flight)
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    flight.reportFrame(CGRect(x: 0, y: 400, width: 28, height: 28), for: first.id)
    viewModel.appendMessageIfNew(second)
    flight.complete()
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: second.id)

    viewModel.timeline.removeMessage(second.id)

    #expect(flight.flight == nil)
    #expect(viewModel.items[0].envelope.incomingAvatar != nil)
  }

  @Test
  func `unbind drops the stored frame so a later hop cannot use it`() {
    let flight = IncomingAvatarFlight()
    let a = UUID()
    let c = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: a)
    flight.unbind(a)

    #expect(flight.frame(for: a) == nil)
    flight.beginFlight(from: a, to: c, identity: .initials(name: "Alice"))
    #expect(flight.flight == nil)
  }

  @Test
  func `identity strip without a flight unbinds the stored frame`() {
    let flight = IncomingAvatarFlight()
    let messageID = UUID()
    let holder = GutterIdentityHolder(identity: .initials(name: "Alice"))
    let host = UIHostingController(
      rootView: IdentityStripHost(holder: holder, messageID: messageID)
        .environment(\.incomingAvatarFlight, flight)
    )
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 80))
    window.rootViewController = host
    window.isHidden = false
    window.layoutIfNeeded()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: messageID)

    holder.identity = nil
    window.layoutIfNeeded()

    #expect(flight.frame(for: messageID) == nil)
  }

  @Test
  func `identity strip during flight keeps the from-frame`() {
    let flight = IncomingAvatarFlight()
    let from = UUID()
    let to = UUID()
    let holder = GutterIdentityHolder(identity: .initials(name: "Alice"))
    let host = UIHostingController(
      rootView: IdentityStripHost(holder: holder, messageID: from)
        .environment(\.incomingAvatarFlight, flight)
    )
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 80))
    window.rootViewController = host
    window.isHidden = false
    window.layoutIfNeeded()
    let fromFrame = CGRect(x: 0, y: 100, width: 28, height: 28)
    flight.reportFrame(fromFrame, for: from)
    flight.beginFlight(from: from, to: to, identity: .initials(name: "Alice"))

    holder.identity = nil
    window.layoutIfNeeded()

    #expect(flight.isFlying(from))
    #expect(flight.frame(for: from) != nil)
  }

  @Test
  func `rebind reports the new id and clears the old`() {
    let flight = IncomingAvatarFlight()
    let a = UUID()
    let b = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: a)
    flight.unbind(a)
    flight.reportFrame(CGRect(x: 0, y: 200, width: 28, height: 28), for: b)

    #expect(flight.frame(for: a) == nil)
    #expect(flight.frame(for: b)?.origin.y == 200)
  }

  @Test
  func `at rest only cluster-ends report; during flight only from and to`() {
    let flight = IncomingAvatarFlight()
    let ids = (0..<12).map { _ in UUID() }
    let from = ids[10]
    let to = ids[11]

    for id in ids {
      #expect(flight.shouldReportFrame(messageID: id, hasIdentity: id == from) == (id == from))
    }

    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: from)
    flight.beginFlight(from: from, to: to, identity: .initials(name: "Alice"))

    for id in ids {
      let expected = id == from || id == to
      #expect(flight.shouldReportFrame(messageID: id, hasIdentity: id == to) == expected)
    }

    flight.complete()
    for id in ids {
      #expect(flight.shouldReportFrame(messageID: id, hasIdentity: id == to) == (id == to))
    }
  }

  @Test
  func `reportFrame does not invalidate a gutter that only reads the flying flag`() {
    let flight = IncomingAvatarFlight()
    let probeID = UUID()
    let counter = BodyCounter()
    let host = UIHostingController(
      rootView: FlyingFlagProbe(messageID: probeID, counter: counter)
        .environment(\.incomingAvatarFlight, flight)
    )
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 200))
    window.rootViewController = host
    window.isHidden = false
    window.layoutIfNeeded()
    let afterAppear = counter.count

    let noise = UUID()
    for index in 0..<30 {
      flight.reportFrame(
        CGRect(x: 0, y: CGFloat(index), width: 28, height: 28),
        for: noise
      )
    }
    window.layoutIfNeeded()

    #expect(counter.count == afterAppear)
  }

  @Test
  func `overlapping beginFlight cancels instead of splicing`() {
    let flight = IncomingAvatarFlight()
    let a = UUID()
    let b = UUID()
    let c = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: a)
    flight.beginFlight(from: a, to: b, identity: .initials(name: "Alice"))
    flight.reportFrame(CGRect(x: 0, y: 180, width: 28, height: 28), for: b)
    flight.beginFlight(from: b, to: c, identity: .initials(name: "Alice"))

    #expect(flight.flight == nil)
    #expect(flight.isFlying(a) == false)
    #expect(flight.isFlying(b) == false)
  }

  @Test
  func `drawn frame lerps live frames not a captured snapshot`() {
    let flight = IncomingAvatarFlight()
    let from = UUID()
    let to = UUID()
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: from)
    flight.beginFlight(from: from, to: to, identity: .initials(name: "Alice"))
    flight.reportFrame(CGRect(x: 0, y: 600, width: 28, height: 28), for: to)

    #expect(flight.drawnFrame(progress: 0.5)?.origin.y == 550)

    flight.reportFrame(CGRect(x: 0, y: 400, width: 28, height: 28), for: from)
    flight.reportFrame(CGRect(x: 0, y: 500, width: 28, height: 28), for: to)

    #expect(flight.drawnFrame(progress: 0.5)?.origin.y == 450)
  }

  @Test
  func `reserved empty gutter keeps padding-only height`() {
    let messageID = UUID()
    let gutterHeight = measureHeight(
      IncomingAvatarGutter(identity: nil, reserveColumn: true, messageID: messageID) {
        Text("Hi")
      }
    )
    let paddedHeight = measureHeight(
      Text("Hi").padding(.leading, IncomingBubbleAvatarMetrics.columnWidth)
    )
    #expect(gutterHeight == paddedHeight)
  }

  @Test
  func `rooms fly the matching prefix cluster only`() {
    let flight = IncomingAvatarFlight()
    let viewModel = RoomConversationViewModel()
    viewModel.incomingAvatarFlight = flight
    let aa = Data([0xAA])
    let bb = Data([0xBB])
    let firstBB = roomMessage(ts: 100, prefix: bb, name: "Alice")
    let firstAA = roomMessage(ts: 160, prefix: aa, name: "Alice")
    let followAA = roomMessage(ts: 220, prefix: aa, name: "Alice")

    viewModel.appendMessageIfNew(firstBB)
    viewModel.appendMessageIfNew(firstAA)
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: firstBB.id)
    flight.reportFrame(CGRect(x: 0, y: 200, width: 28, height: 28), for: firstAA.id)
    viewModel.appendMessageIfNew(followAA)

    #expect(flight.flight?.fromID == firstAA.id)
    #expect(flight.flight?.toID == followAA.id)
    #expect(flight.isFlying(firstBB.id) == false)
  }

  @Test
  func `coalesced room loadMessages replace does not start a flight`() {
    let flight = IncomingAvatarFlight()
    let viewModel = RoomConversationViewModel()
    viewModel.incomingAvatarFlight = flight
    let prefix = Data([0xAA])
    let first = roomMessage(ts: 100, prefix: prefix, name: "Alice")
    let second = roomMessage(ts: 160, prefix: prefix, name: "Alice")
    viewModel.appendMessageIfNew(first)
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: first.id)
    viewModel.appendMessageIfNew(second)
    flight.complete()
    flight.reportFrame(CGRect(x: 0, y: 200, width: 28, height: 28), for: second.id)

    viewModel.messages = [first, second]

    #expect(flight.flight == nil)
  }

  @Test
  func `overlay hides the flying avatar from accessibility`() {
    let flight = IncomingAvatarFlight()
    let from = UUID()
    let to = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: from)
    flight.beginFlight(from: from, to: to, identity: .initials(name: "Alice"))

    let overlayHost = UIHostingController(rootView: flight.overlay().frame(width: 320, height: 400))
    let visibleHost = UIHostingController(
      rootView: ContactAvatar(name: "Alice", size: IncomingBubbleAvatarMetrics.size)
        .frame(width: 320, height: 400)
    )
    let overlayWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
    overlayWindow.rootViewController = overlayHost
    overlayWindow.isHidden = false
    overlayWindow.layoutIfNeeded()
    let visibleWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
    visibleWindow.rootViewController = visibleHost
    visibleWindow.isHidden = false
    visibleWindow.layoutIfNeeded()

    let overlayLabels = accessibilityLabels(in: overlayHost.view)
    let visibleLabels = accessibilityLabels(in: visibleHost.view)
    #expect(visibleLabels.contains { $0 == "A" || $0.localizedCaseInsensitiveContains("Alice") })
    #expect(overlayLabels.contains { $0 == "A" || $0.localizedCaseInsensitiveContains("Alice") } == false)
  }

  @Test
  func `cell wrap injects the flight environment; nil default still builds`() {
    let flight = IncomingAvatarFlight()
    let box = FlightBox()
    let wrapped = EnvironmentProbe(box: box)
      .environment(\.incomingAvatarFlight, flight)
    let host = UIHostingController(rootView: wrapped)
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 80))
    window.rootViewController = host
    window.isHidden = false
    window.layoutIfNeeded()
    #expect(box.value != nil)

    let gutter = IncomingAvatarGutter(
      identity: .initials(name: "Alice"),
      reserveColumn: true,
      messageID: UUID()
    ) {
      Text("Hi")
    }
    let nilHost = UIHostingController(rootView: gutter)
    let nilWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 80))
    nilWindow.rootViewController = nilHost
    nilWindow.isHidden = false
    nilWindow.layoutIfNeeded()
    #expect(nilHost.view != nil)
  }

  @Test
  func `reduce motion skips beginFlight`() {
    let flight = IncomingAvatarFlight()
    flight.reduceMotion = true
    let from = UUID()
    flight.reportFrame(CGRect(x: 0, y: 100, width: 28, height: 28), for: from)
    flight.beginFlight(from: from, to: UUID(), identity: .initials(name: "Alice"))
    #expect(flight.flight == nil)
  }
}

@MainActor
private func boundViewModel(flight: IncomingAvatarFlight) -> ChatViewModel {
  let viewModel = ChatViewModel()
  viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
  viewModel.incomingAvatarFlight = flight
  return viewModel
}

private func createChannelMessage(
  timestamp: UInt32,
  senderName: String? = nil
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: UUID(),
    contactID: nil,
    channelIndex: 0,
    text: "Test message",
    timestamp: timestamp,
    createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
    direction: .incoming,
    status: .delivered,
    textType: .plain,
    ackCode: nil,
    pathLength: 0,
    snr: nil,
    senderKeyPrefix: nil,
    senderNodeName: senderName,
    isRead: false,
    replyToID: nil,
    roundTripTime: nil,
    heardRepeats: 0,
    retryAttempt: 0,
    maxRetryAttempts: 0
  )
}

private func roomMessage(
  ts: UInt32,
  prefix: Data,
  name: String
) -> RoomMessageDTO {
  RoomMessageDTO(
    sessionID: UUID(),
    authorKeyPrefix: prefix,
    authorName: name,
    text: "msg",
    timestamp: ts
  )
}

@MainActor
private func measureHeight(_ view: some View) -> CGFloat {
  let host = UIHostingController(rootView: view)
  let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
  window.rootViewController = host
  window.isHidden = false
  window.layoutIfNeeded()
  return host.sizeThatFits(in: CGSize(width: 390, height: 800)).height
}

@MainActor
private func accessibilityLabels(in view: UIView) -> [String] {
  var labels: [String] = []
  if view.isAccessibilityElement, let label = view.accessibilityLabel, !label.isEmpty {
    labels.append(label)
  }
  if let elements = view.accessibilityElements {
    for element in elements {
      guard let object = element as? NSObject,
            let label = object.accessibilityLabel,
            !label.isEmpty else { continue }
      labels.append(label)
    }
  }
  for subview in view.subviews {
    labels.append(contentsOf: accessibilityLabels(in: subview))
  }
  return labels
}

private final class BodyCounter {
  var count = 0
}

private struct FlyingFlagProbe: View {
  let messageID: UUID
  let counter: BodyCounter
  @Environment(\.incomingAvatarFlight) private var flight

  var body: some View {
    Text(flight?.isFlying(messageID) == true ? "flying" : "rest")
      .background(BodyTick(counter: counter))
  }
}

private struct BodyTick: View {
  let counter: BodyCounter

  var body: Color {
    counter.count += 1
    return Color.clear
  }
}

private final class FlightBox {
  var value: IncomingAvatarFlight?
}

@Observable
@MainActor
private final class GutterIdentityHolder {
  var identity: IncomingAvatarIdentity?

  init(identity: IncomingAvatarIdentity?) {
    self.identity = identity
  }
}

private struct IdentityStripHost: View {
  let holder: GutterIdentityHolder
  let messageID: UUID

  var body: some View {
    IncomingAvatarGutter(
      identity: holder.identity,
      reserveColumn: true,
      messageID: messageID
    ) {
      Text("Hi")
    }
  }
}

private struct EnvironmentProbe: View {
  let box: FlightBox
  @Environment(\.incomingAvatarFlight) private var flight

  var body: some View {
    Color.clear
      .onAppear { box.value = flight }
  }
}
